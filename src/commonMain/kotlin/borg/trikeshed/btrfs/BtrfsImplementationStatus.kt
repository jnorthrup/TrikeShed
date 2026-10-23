package borg.trikeshed.btrfs

enum class BtrfsBlockGroupProfile(
    val bits: ULong,
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
        fun fromBits(bits: ULong): BtrfsBlockGroupProfile =
            entries.firstOrNull { it.bits == bits }
                ?: throw IllegalArgumentException("unknown block-group profile bits 0x${bits.toString(16)}")
    }
}

enum class BtrfsOracleKind {
    BTRFS_PROGS_OFFLINE,
    KERNEL_IOCTL_REFERENCE,
    LOCAL_INVARIANT,
    MACOS_FUSE_RUNTIME,
}

enum class BtrfsSupportStatus {
    IMPLEMENTED,
    SKELETON,
    UNVERIFIED,
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
    RAID_VOLUME,
    SPANNED_VOLUME,
    RAID_RECOVERY,
    FILESYSTEM_ELEMENT,
    MOUNTED_VOLUME_GROWTH,
    USERSPACE_REBALANCE,
    METADATA_COMPRESSION,
    MACOS_FUSE_MOUNT,
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
            "BtrfsSuperblock validates native offsets, CRC32C, magic and geometry at the 64KiB primary-superblock address; native mkfs fixtures exercise the parser.",
        ),
        BtrfsCapabilityStatus(
            BtrfsCapability.RAID_PROFILE_LAYOUT,
            BtrfsSupportStatus.IMPLEMENTED,
            BtrfsOracleKind.LOCAL_INVARIANT,
            "BtrfsBlockGroupProfile validates supported block-group profile bits and stripe counts.",
        ),
        BtrfsCapabilityStatus(
            BtrfsCapability.RAID_VOLUME,
            BtrfsSupportStatus.IMPLEMENTED,
            BtrfsOracleKind.LOCAL_INVARIANT,
            "BtrfsRaidVolume composes SINGLE, DUP, RAID0/1/10/5/6 block IO through bounded member channels; BtrfsRaidImage owns uring image members. This is userspace block storage, not canonical Btrfs filesystem serialization. Same-host files do not establish physical failure isolation.",
        ),
        BtrfsCapabilityStatus(
            BtrfsCapability.SPANNED_VOLUME,
            BtrfsSupportStatus.IMPLEMENTED,
            BtrfsOracleKind.LOCAL_INVARIANT,
            "SPAN concatenates unequal member data capacities, including reads and writes across member boundaries. BtrfsRaidImage persists geometry and ordered member identities; SPAN cannot recover an erased member.",
        ),
        BtrfsCapabilityStatus(
            BtrfsCapability.RAID_RECOVERY,
            BtrfsSupportStatus.IMPLEMENTED,
            BtrfsOracleKind.LOCAL_INVARIANT,
            "Mirrors and P/Q parity recover known erasures within layout redundancy. Replacement is unavailable until rebuild and sync complete. Checksummed dual slots retain ordered identities, unavailable state and complete block after-images for redo. Atomicity covers one physical stripe block or replicated logical block, not an entire multi-block write. Bare BtrfsRaidVolume requires caller-supplied persistence. Parity cannot identify arbitrary silent corruption; power-loss durability requires the backend's write and fsync guarantees.",
        ),
        BtrfsCapabilityStatus(
            BtrfsCapability.MKFS_IMAGE,
            BtrfsSupportStatus.IMPLEMENTED,
            BtrfsOracleKind.BTRFS_PROGS_OFFLINE,
            "BtrfsFilesystemImage emits native single-device CRC32C images with root, chunk, device, extent, checksum and filesystem trees. Regular files/directories and internal tree nodes are checked by native btrfs check with data checksums and byte-exact restore in BtrfsFilesystemOracleTest; see docs/btrfs-userspace.md for supported bounds.",
        ),
        BtrfsCapabilityStatus(
            BtrfsCapability.CHECK_READ_ONLY,
            BtrfsSupportStatus.IMPLEMENTED,
            BtrfsOracleKind.BTRFS_PROGS_OFFLINE,
            "BtrfsProgsOracle runs external btrfs check --readonly with bounded execution and explicit unavailable, rejected and launch-failure outcomes. Native acceptance is exercised in BtrfsFilesystemOracleTest; this is not an internal implementation of every btrfs check mode.",
        ),
        BtrfsCapabilityStatus(
            BtrfsCapability.ROOT_TREE_READ,
            BtrfsSupportStatus.IMPLEMENTED,
            BtrfsOracleKind.BTRFS_PROGS_OFFLINE,
            "BtrfsFilesystemImage validates and traverses native root and filesystem trees for the supported single-device regular-file/directory snapshot. Native mkfs --rootdir interoperability and corrupt-checksum rejection are covered; unsupported features are refused.",
        ),
        BtrfsCapabilityStatus(
            BtrfsCapability.CHUNK_TREE_READ,
            BtrfsSupportStatus.IMPLEMENTED,
            BtrfsOracleKind.BTRFS_PROGS_OFFLINE,
            "BtrfsFilesystemImage traverses native chunk nodes, validates checksums and maps SINGLE/DUP chunks on one device. It refuses unsupported multi-device/profile combinations.",
        ),
        BtrfsCapabilityStatus(
            BtrfsCapability.DEVICE_TREE_READ,
            BtrfsSupportStatus.SKELETON,
            BtrfsOracleKind.BTRFS_PROGS_OFFLINE,
            "Native device/stripe leaf records are parsed and canonical device-tree records are emitted. General multi-device device-tree traversal and replay are not implemented.",
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
            "Native Btrfs block-group balancing and Linux balance ioctls are not implemented. USERSPACE_REBALANCE separately describes supported backing-storage relocation.",
        ),
        BtrfsCapabilityStatus(
            BtrfsCapability.FILESYSTEM_ELEMENT,
            BtrfsSupportStatus.IMPLEMENTED,
            BtrfsOracleKind.LOCAL_INVARIANT,
            "BtrfsFileSystemElement owns a bounded channel and SupervisorJob; native snapshot mutations publish complete durable generations. File operations, reopen, cancellation, errors and drain are exercised in BtrfsFileSystemElementTest.",
        ),
        BtrfsCapabilityStatus(
            BtrfsCapability.MOUNTED_VOLUME_GROWTH,
            BtrfsSupportStatus.IMPLEMENTED,
            BtrfsOracleKind.LOCAL_INVARIANT,
            "The mounted filesystem retains its identity while migrating into larger fresh file members. Queued operations settle across publication; shrink and aliased destinations are refused. BtrfsRaidRebalanceTest and BtrfsToolTest exercise reopening after addition.",
        ),
        BtrfsCapabilityStatus(
            BtrfsCapability.USERSPACE_REBALANCE,
            BtrfsSupportStatus.IMPLEMENTED,
            BtrfsOracleKind.LOCAL_INVARIANT,
            "BtrfsRaidRebalance copies or rewrites into a fresh supported userspace RAID/SPAN geometry, syncs and atomically publishes its manifest. This is backing relocation, not native Btrfs block-group conversion. Failed and ambiguous publication tests preserve recovery behavior.",
        ),
        BtrfsCapabilityStatus(
            BtrfsCapability.METADATA_COMPRESSION,
            BtrfsSupportStatus.IMPLEMENTED,
            BtrfsOracleKind.LOCAL_INVARIANT,
            "BtrfsMetadataVolume captures native tree pages into checked RAW/ZSTD/LZ4 sidecars and reconstructs canonical logical bytes. Tests check native codec interoperability, absent raw tree duplicates, compression change, corruption and reopened filesystem content.",
        ),
        BtrfsCapabilityStatus(
            BtrfsCapability.MACOS_FUSE_MOUNT,
            BtrfsSupportStatus.UNVERIFIED,
            BtrfsOracleKind.MACOS_FUSE_RUNTIME,
            "BtrfsMacFuseMount provides a Darwin arm64 macFUSE 2 FFM adapter over the common filesystem. Native callback ABI tests run on Linux; actual macOS mounting remains unverified in this cloud environment.",
        ),
    )

    fun statusOf(capability: BtrfsCapability): BtrfsCapabilityStatus =
        matrix.first { it.capability == capability }

    fun offlineOracleRequired(): List<BtrfsCapabilityStatus> =
        matrix.filter { it.oracle == BtrfsOracleKind.BTRFS_PROGS_OFFLINE && it.status != BtrfsSupportStatus.IMPLEMENTED }
}
