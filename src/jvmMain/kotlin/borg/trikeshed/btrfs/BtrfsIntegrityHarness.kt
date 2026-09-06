package borg.trikeshed.btrfs

import borg.trikeshed.cas.CasPaths
import borg.trikeshed.job.ContentId
import borg.trikeshed.reflink.InMemoryReferenceCounter
import borg.trikeshed.userspace.nio.channels.spi.JvmProcessOperations
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * VAL-BTRFS-005 lane w-m5-integrity — WHOLE-SET cid re-verification at the application
 * layer, on the live btrfs volume, through TrikeShed's own store code.
 *
 * The blob set includes `sha256/<h0>/<h1>/<h2>/<h3>/<60hex>` and legacy `sha256/<2hex>/<62hex>`.
 * Nothing else under the mount
 * is a blob. `.tmp` residue is counted separately and must be zero.
 *
 * Every blob in that set — the FULL set, never a sample — is re-verified three ways:
 *   1. the cid implied by concatenating the hex path components is handed to
 *      `BtrfsReflinkStore.get()`, which itself re-hashes and throws on divergence;
 *   2. sha256 is recomputed here over the returned bytes and compared to `cid.hex`;
 *   3. the canonical or legacy path is compared to the path the blob was found at, so a blob
 *      cannot sit at a path the store would never address.
 *
 * The named exclusion list (VAL-BTRFS-002's planted-divergence cid) is passed in BY
 * NAME with `--exclude <cid>` and may be empty. It is consumed by name only: a mismatch
 * at any other cid is a hard failure and exits non-zero. It is never widened here.
 */
private var vstep = 0

private fun vsay(msg: String) {
    vstep++
    println("[integrity %04d] %s".format(vstep, msg))
    System.out.flush()
}

private fun hex256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** The current and legacy layouts both belong to the stored blob set. */
private fun countingRuleBlobSet(casRoot: Path): List<Path> {
    val sha = casRoot.resolve("sha256")
    if (!Files.isDirectory(sha)) return emptyList()
    val layout = Regex("(?:[0-9a-f]/){4}[0-9a-f]{60}|[0-9a-f]{2}/[0-9a-f]{62}")
    Files.walk(sha, 5).use { paths ->
        return paths.filter { Files.isRegularFile(it) && layout.matches(sha.relativize(it).joinToString("/")) }
            .sorted().toList()
    }
}

private fun tmpResidueOf(casRoot: Path): List<Path> {
    if (!Files.isDirectory(casRoot)) return emptyList()
    Files.walk(casRoot).use { s ->
        return s.filter {
            Files.isRegularFile(it) &&
                (it.fileName.toString().endsWith(".tmp") || it.fileName.toString().startsWith("."))
        }.toList()
    }
}

fun main(argv: Array<String>) {
    var casRoot = "/mnt/trikeshed/forge/cas"
    var expected = -1
    val exclusions = LinkedHashSet<String>()
    var i = 0
    while (i < argv.size) {
        when (argv[i]) {
            "--cas-root" -> { casRoot = argv[i + 1]; i += 2 }
            "--expect-count" -> { expected = argv[i + 1].toInt(); i += 2 }
            "--exclude" -> { exclusions.add(argv[i + 1]); i += 2 }
            else -> { System.err.println("unknown arg: ${argv[i]}"); exitProcess(2) }
        }
    }

    vsay("=== BtrfsIntegrityHarness — VAL-BTRFS-005, lane w-m5-integrity ===")
    vsay("casRoot=$casRoot expectCount=$expected")
    vsay("NAMED EXCLUSION LIST (VAL-BTRFS-002, by cid): " +
        if (exclusions.isEmpty()) "EMPTY — no cid is excused; every blob must verify"
        else exclusions.joinToString(", "))
    vsay("JVM ATTRIBUTION: java.version=${System.getProperty("java.version")} " +
        "vendor=${System.getProperty("java.vendor")} arch=${System.getProperty("os.arch")} " +
        "pid=${ProcessHandle.current().pid()}")
    vsay("JVM ATTRIBUTION: sun.java.command=${System.getProperty("sun.java.command")}")
    vsay("JVM ATTRIBUTION: BtrfsReflinkStore loaded from " +
        BtrfsReflinkStore::class.java.protectionDomain.codeSource.location)
    vsay("JVM ATTRIBUTION: harness loaded from " +
        object {}.javaClass.protectionDomain.codeSource.location)

    val fsType = JvmFilesystemTypeProbe.typeOf(casRoot)
    val fsSource = JvmFilesystemTypeProbe.sourceOf(casRoot)
    vsay("PRE-READ BTRFS PROBE: casRoot='$casRoot' fstype=${fsType ?: "<undeterminable>"} source=${fsSource ?: "<undeterminable>"}")

    val fileOps = JvmFileOperations()
    val store = try {
        BtrfsReflinkStore(
            rootDir = casRoot,
            fileOps = fileOps,
            processOps = JvmProcessOperations(),
            refCounter = InMemoryReferenceCounter(),
            fsProbe = JvmFilesystemTypeProbe,
        )
    } catch (e: IllegalStateException) {
        vsay("REFUSED AT CONSTRUCTION — the pre-write btrfs guard fired: ${e.message}")
        exitProcess(3)
    }

    val casRootPath = Paths.get(casRoot)

    // ── the blob set, by the inherited counting rule ──────────────────────────
    val set = countingRuleBlobSet(casRootPath)
    vsay("BLOB SET: sha256/<h0>/<h1>/<h2>/<h3>/<60hex> plus legacy 2/62 : ${set.size} files")
    val allRegular = Files.walk(casRootPath).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
    vsay("all regular files under casRoot = ${allRegular.size} " +
        "(must equal the blob set, else something non-blob is hiding: ${allRegular.size == set.size})")
    val residue = tmpResidueOf(casRootPath)
    vsay(".tmp / dotfile residue under casRoot = ${residue.size} ${residue.joinToString(" ")}")

    // ── the FULL-SET re-verification ─────────────────────────────────────────
    vsay("--- FULL-SET RE-VERIFICATION (every blob, not a sample) ---")
    var verified = 0
    var excused = 0
    val mismatches = ArrayList<String>()
    var totalLogicalBytes = 0L

    for (p in set) {
        val hexFromPath = casRootPath.resolve("sha256").relativize(p).joinToString("")
        val cid = ContentId("sha256:$hexFromPath")

        val pathFor = store.pathFor(cid)
        val pathAgrees = Paths.get(pathFor).toAbsolutePath().normalize() == p.toAbsolutePath().normalize() ||
            casRootPath.resolve(CasPaths.legacyBlob(cid)).toAbsolutePath().normalize() == p.toAbsolutePath().normalize()

        var got: ByteArray? = null
        var thrown: String? = null
        try {
            got = store.get(cid)
        } catch (e: IllegalStateException) {
            thrown = "${e.javaClass.name}: ${e.message}"
        }

        val onDisk = Files.readAllBytes(p)
        val recomputed = hex256(onDisk)
        val ok = thrown == null && got != null && got.contentEquals(onDisk) &&
            recomputed == cid.hex && pathAgrees
        totalLogicalBytes += onDisk.size.toLong()

        if (ok) {
            verified++
            vsay("verify[%04d] OK  size=%-8d cid=%s recomputed=%s pathFor=agrees".format(
                verified, onDisk.size, cid.value, recomputed))
        } else if (exclusions.contains(cid.value) || exclusions.contains(cid.hex)) {
            excused++
            vsay("verify EXCUSED by the NAMED exclusion list: cid=${cid.value} " +
                "recomputed=$recomputed thrown=${thrown ?: "<none>"} pathAgrees=$pathAgrees")
        } else {
            val line = "MISMATCH path=$p cidFromPath=${cid.value} recomputed=$recomputed " +
                "storeGet=${if (thrown != null) "THREW $thrown" else "${got?.size} bytes"} pathAgrees=$pathAgrees"
            mismatches.add(line)
            vsay(line)
        }
    }

    vsay("--- RESULT ---")
    vsay("blob set size            = ${set.size}")
    vsay("verified (full set)      = $verified")
    vsay("excused by named list    = $excused")
    vsay("MISMATCHES               = ${mismatches.size}")
    vsay("logical blob bytes       = $totalLogicalBytes " +
        "(RECORDED FOR REFERENCE ONLY — VAL-BTRFS-005 forbids reconciling `Total to scrub` " +
        "against logical bytes; the physical denominator is `btrfs filesystem df`)")
    if (expected >= 0) {
        vsay("VAL-BTRFS-002 write count reconciliation: expected=$expected actual=${set.size} " +
            "match=${expected == set.size}")
    }
    mismatches.forEach { vsay("  $it") }

    val clean = mismatches.isEmpty() && residue.isEmpty() && allRegular.size == set.size &&
        (expected < 0 || expected == set.size) && set.isNotEmpty()
    vsay(if (clean) "FULL-SET CID RE-VERIFICATION: PASS — exit 0"
         else "FULL-SET CID RE-VERIFICATION: FAIL — exit 7")
    exitProcess(if (clean) 0 else 7)
}
