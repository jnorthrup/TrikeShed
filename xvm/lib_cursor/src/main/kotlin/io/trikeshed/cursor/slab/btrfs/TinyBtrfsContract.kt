package io.trikeshed.cursor.slab.btrfs

import io.trikeshed.cursor.slab.*
import io.trikeshed.kernel.Series
import io.trikeshed.kernel.Join
import io.trikeshed.kernel.j
import io.trikeshed.kernel.α

/**
 * tinybtrfs: btrfs ioctl surface as pure Cursor transforms.
 * No 3rd party deps — direct ioctl mapping via JNR/JNI stubs.
 * Each seam = a Cursor → Cursor projection preserving facet metadata.
 */

// ==================== IOCTL COMMAND CODES ====================
object BtrfsIoctl {
    const val MAGIC = 0x94
    const val SUBVOL_CREATE_V2 = _IOW(MAGIC, 1, 0)      // struct btrfs_ioctl_vol_args_v2
    const val SNAP_CREATE_V2 = _IOW(MAGIC, 2, 0)        // struct btrfs_ioctl_vol_args_v2
    const val SNAP_DESTROY_V2 = _IOW(MAGIC, 3, 0)       // struct btrfs_ioctl_vol_args_v2
    const val CLONE_RANGE = _IOW(MAGIC, 9, 0)           // struct btrfs_ioctl_clone_range_args
    const val FILE_EXTENT_SAME = _IOW(MAGIC, 14, 0)     // struct btrfs_ioctl_same_extent_args
    const val START_SYNC = _IOR(MAGIC, 24, 0)           // u64* transid
    const val WAIT_SYNC = _IOW(MAGIC, 25, 0)            // u64* transid
    const val QGROUP_LIMIT = _IOW(MAGIC, 36, 0)         // struct btrfs_ioctl_qgroup_limit_args
    const val QGROUP_ASSIGN = _IOW(MAGIC, 37, 0)        // struct btrfs_ioctl_qgroup_assign_args
    const val GET_SUBVOL_INFO = _IOR(MAGIC, 48, 0)      // struct btrfs_ioctl_get_subvol_info_args
    const val SUBVOL_GETFLAGS = _IOR(MAGIC, 49, 0)      // u64*
    const val SUBVOL_SETFLAGS = _IOW(MAGIC, 50, 0)      // u64*
    const val DEFRAG_RANGE = _IOW(MAGIC, 51, 0)         // struct btrfs_ioctl_defrag_range_args
    const val SEND = _IOW(MAGIC, 56, 0)                 // struct btrfs_ioctl_send_args
    const val RECEIVE = _IOW(MAGIC, 57, 0)              // struct btrfs_ioctl_receive_args
    const val FIDEDUPERANGE = _IOW(MAGIC, 60, 0)        // struct fideduperange
}

// ==================== DATA STRUCTS (packed, 24B aligned) ====================
@JvmInline
value class BtrfsFd(private val value: Int) {
    companion object { operator fun invoke(fd: Int) = BtrfsFd(fd) }
}

@JvmInline
value class SubvolId(private val value: Long) {
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
    val infos: Array<SameExtentInfo>  // [fd, offset, length, ...]
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
@JvmInline
value class Aligned24<T>(val value: T)

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
    var f = SlabFacet.NONE
    if (flags and 1L != 0) f = f or SlabFacet.IMMUTABLE  // BTRFS_SUBVOL_RDONLY
    if (flags and (1L shl 1) != 0) f = f or SlabFacet.COMPRESSED_ZSTD
    return f
}