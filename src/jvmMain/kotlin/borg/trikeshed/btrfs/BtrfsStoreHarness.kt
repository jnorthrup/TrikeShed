package borg.trikeshed.btrfs

import borg.trikeshed.job.ContentId
import borg.trikeshed.reflink.InMemoryReferenceCounter
import borg.trikeshed.util.oroboros.FileCasStore
import borg.trikeshed.userspace.nio.channels.spi.JvmProcessOperations
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Random
import kotlin.system.exitProcess

/**
 * JVM implementation of [FilesystemTypeProbe].
 *
 * Resolves the NEAREST EXISTING ANCESTOR of the path — a CAS root that does not exist
 * yet must still be judged, and it has to be judged by the filesystem that would
 * receive the `mkdirs`. `FileStore.type()` returns the kernel's fs type name on Linux
 * (`btrfs`, `overlay`, `ext4`, `tmpfs`).
 */
object JvmFilesystemTypeProbe : FilesystemTypeProbe {

    override fun typeOf(path: String): String? = probe(path)?.first

    /** Device/source backing the path's filesystem — for the evidence record. */
    fun sourceOf(path: String): String? = probe(path)?.second

    /**
     * `/proc/self/mountinfo` is authoritative and is tried FIRST, because
     * `Files.getFileStore` FAILS on a btrfs subvolume: btrfs gives every subvolume its
     * own anonymous st_dev, so the JDK's mount-entry search walks up, stops at the
     * subvolume root, finds no `/proc/mounts` line whose directory equals it and throws
     * `IOException("Mount point not found")`. Longest-mountpoint-prefix matching has no
     * such blind spot, and it also answers correctly for a path that does not exist yet
     * — which is the case that matters, since that is the filesystem which would receive
     * the `mkdirs`.
     */
    fun probe(path: String): Pair<String, String>? {
        val abs = Paths.get(path).toAbsolutePath().normalize().toString()
        fromMountinfo(abs)?.let { return it }
        // Non-Linux fallback (macOS host smoke runs): nearest existing ancestor.
        var p: Path? = Paths.get(abs)
        while (p != null) {
            if (Files.exists(p)) {
                return try {
                    val fs = Files.getFileStore(p)
                    (fs.type()?.lowercase() ?: return null) to fs.name()
                } catch (e: Exception) {
                    null
                }
            }
            p = p.parent
        }
        return null
    }

    private fun unescape(s: String): String =
        s.replace("\\040", " ").replace("\\011", "\t").replace("\\012", "\n").replace("\\134", "\\")

    private fun fromMountinfo(abs: String): Pair<String, String>? {
        val mi = Paths.get("/proc/self/mountinfo")
        if (!Files.isReadable(mi)) return null
        var best: Pair<String, String>? = null
        var bestLen = -1
        for (line in Files.readAllLines(mi)) {
            val sep = line.indexOf(" - ")
            if (sep < 0) continue
            val head = line.substring(0, sep).split(" ")
            val tail = line.substring(sep + 3).split(" ")
            if (head.size < 5 || tail.size < 2) continue
            val mp = unescape(head[4])
            val fsType = tail[0]
            val src = unescape(tail[1])
            val isPrefix = abs == mp || abs.startsWith(if (mp.endsWith("/")) mp else "$mp/")
            if (isPrefix && mp.length >= bestLen) {
                bestLen = mp.length
                best = fsType.lowercase() to src
            }
        }
        return best
    }
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private var step = 0
private fun say(msg: String) {
    step++
    println("[harness %04d] %s".format(step, msg))
    System.out.flush()
}

private fun inodeOf(p: Path): String = try {
    Files.getAttribute(p, "unix:ino").toString()
} catch (e: Exception) {
    "<unavailable>"
}

private fun blobSet(casRoot: Path): List<Path> {
    // THE COUNTING RULE (VAL-BTRFS-002): the blob set is exactly the files matching
    // <casRoot>/sha256/<2hex>/<62hex>. Nothing else under the mount is a blob.
    val sha = casRoot.resolve("sha256")
    if (!Files.isDirectory(sha)) return emptyList()
    val hex = Regex("^[0-9a-f]+$")
    val out = ArrayList<Path>()
    Files.newDirectoryStream(sha).use { shards ->
        for (shard in shards) {
            val d = shard.fileName.toString()
            if (!Files.isDirectory(shard) || d.length != 2 || !hex.matches(d)) continue
            Files.newDirectoryStream(shard).use { files ->
                for (f in files) {
                    val n = f.fileName.toString()
                    if (Files.isRegularFile(f) && n.length == 62 && hex.matches(n)) out.add(f)
                }
            }
        }
    }
    return out.sortedBy { it.toString() }
}

private fun tmpResidue(casRoot: Path): List<Path> {
    if (!Files.isDirectory(casRoot)) return emptyList()
    Files.walk(casRoot).use { s ->
        return s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".tmp") }
            .toList()
    }
}

