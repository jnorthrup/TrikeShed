package borg.trikeshed.btrfs

import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations

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
 * [ofMemory] keeps the previous behaviour exactly, including its isolation model: a **fresh**
 * backing store per guest, so a memory world is collected with the guest that owned it rather than
 * accumulating on the host for as long as the hypervisor lives. Only the file-based store shares a
 * backing filesystem between guests, because only there is the sharing the point.
 *
 * ## Why several live mounts on one file root are safe, and what would break it
 * [UserspaceBtrfs] documents itself as "one live instance is one mount", and [ofFiles] hands each
 * guest its own instance over a shared [root]. That holds because the guests touch disjoint state:
 * each writes only its own `<id>.manifest`, and extents are content-addressed and append-only, so
 * concurrent writers either write different files or write identical bytes to the same name.
 *
 * The one operation that would break it is [UserspaceBtrfs.deleteSubvolume], whose mark-and-sweep
 * only sees the subvolumes *its own* instance loaded — a sweep from one guest's mount would reclaim
 * extents another guest's mount still references. Nothing on the VM path calls it (the guest VFS
 * creates and snapshots, never deletes), and reaping a guest world must therefore go through a
 * single owning mount rather than the guest's own.
 */
class BtrfsWorldStore private constructor(
    private val backingFor: (guestId: String) -> FileOperations,
    val root: String,
    /** False for [ofMemory]: callers that want durability can assert on it rather than assume. */
    val durable: Boolean,
) {
    /** The backing store for one guest — shared for [ofFiles], private for [ofMemory]. */
    fun fileOpsFor(guestId: String): FileOperations = backingFor(guestId)

    /** The subvolume a guest owns. Guest ids carry no path separators, so they are valid names. */
    fun subvolumeFor(guestId: String): String = guestId

    override fun toString(): String = "BtrfsWorldStore(root=$root, durable=$durable)"

    companion object {
        /** The historical behaviour, named: a world that lives and dies with its guest. */
        fun ofMemory(): BtrfsWorldStore =
            BtrfsWorldStore({ InMemoryFileOperations(cwd = "/") }, MEMORY_ROOT, durable = false)

        /** File-based: guest worlds survive the daemon that spawned them, on one shared filesystem. */
        fun ofFiles(fileOps: FileOperations, root: String): BtrfsWorldStore =
            BtrfsWorldStore({ fileOps }, root, durable = true)

        const val MEMORY_ROOT = "/trikeshed-graal-btrfs"

        /** The daemon's on-disk home for guest worlds, under the forge home it is given. */
        fun homeUnder(forgeHome: String): String = "$forgeHome/vm-worlds"
    }
}
