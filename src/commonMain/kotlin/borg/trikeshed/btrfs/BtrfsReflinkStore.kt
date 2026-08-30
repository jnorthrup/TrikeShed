package borg.trikeshed.btrfs

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.nio.channels.spi.ProcessOperations
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.reflink.ReferenceCounter

/**
 * Reports the filesystem type backing a path, so a store can refuse to write onto
 * the wrong filesystem BEFORE it manufactures a directory tree there.
 *
 * Implementations resolve the NEAREST EXISTING ANCESTOR of [path] — a CAS root that
 * does not exist yet still has to be judged, and it must be judged by the filesystem
 * that would receive the `mkdirs`.
 *
 * Returns a lowercase filesystem type name (`"btrfs"`, `"overlay"`, `"ext4"`, `"apfs"`, …)
 * or `null` when the type cannot be determined at all.
 */
fun interface FilesystemTypeProbe {
    fun typeOf(path: String): String?
}

/**
 * CAS store on a btrfs volume.
 *
 * Layout is BYTE-IDENTICAL to `FileCasStore.getShardedPath`
 * (`util/oroboros/Sha2CasBus.kt`): `<rootDir>/sha256/<hex[0:2]>/<hex[2:]>`, derived
 * from [ContentId.hex] and never from [ContentId.value] (which carries the
 * `"sha256:"` prefix — sharding on it would drop every blob into one `sh/`
 * directory with a colon in the file name).
 *
 * Writes are published atomically (temp + rename + fsync, via
 * [FileOperations.writeAtomically]) so a torn write can never appear at a cid path.
 *
 * WHAT THIS STORE DELIVERS (mission-002 decision D9):
 *  - whole-file extent sharing through [reflinkCopy] (`cp --reflink=always`);
 *  - content addressing with atomic publication and read-back verification.
 * WHAT IT DOES NOT DELIVER:
 *  - chunk-level deduplication on [put]. The `ReflinkScanner` scan whose result
 *    `put()` used to compute and throw away is REMOVED rather than implemented:
 *    code that computes an unused result lies about what the store does.
 *
 * [ReferenceCounter] bookkeeping is PROCESS-LOCAL (`InMemoryReferenceCounter` is the
 * only implementation) and dies with the JVM. It is neither dedup evidence nor
 * durability evidence; both rest on filesystem-level proof.
 */
class BtrfsReflinkStore(
    private val rootDir: String,
    private val fileOps: FileOperations,
    private val processOps: ProcessOperations,
    private val refCounter: ReferenceCounter,
    private val fsProbe: FilesystemTypeProbe,
) : CasStore() {

    init {
        // The btrfs guard runs BEFORE the mkdirs below. Without it this init block
        // would happily manufacture a CAS root on whatever filesystem happened to be
        // under `rootDir` (a container overlay, say) and every put() would "succeed"
        // while quietly writing somewhere else.
        requireBtrfsRoot("construct a store")
        if (!fileOps.exists(rootDir)) {
            fileOps.mkdirs(rootDir)
        }
    }

    /**
     * Refuse LOUDLY unless [rootDir] resolves onto a btrfs filesystem.
     * Called from `init` and again from every [put] before any byte is written, so a
     * volume that disappears mid-run cannot be written past.
     */
    private fun requireBtrfsRoot(action: String) {
        val fsType = fsProbe.typeOf(rootDir)
        if (fsType != BTRFS) {
            throw IllegalStateException(
                "BtrfsReflinkStore REFUSES to $action: casRoot '$rootDir' does not resolve onto a " +
                    "btrfs filesystem (detected fstype=" + (fsType ?: "<undeterminable>") + "). " +
                    "The btrfs volume is not mounted at that path. Refusing rather than creating a " +
                    "CAS root on the wrong filesystem."
            )
        }
    }

    /** `<rootDir>/sha256/<hex[0:2]>/<hex[2:]>` — identical to FileCasStore.getShardedPath. */
    private fun cidPath(cid: ContentId): String {
        val hex = cid.hex
        require(hex.length == 64) { "Invalid ContentId hex length: ${hex.length}" }
        return fileOps.resolvePath(rootDir, SHA256_DIR, hex.substring(0, 2), hex.substring(2))
    }

    /** The shard directory for [cid] — created only on the write path. */
    private fun shardDir(cid: ContentId): String =
        fileOps.resolvePath(rootDir, SHA256_DIR, cid.hex.substring(0, 2))

    override fun put(bytes: ByteArray): ContentId {
        requireBtrfsRoot("put")

        val cid = ContentId.of(bytes)
        val target = cidPath(cid)

        if (fileOps.exists(target)) {
            val existing = fileOps.readAllBytes(target)
            if (ContentId.of(existing) == cid) {
                // Dedup: the blob is already published at its cid path. No second file.
                refCounter.increment(cid)
                return cid
            }
            throw IllegalStateException("CAS collision: $cid already exists with different content")
        }

        val dir = shardDir(cid)
        if (!fileOps.exists(dir)) {
            fileOps.mkdirs(dir)
        }

        // Atomic publication: same-directory temp file, fsync, ATOMIC_MOVE, parent fsync.
        fileOps.writeAtomically(target, bytes)

        // Nothing is considered published until the exact bytes read back and re-hash.
        val reread = fileOps.readAllBytes(target)
        if (!reread.contentEquals(bytes) || ContentId.of(reread) != cid) {
            throw IllegalStateException("digest mismatch: failed to verify stored blob for CID $cid")
        }

        refCounter.increment(cid)
        return cid
    }

    /**
     * The btrfs primitive: clone [srcCid]'s extents to [dstPath] with `cp --reflink=always`.
     * A true return means `cp` exited 0; extent SHARING is a physical fact that must be
     * measured (`filefrag -v`, `btrfs filesystem du -s`), never inferred from this boolean.
     */
    suspend fun reflinkCopy(srcCid: ContentId, dstPath: String): Boolean {
        val srcPath = cidPath(srcCid)
        if (!fileOps.exists(srcPath)) return false

        return try {
            val result = processOps.exec(
                command = "cp",
                args = listOf("--reflink=always", srcPath, dstPath)
            )
            if (result.exitCode != 0) {
                lastReflinkError = result.stderr.decodeToString().trim()
            }
            result.exitCode == 0
        } catch (e: Exception) {
            lastReflinkError = e.toString()
            false
        }
    }

    /** stderr of the most recent failed [reflinkCopy], so a refusal is never silent. */
    var lastReflinkError: String? = null
        private set

    /** The path this store would use for [cid]; exposed so harnesses can prove the layout. */
    fun pathFor(cid: ContentId): String = cidPath(cid)

    override fun get(cid: ContentId): ByteArray? {
        val target = cidPath(cid)
        if (!fileOps.exists(target)) return null

        val bytes = fileOps.readAllBytes(target)
        if (ContentId.of(bytes) != cid) {
            throw IllegalStateException("CAS integrity failure: stored blob for $cid does not match hash")
        }
        return bytes
    }

    companion object {
        const val BTRFS: String = "btrfs"
        private const val SHA256_DIR: String = "sha256"
    }
}
