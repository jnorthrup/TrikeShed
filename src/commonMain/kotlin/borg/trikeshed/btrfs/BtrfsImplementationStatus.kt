package borg.trikeshed.btrfs

enum class BtrfsBlockGroupProfile(
    val bits: UByte,
    val minStripes: Int,
) {
    SINGLE(CHUNK_SINGLE, 1),
    DUP(CHUNK_DUP, 2),
    RAID0(CHUNK_RAID0, 2),
    RAID1(CHUNK_RAID1, 2),
    RAID10(CHUNK_RAID10, 4),
    RAID5(CHUNK_RAID5, 2),
    RAID6(CHUNK_RAID6, 3);

    fun validate(stripes: List<BtrfsStripe>) {
        require(stripes.size >= minStripes) { "$name needs >= $minStripes stripes" }
        when (this) {
            SINGLE -> require(stripes.size == 1) { "SINGLE needs exactly one stripe" }
            DUP -> require(stripes.size == 2 && stripes[0].devid == stripes[1].devid) {
                "DUP needs two stripes on one device"
            }
            RAID10 -> require(stripes.size % 2 == 0) { "RAID10 needs an even stripe count" }
            else -> Unit
        }
    }

    companion object {
        fun fromBits(bits: UByte): BtrfsBlockGroupProfile =
            entries.firstOrNull { it.bits == bits }
                ?: throw IllegalArgumentException("unknown block-group profile bits 0x${bits.toString(16)}")
    }
}

enum class BtrfsOracleKind {
    BTRFS_PROGS_OFFLINE,
    KERNEL_IOCTL_REFERENCE,
    LOCAL_INVARIANT,
}

enum class BtrfsSupportStatus {
    IMPLEMENTED,
    SKELETON,
    MISSING,
}

enum class BtrfsCapability {
    URING_FILE_VOLUME,
    MKFS_IMAGE,
    CHECK_READ_ONLY,
    SUPERBLOCK_READ,
    ROOT_TREE_READ,
    CHUNK_TREE_READ,
    DEVICE_TREE_READ,
    SUBVOLUME_CREATE,
    SNAPSHOT_CREATE,
    SEND_RECEIVE,
    REFLINK,
    DEDUPE,
    DEFRAG,
    BALANCE,
    RAID_PROFILE_LAYOUT,
}

data class BtrfsCapabilityStatus(
    val capability: BtrfsCapability,
    val status: BtrfsSupportStatus,
    val oracle: BtrfsOracleKind,
    val evidence: String,
)

