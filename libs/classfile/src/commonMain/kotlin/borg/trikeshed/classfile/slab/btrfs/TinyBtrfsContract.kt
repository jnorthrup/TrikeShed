package borg.trikeshed.classfile.slab.btrfs

import borg.trikeshed.classfile.slab.*
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j

/**
 * tinybtrfs: btrfs ioctl surface as pure Cursor transforms.
 * No 3rd party deps — direct ioctl mapping via JNR/JNI stubs.
 * Each seam = a Cursor → Cursor projection preserving facet metadata.
 */

// ==================== IOCTL COMMAND CODES (x86-64 Linux) ====================
// Ioctl encoding: _IOW/_IOR = (magic<<0) | (size<<16) | (dir<<8) | nr
// Computed from Linux kernel btrfs.h constants
object BtrfsIoctl {
    const val MAGIC = 0x94
    const val SUBVOL_CREATE_V2 = 0x94000001
    const val SNAP_CREATE_V2 = 0x94000002
    const val SNAP_DESTROY_V2 = 0x94000003
    const val CLONE_RANGE = 0x94000009
    const val FILE_EXTENT_SAME = 0x9400000E
    const val START_SYNC = 0x94000018
    const val WAIT_SYNC = 0x94000019
    const val QGROUP_LIMIT = 0x94000024
    const val QGROUP_ASSIGN = 0x94000025
    const val GET_SUBVOL_INFO = 0x94000030
    const val SUBVOL_GETFLAGS = 0x94000031
    const val SUBVOL_SETFLAGS = 0x94000032
    const val DEFRAG_RANGE = 0x94000033
    const val SEND = 0x94000038
    const val RECEIVE = 0x94000039
    const val FIDEDUPERANGE = 0x9400003C
}

// ==================== DATA STRUCTS (packed, 24B aligned) ====================
inline  class BtrfsFd(private val value: Int) {
    companion object { operator fun invoke(fd: Int) = BtrfsFd(fd) }
}

inline  class SubvolId(private val value: Long) {
    companion object { operator fun invoke(id: Long) = SubvolId(id) }
}

data class VolArgsV2(
    val fd: BtrfsFd,
    val transid: Long = 0,
    val flags: Long = 0,
    val size: Long = 0,
    val qgroupInherit: Long = 0,
    val name: String = "",
    val devid: Long = 0,
    val subvolid: SubvolId = SubvolId(0)
)

data class CloneRangeArgs(
    val srcFd: BtrfsFd,
    val srcOffset: Long,
    val srcLength: Long,
    val destOffset: Long
)

data class SameExtentArgs(
    val fdCount: Int,
    val infos: Array<SameExtentInfo>
) {
    data class SameExtentInfo(val fd: BtrfsFd, val offset: Long, val length: Long)
}

data class QGroupLimitArgs(
    val qgroupid: Long,
    val limFlags: Long,
    val maxRefer: Long,
    val maxExcl: Long,
    val rsvRefer: Long,
    val rsvExcl: Long
)

data class QGroupAssignArgs(
    val assign: Int,  // 1=assign, 0=remove
    val src: Long,
    val dst: Long
)

data class SubvolInfoArgs(
    val treeid: Long,
    val name: String = "",
    val parentId: Long = 0,
    val dirid: Long = 0,
    val generation: Long = 0,
    val ctransid: Long = 0,
    val otransid: Long = 0,
    val stransid: Long = 0,
    val rtransid: Long = 0,
    val flags: Long = 0,
    val uuid: ByteArray = ByteArray(16),
    val parentUuid: ByteArray = ByteArray(16),
    val receivedUuid: ByteArray = ByteArray(16)
)

data class DefragRangeArgs(
    val start: Long,
    val len: Long,
    val compressType: Int = 0,
    val extentThresh: Int = 0
)

data class SendArgs(
    val sendFd: BtrfsFd,
    val cloneSourcesCount: Long,
    val cloneSources: Long,
    val parentRoot: Long,
    val flags: Long
)

data class ReceiveArgs(
    val fd: BtrfsFd,
    val flags: Long
)

/** 24B wireproto alignment for FieldSynapse compatibility */
inline  class Aligned24<T>(val value: T)

// ==================== CURSOR TRANSFORMS (pure projections) ====================

/** Open btrfs filesystem → SlabCursor of all subvolumes */
fun openFs(devicePath: String): SlabCursor = TODO("ioctl BTRFS_IOC_FS_INFO → SlabCursor")