/**
 * Deterministic blob corpus: 50 blobs, of which the last three are 1 MiB, 2 MiB and
 * 4 MiB so VAL-BTRFS-003 has NON-INLINE extents to measure (btrfs `max_inline` is
 * 2 KiB by default; files at or under it live in metadata and fake shared extents).
 */
private fun corpus(count: Int): List<ByteArray> {
    val rnd = Random(0x7213_5EDL)
    val out = ArrayList<ByteArray>(count)
    val bigSizes = intArrayOf(1 shl 20, 2 shl 20, 4 shl 20)
    for (i in 0 until count) {
        val size = when {
            i >= count - 3 -> bigSizes[i - (count - 3)]
            else -> 4096 + (i * 1237) % 61440
        }
        val b = ByteArray(size)
        rnd.nextBytes(b)
        // stamp the index so no two blobs can collide by accident of the generator
        val stamp = "trikeshed-m5-blob-$i:".encodeToByteArray()
        System.arraycopy(stamp, 0, b, 0, minOf(stamp.size, b.size))
        out.add(b)
    }
    return out
}

fun main(argv: Array<String>) {
    var casRoot = "/mnt/trikeshed/forge/cas"
    var mode = "populate"
    var blobs = 50
    var scratch: String? = null
    var i = 0
    while (i < argv.size) {
        when (argv[i]) {
            "--cas-root" -> { casRoot = argv[i + 1]; i += 2 }
            "--mode" -> { mode = argv[i + 1]; i += 2 }
            "--blobs" -> { blobs = argv[i + 1].toInt(); i += 2 }
            "--scratch" -> { scratch = argv[i + 1]; i += 2 }
            else -> { System.err.println("unknown arg: ${argv[i]}"); exitProcess(2) }
        }
    }

    say("=== BtrfsStoreHarness — VAL-BTRFS-002 / VAL-BTRFS-003 ===")
    say("mode=$mode casRoot=$casRoot blobs=$blobs scratch=${scratch ?: "<none>"}")
    say("JVM ATTRIBUTION: java.version=${System.getProperty("java.version")} " +
        "vendor=${System.getProperty("java.vendor")} arch=${System.getProperty("os.arch")} " +
        "pid=${ProcessHandle.current().pid()}")
    say("JVM ATTRIBUTION: sun.java.command=${System.getProperty("sun.java.command")}")
    say("JVM ATTRIBUTION: BtrfsReflinkStore loaded from " +
        BtrfsReflinkStore::class.java.protectionDomain.codeSource.location)
    say("JVM ATTRIBUTION: harness loaded from " +
        object {}.javaClass.protectionDomain.codeSource.location)
    for (c in BtrfsReflinkStore::class.java.declaredConstructors) {
        say("JVM ATTRIBUTION: BtrfsReflinkStore ctor = ${c.parameterTypes.joinToString(", ") { it.simpleName }}")
    }

    val fsType = JvmFilesystemTypeProbe.typeOf(casRoot)
    val fsSource = JvmFilesystemTypeProbe.sourceOf(casRoot)
    say("PRE-WRITE BTRFS GUARD probe: casRoot='$casRoot' fstype=${fsType ?: "<undeterminable>"} source=${fsSource ?: "<undeterminable>"}")

    val fileOps = JvmFileOperations()
    val processOps = JvmProcessOperations()
    val refCounter = InMemoryReferenceCounter()

    val store = try {
        BtrfsReflinkStore(
            rootDir = casRoot,
            fileOps = fileOps,
            processOps = processOps,
            refCounter = refCounter,
            fsProbe = JvmFilesystemTypeProbe,
        )
    } catch (e: IllegalStateException) {
        say("REFUSED AT CONSTRUCTION — the pre-write btrfs guard fired:")
        println("--- refusal message ---")
        println(e.message)
        println("--- stack frames ---")
        e.stackTrace.take(6).forEach { println("    at $it") }
        System.out.flush()
        say("NEGATIVE RUN OUTCOME: harness FAILED LOUDLY, exit code 3, no CAS root manufactured")
        exitProcess(3)
    }

    if (mode == "negative") {
        say("NEGATIVE RUN FAILURE: the store was CONSTRUCTED with the volume unmounted. " +
            "The guard did not fire. This is the Fail clause of VAL-BTRFS-002.")
        exitProcess(4)
    }

    val casRootPath = Paths.get(casRoot)

    // ── 1. write the corpus through the store ────────────────────────────────
    say("--- PHASE 1: writing $blobs blobs through BtrfsReflinkStore.put() ---")
    val data = corpus(blobs)
    val cids = ArrayList<ContentId>(blobs)
    var totalBytes = 0L
    for ((idx, b) in data.withIndex()) {
        val cid = store.put(b)
        cids.add(cid)
        totalBytes += b.size
        val p = Paths.get(store.pathFor(cid))
        say("put[%02d] size=%-8d cid=%s -> %s (exists=%s onDiskSize=%d ino=%s)".format(
            idx, b.size, cid.value, p, Files.exists(p), Files.size(p), inodeOf(p)))
    }
    say("PHASE 1 done: $blobs blobs, $totalBytes bytes total")
    val bigOnes = data.indices.filter { data[it].size >= (1 shl 20) }
    say("blobs >= 1 MiB (non-inline, for VAL-BTRFS-003): " +
        bigOnes.joinToString(", ") { "idx=$it size=${data[it].size} cid=${cids[it].value}" })

    // ── 2. sharding fanout ───────────────────────────────────────────────────
    say("--- PHASE 2: sharding fanout ---")
    val sha = casRootPath.resolve("sha256")
    val shards = Files.newDirectoryStream(sha).use { it.filter { p -> Files.isDirectory(p) }.map { p -> p.fileName.toString() }.sorted() }
    say("shard directories under $sha : count=${shards.size}")
    say("shard names: ${shards.joinToString(" ")}")
    val set = blobSet(casRootPath)
    say("BLOB SET per the counting rule <casRoot>/sha256/<2hex>/<62hex> : ${set.size} files")
    set.take(8).forEach { say("  blob-set sample: $it") }

    // ── 3. byte-equality on retrieval ────────────────────────────────────────
    say("--- PHASE 3: byte-equality for retrieved blobs ---")
    for (idx in listOf(0, blobs / 2, blobs - 1, blobs - 3)) {
        val cid = cids[idx]
        val got = store.get(cid) ?: error("get returned null for $cid")
        val ok = got.contentEquals(data[idx])
        say("get[%02d] cid=%s bytes=%d contentEquals=%s sha256(retrieved)=%s match=%s".format(
            idx, cid.value, got.size, ok, sha256Hex(got), sha256Hex(got) == cid.hex))
        if (!ok || sha256Hex(got) != cid.hex) error("byte-equality FAILED for $cid")
    }

    // ── 4. dedup re-put ──────────────────────────────────────────────────────
    say("--- PHASE 4: dedup re-put (identical bytes must not create a second file) ---")
    val dupIdx = blobs - 1
    val dupCid = cids[dupIdx]
    val dupPath = Paths.get(store.pathFor(dupCid))
    val before = blobSet(casRootPath).size
    say("before re-put: blobSetSize=$before path=$dupPath ino=${inodeOf(dupPath)} size=${Files.size(dupPath)} mtime=${Files.getLastModifiedTime(dupPath)}")
    val again = store.put(data[dupIdx])
    val after = blobSet(casRootPath).size
    say("after  re-put: cid=${again.value} sameCid=${again == dupCid} blobSetSize=$after ino=${inodeOf(dupPath)} size=${Files.size(dupPath)} mtime=${Files.getLastModifiedTime(dupPath)}")
    say("DEDUP: blobSetSize unchanged=${before == after} (no second file written)")
    say("REFCOUNT NOTE (decision D7): refCounter.getCount(${dupCid.value})=${refCounter.getCount(dupCid)} — " +
        "PROCESS-LOCAL, InMemoryReferenceCounter only, dies with this JVM. NOT dedup evidence, NOT durability evidence.")

    // ── 5. planted divergence: collision + integrity exceptions ──────────────
    say("--- PHASE 5: PLANTED DIVERGENCE (a real sha256 collision is infeasible) ---")
    val plantedOriginal = "trikeshed-m5-planted-divergence-original-payload\n".repeat(64).encodeToByteArray()
    val plantedCid = ContentId.of(plantedOriginal)
    val plantedPath = store.pathFor(plantedCid)
    val divergent = "trikeshed-m5-planted-divergence-DIVERGENT-bytes\n".repeat(64).encodeToByteArray()
    say("PLANTED-DIVERGENCE CID = ${plantedCid.value}")
    say("PLANTED-DIVERGENCE PATH = $plantedPath")
    say("planting: writing ${divergent.size} DIVERGENT bytes (sha256=${sha256Hex(divergent)}) directly at that path, bypassing put()")
    val plantedShard = Paths.get(plantedPath).parent
    Files.createDirectories(plantedShard)
    fileOps.write(plantedPath, divergent)
    say("planted. file sha256 on disk = ${sha256Hex(Files.readAllBytes(Paths.get(plantedPath)))}")

    var collisionSeen = false
    try {
        store.put(plantedOriginal)
        say("PHASE 5 FAILURE: put() did NOT throw on the planted divergence")
    } catch (e: IllegalStateException) {
        collisionSeen = true
        say("CAUGHT (put) ${e.javaClass.name}: ${e.message}")
        e.stackTrace.take(5).forEach { println("    at $it") }
        System.out.flush()
    }

    var integritySeen = false
    try {
        store.get(plantedCid)
        say("PHASE 5 FAILURE: get() did NOT throw on the planted divergence")
    } catch (e: IllegalStateException) {
        integritySeen = true
        say("CAUGHT (get) ${e.javaClass.name}: ${e.message}")
        e.stackTrace.take(5).forEach { println("    at $it") }
        System.out.flush()
    }
    say("planted-divergence exceptions: collision=$collisionSeen integrity=$integritySeen")

    // END STATE: RESTORE the path to the correct bytes for that cid and re-verify.
    say("RESTORING the planted path to the correct bytes for ${plantedCid.value}")
    fileOps.writeAtomically(plantedPath, plantedOriginal)
    val restoredHex = sha256Hex(Files.readAllBytes(Paths.get(plantedPath)))
    say("RESTORED: sha256(file)=$restoredHex  expected=${plantedCid.hex}  match=${restoredHex == plantedCid.hex}")
    val restoredGet = store.get(plantedCid)
    say("RESTORED: store.get() returns ${restoredGet?.size} bytes, contentEquals=${restoredGet?.contentEquals(plantedOriginal)}")
    say("PLANTED-DIVERGENCE END STATE = RESTORED-AND-RE-VERIFIED. Named exclusion list is EMPTY.")

    // ── 6. reflinkCopy — the store primitive VAL-BTRFS-003 measures ──────────
    if (scratch != null) {
        say("--- PHASE 6: store.reflinkCopy() into the scratch plane ---")
        val srcIdx = blobs - 1 // the 4 MiB blob
        val srcCid = cids[srcIdx]
        say("scratch plane = $scratch  (plain directory on the same btrfs mount; NOT a subvolume, NOT inside the CAS tree)")
        say("scratch fstype=${JvmFilesystemTypeProbe.typeOf(scratch!!)} source=${JvmFilesystemTypeProbe.sourceOf(scratch!!)}")
        val srcPath = store.pathFor(srcCid)
        say("REFLINK SOURCE (the CAS blob, written by store.put): cid=${srcCid.value} size=${data[srcIdx].size} path=$srcPath")
        // Two clones: one stays pristine for the sharing proof, one is the dd-divergence
        // subject. Both land in the scratch plane; neither is inside the CAS tree.
        for (dst in listOf("$scratch/clone-4MiB.bin", "$scratch/diverge-4MiB.bin")) {
            val ok = runBlocking { store.reflinkCopy(srcCid, dst) }
            say("store.reflinkCopy(${srcCid.value}, $dst) = $ok  err=${store.lastReflinkError ?: "<none>"}")
            if (!ok) exitProcess(5)
            say("  clone sha256=${sha256Hex(Files.readAllBytes(Paths.get(dst)))} size=${Files.size(Paths.get(dst))}")
        }
        val srcHex = sha256Hex(Files.readAllBytes(Paths.get(srcPath)))
        say("CAS source re-hash: $srcHex == ${srcCid.hex} -> ${srcHex == srcCid.hex}")
    }

    // ── 7. layout compatibility with FileCasStore ────────────────────────────
    say("--- PHASE 7: LAYOUT COMPATIBILITY with FileCasStore (Sha2CasBus.kt getShardedPath) ---")
    // (a) the strongest form: point a stock FileCasStore at the SAME casRoot and have it
    //     READ blobs this store wrote. If the layouts differed by one byte this returns null.
    val interop = FileCasStore(fileOps, casRoot)
    for (idx in listOf(0, blobs / 2, blobs - 1)) {
        val cid = cids[idx]
        val got = interop.get(cid)
        say("FileCasStore(casRoot).get(${cid.value}) -> ${got?.size ?: -1} bytes, contentEquals=${got?.contentEquals(data[idx])}")
        if (got == null || !got.contentEquals(data[idx])) error("layout INCOMPATIBLE: FileCasStore cannot read $cid")
    }
    // (b) relative-path string equality: let FileCasStore lay out the same three blobs under
    //     an independent root and compare the paths it PRODUCED, byte for byte.
    val layoutRoot = "/tmp/m5-layout-check-filecas"
    Files.createDirectories(Paths.get(layoutRoot))
    val layoutStore = FileCasStore(fileOps, layoutRoot)
    val layoutIdx = listOf(0, blobs / 2, blobs - 1)
    layoutIdx.forEach { layoutStore.put(data[it]) }
    val fileCasRel = Files.walk(Paths.get(layoutRoot)).use { s ->
        s.filter { Files.isRegularFile(it) }.map { Paths.get(layoutRoot).relativize(it).toString() }.sorted().toList()
    }
    val btrfsRel = layoutIdx.map { Paths.get(casRoot).relativize(Paths.get(store.pathFor(cids[it]))).toString() }.sorted()
    say("FileCasStore produced   : $fileCasRel")
    say("BtrfsReflinkStore uses  : $btrfsRel")
    say("LAYOUT BYTE-IDENTICAL   : ${fileCasRel == btrfsRel}")
    if (fileCasRel != btrfsRel) error("layout diverged from FileCasStore.getShardedPath")

    // ── 8. lane-end hygiene ──────────────────────────────────────────────────
    say("--- PHASE 8: lane-end hygiene ---")
    val residue = tmpResidue(casRootPath)
    say("writeAtomically temp residue under $casRoot : ${residue.size} file(s) ${residue.joinToString(" ")}")
    val finalSet = blobSet(casRootPath)
    say("FINAL BLOB SET (counting rule) = ${finalSet.size} files")
    val allFiles = Files.walk(casRootPath).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
    say("all regular files under casRoot = ${allFiles.size} (must equal the blob set: ${allFiles.size == finalSet.size})")
    if (residue.isNotEmpty()) exitProcess(6)
    say("HARNESS COMPLETE — exit 0")
}
