package borg.trikeshed.btrfs

import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
import borg.trikeshed.collections.associative.LinearHashMap
import borg.trikeshed.job.CasStore
import borg.trikeshed.util.oroboros.FileCasStore

/**
 * Where a guest VM's btrfs world lives.
 *
 * A guest world is a [UserspaceBtrfs] subvolume. Until this existed the only thing constructing one
 * was `TrikeShedGraalVfs`'s default argument — an [InMemoryFileOperations] — so every "btrfs-hosted"
 * VM was really RAM-hosted: the world evaporated with the process, `snapshot` produced something
 * that could never be read back, and [UserspaceBtrfs]'s durability (manifests replay, extents on
 * the backing store) was carried but never used.
 *
 * [ofFiles] is the file-based store: one btrfs root on a real filesystem, one **subvolume per
 * guest**. That is the layout btrfs itself has — one filesystem, many subvolumes — and it is what
 * makes the extent store shared: two guests holding the same jar hold one copy of its bytes,
 * because extents are keyed by content hash. Isolation is by subvolume, which
 * `TrikeShedGraalVfs` never leaves.
 *
 * [cas] is shared across guests and may be the same CAS the host exposes through
 * Couch. Manifests and file bytes keep one content identity across guest mounts,
 * snapshots, and publication. [ofMemory] keeps a stable private metadata backing
 * per guest for this world's lifetime; a newly constructed memory world is empty.
 *
 * ## Why several live mounts on one file root are safe, and what would break it
 * [UserspaceBtrfs] documents itself as "one live instance is one mount", and [ofFiles] hands each
 * guest its own instance over a shared [root]. That holds because the guests touch disjoint state:
 * each writes only its own `<id>.manifest`, and extents are content-addressed and append-only, so
 * concurrent writers either write different files or write identical bytes to the same name.
 *
 * Guest mounts borrow [cas], so deleting a subvolume never sweeps shared CAS
 * objects using only that guest's references. Shared-object reclamation belongs
 * to the owning store, with the complete set of roots.
 */
class BtrfsWorldStore private constructor(
    private val backingFor: (guestId: String) -> FileOperations,
    val root: String,
    /** False for [ofMemory]: callers that want durability can assert on it rather than assume. */
    val durable: Boolean,
    val cas: CasStore,
) {
    /** The backing store for one guest — shared for [ofFiles], private for [ofMemory]. */
    fun fileOpsFor(guestId: String): FileOperations = backingFor(guestId)

    /** The subvolume a guest owns. Guest ids carry no path separators, so they are valid names. */
    fun subvolumeFor(guestId: String): String = guestId

    /** A fresh view of committed guest metadata using this world's shared CAS. */
    fun mount(guestId: String): UserspaceBtrfs = UserspaceBtrfs(root, fileOpsFor(guestId), cas)

    override fun toString(): String = "BtrfsWorldStore(root=$root, durable=$durable)"

    companion object {
        /** Stable per-guest metadata and shared blobs, all owned by this memory world. */
        fun ofMemory(cas: CasStore = CasStore.inMemory()): BtrfsWorldStore {
            val guests = LinearHashMap<String, FileOperations>()
            return BtrfsWorldStore({ guest ->
                guests[guest] ?: InMemoryFileOperations(cwd = "/").also { guests[guest] = it }
            }, MEMORY_ROOT, durable = false, cas = cas)
        }

        /** File-based: guest worlds survive the daemon that spawned them, on one shared filesystem. */
        fun ofFiles(fileOps: FileOperations, root: String, cas: CasStore? = null): BtrfsWorldStore =
            BtrfsWorldStore({ fileOps }, root, durable = true,
                cas = cas ?: FileCasStore(fileOps, fileOps.resolvePath(root, "extents")))

        const val MEMORY_ROOT = "/trikeshed-graal-btrfs"

        /** The daemon's on-disk home for guest worlds, under the forge home it is given. */
        fun homeUnder(forgeHome: String): String = "$forgeHome/vm-worlds"
    }
}