object BtrfsImplementationStatus {
    val matrix: List<BtrfsCapabilityStatus> = listOf(
        BtrfsCapabilityStatus(
            BtrfsCapability.URING_FILE_VOLUME,
            BtrfsSupportStatus.IMPLEMENTED,
            BtrfsOracleKind.LOCAL_INVARIANT,
            "BtrfsUringFileVolume implements userspace.nio.Volume over a channel-opened image fd and routes open, resize, block reads, writes, fsync, and close through the common uring channel.",
        ),
        BtrfsCapabilityStatus(
            BtrfsCapability.SUPERBLOCK_READ,
            BtrfsSupportStatus.IMPLEMENTED,
            BtrfsOracleKind.LOCAL_INVARIANT,
            "BtrfsSuperblock parses the 64KiB primary-superblock buffer shape used by the current skeleton writer.",
        ),
        BtrfsCapabilityStatus(
            BtrfsCapability.RAID_PROFILE_LAYOUT,
            BtrfsSupportStatus.IMPLEMENTED,
            BtrfsOracleKind.LOCAL_INVARIANT,
            "BtrfsBlockGroupProfile validates supported block-group profile bits and stripe counts.",
        ),
        BtrfsCapabilityStatus(
            BtrfsCapability.MKFS_IMAGE,
            BtrfsSupportStatus.SKELETON,
            BtrfsOracleKind.BTRFS_PROGS_OFFLINE,
            "BtrfsImageIo writes superblock slots through the common uring volume and verifies local readback, but does not yet emit complete root, chunk, device, extent, checksum, or fs trees.",
        ),
        BtrfsCapabilityStatus(
            BtrfsCapability.CHECK_READ_ONLY,
            BtrfsSupportStatus.SKELETON,
            BtrfsOracleKind.BTRFS_PROGS_OFFLINE,
            "BtrfsProgsOracle can run btrfs check --readonly when a JVM-visible btrfs executable is configured or found; missing btrfs-progs remains a concrete blocker.",
        ),
        BtrfsCapabilityStatus(
            BtrfsCapability.ROOT_TREE_READ,
            BtrfsSupportStatus.MISSING,
            BtrfsOracleKind.BTRFS_PROGS_OFFLINE,
            "The current reader does not traverse canonical Btrfs root-tree items.",
        ),
        BtrfsCapabilityStatus(
            BtrfsCapability.CHUNK_TREE_READ,
            BtrfsSupportStatus.SKELETON,
            BtrfsOracleKind.BTRFS_PROGS_OFFLINE,
            "BtrfsChunkTree parses local fixture bytes; upstream node/header/item validation is not complete.",
        ),
        BtrfsCapabilityStatus(
            BtrfsCapability.DEVICE_TREE_READ,
            BtrfsSupportStatus.SKELETON,
            BtrfsOracleKind.BTRFS_PROGS_OFFLINE,
            "Device and stripe value types exist; multi-device fs UUID/device-tree replay is not implemented.",
        ),
        BtrfsCapabilityStatus(
            BtrfsCapability.SUBVOLUME_CREATE,
            BtrfsSupportStatus.SKELETON,
            BtrfsOracleKind.KERNEL_IOCTL_REFERENCE,
            "BtrfsUserspaceVolume creates local directory-backed subvolumes, not canonical Btrfs root refs.",
        ),
        BtrfsCapabilityStatus(
            BtrfsCapability.SNAPSHOT_CREATE,
            BtrfsSupportStatus.SKELETON,
            BtrfsOracleKind.KERNEL_IOCTL_REFERENCE,
            "Snapshots are currently file copies marked immutable, not shared Btrfs extents/root refs.",
        ),
        BtrfsCapabilityStatus(
            BtrfsCapability.SEND_RECEIVE,
            BtrfsSupportStatus.SKELETON,
            BtrfsOracleKind.KERNEL_IOCTL_REFERENCE,
            "Send/receive uses a TrikeShed stream envelope, not the Btrfs send-stream format.",
        ),
        BtrfsCapabilityStatus(
            BtrfsCapability.REFLINK,
            BtrfsSupportStatus.SKELETON,
            BtrfsOracleKind.KERNEL_IOCTL_REFERENCE,
            "BtrfsReflinkStore can model shared extents locally; FICLONE/FICLONERANGE parity is not implemented.",
        ),
        BtrfsCapabilityStatus(
            BtrfsCapability.DEDUPE,
            BtrfsSupportStatus.MISSING,
            BtrfsOracleKind.KERNEL_IOCTL_REFERENCE,
            "No userspace implementation of byte-equality range dedupe or ioctl reference harness exists yet.",
        ),
        BtrfsCapabilityStatus(
            BtrfsCapability.DEFRAG,
            BtrfsSupportStatus.MISSING,
            BtrfsOracleKind.KERNEL_IOCTL_REFERENCE,
            "No userspace extent relocation/rewrite operation exists yet.",
        ),
        BtrfsCapabilityStatus(
            BtrfsCapability.BALANCE,
            BtrfsSupportStatus.MISSING,
            BtrfsOracleKind.KERNEL_IOCTL_REFERENCE,
            "No userspace block-group relocation/profile-conversion operation exists yet.",
        ),
    )

    fun statusOf(capability: BtrfsCapability): BtrfsCapabilityStatus =
        matrix.first { it.capability == capability }

    fun offlineOracleRequired(): List<BtrfsCapabilityStatus> =
        matrix.filter { it.oracle == BtrfsOracleKind.BTRFS_PROGS_OFFLINE && it.status != BtrfsSupportStatus.IMPLEMENTED }
}