/** Create subvolume → new SlabExtent with IMMUTABLE facet if read-only */
fun createSubvol(parentFd: BtrfsFd, name: String, readOnly: Boolean = false): SlabExtent = TODO(
    "ioctl SUBVOL_CREATE_V2 → SlabExtent{offset=subvolid, length=0, facet=IMMUTABLE.or(if(readOnly) READ_ONLY else NONE)}"
)

/** Create snapshot (instant, COW) → SlabExtent with SNAPSHOT_ANCHOR facet */
fun createSnapshot(sourceFd: BtrfsFd, destFd: BtrfsFd, name: String, readOnly: Boolean = true): SlabExtent = TODO(
    "ioctl SNAP_CREATE_V2 → SlabExtent{offset=new_subvolid, length=0, facet=SNAPSHOT_ANCHOR.or(IMMUTABLE)}"
)

/** Destroy subvolume/snapshot → filter Cursor, remove extent */
fun destroySubvol(fd: BtrfsFd, subvolId: SubvolId): SlabCursor = TODO(
    "ioctl SNAP_DESTROY_V2 → filter SlabCursor { it.offset != subvolId.value }"
)

/** Clone range (dedup) → merge extents, add DEDUP_CANDIDATE facet */
fun cloneRange(destFd: BtrfsFd, srcFd: BtrfsFd, srcOffset: Long, length: Long, destOffset: Long): SlabExtent = TODO(
    "ioctl CLONE_RANGE → SlabExtent{offset=destOffset, length, facet=DEDUP_CANDIDATE.or(COMPRESSED_ZSTD)}"
)

/** Deduplicate extents (kernel verifies identity) → merge facets */
fun dedupExtents(args: SameExtentArgs): Series<SlabExtent> = TODO(
    "ioctl FILE_EXTENT_SAME → map each extent: facet = facet.or(DEDUP_CANDIDATE).or(if(verified) COMPRESSED_ZSTD else NONE)"
)

/** Explicit sync start → return transid for WAIT_SYNC */
fun startSync(fd: BtrfsFd): Long = TODO("ioctl START_SYNC → transid")

/** Wait for sync → barrier for durability */
fun waitSync(fd: BtrfsFd, transid: Long): Unit = TODO("ioctl WAIT_SYNC(transid)")

/** Quota limit → attach to SlabExtent facet for budget enforcement */
fun setQGroupLimit(fd: BtrfsFd, qgroupId: Long, maxBytes: Long): Unit = TODO("ioctl QGROUP_LIMIT")

/** Assign subvol to qgroup → inherit budget facet */
fun assignQGroup(fd: BtrfsFd, src: Long, dst: Long): Unit = TODO("ioctl QGROUP_ASSIGN")

/** Get subvol info → project to SlabExtent with metadata */
fun getSubvolInfo(fd: BtrfsFd, subvolId: SubvolId): SlabExtent = TODO(
    "ioctl GET_SUBVOL_INFO → SlabExtent{offset=treeid, length=generation, facet=fromFlags(flags)}"
)

/** Set subvol flags (read-only, etc.) → mutate facet */
fun setSubvolFlags(fd: BtrfsFd, flags: Long): Unit = TODO("ioctl SUBVOL_SETFLAGS")

/** Defragment range → rewrite extents, may change physical layout */
fun defragRange(fd: BtrfsFd, start: Long, len: Long): SlabCursor = TODO(
    "ioctl DEFRAG_RANGE → remap SlabCursor extents (offsets may shift)"
)

/** Send stream (incremental with -p parent) → Series<Byte> for replication */
fun sendStream(fd: BtrfsFd, parentRoot: Long = 0, flags: Long = 0): Series<Byte> = TODO(
    "ioctl SEND → byte stream for btrfs receive"
)

/** Receive stream → reconstruct SlabCursor on target fs */
fun receiveStream(fd: BtrfsFd, stream: Series<Byte>): SlabCursor = TODO(
    "ioctl RECEIVE → SlabCursor of received subvolumes"
)

/** Fiemap → extent map for tiering decisions */
fun fiemap(fd: BtrfsFd, start: Long, length: Long): Series<Join<Long, Long>> = TODO(
    "ioctl FIEMAP → Series<Join<offset, length>> for Parquet row group alignment"
)

// ==================== FACET DERIVATION ====================
fun facetFromFlags(flags: Long): SlabFacet {
    var f = SlabFacetFlag.NONE.facet
    if ((flags and 1L) != 0L) f = f or SlabFacetFlag.IMMUTABLE.facet  // BTRFS_SUBVOL_RDONLY
    if ((flags and (1L shl 1)) != 0L) f = f or SlabFacetFlag.COMPRESSED_ZSTD.facet
    return f
}
