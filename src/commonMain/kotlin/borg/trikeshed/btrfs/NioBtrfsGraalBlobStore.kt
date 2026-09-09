package borg.trikeshed.btrfs

import borg.trikeshed.userspace.nio.ebpf.UringEbpfProgram
import borg.trikeshed.userspace.nio.channels.UringChannel
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.spi.NioCapabilityReport
import kotlinx.coroutines.CoroutineScope

/**
 * Common userspace block-volume, Btrfs seed-image, and Graal blob entry points.
 *
 * [openRaidImage] and the configuration-based [spanMount] expose persistent
 * userspace block I/O. Canonical Btrfs filesystem serialization, exclusive
 * filesystem locking, ioctl reflinks, and Graal mounting remain incomplete.
 */
object NioBtrfsGraalBlobStore {

    /** Superblock + chunk tree location constants (btrfs on-disk spec). */
    const val SUPER_OFFSET_PRIMARY = 65536L // 64 KiB
    const val SUPER_OFFSET_MIRRORS = 67108864L // 64 MiB
    const val SUPER_SIZE = 4096
    const val SECTOR_MIN = 4096L

    // ── Chokepoints ──────────────────────────────────────────────────────────

    /** Local-exclusive: one Graal isolate owns the volume. */
    fun acquireExclusive(rootDir: String, fileOps: FileOperations): Boolean =
        TODO("jvmMain: FileChannel.lock on $rootDir/.btrfs.lock ; posixMain: flock() ; js/wasm: no-op exclusive")

    fun releaseExclusive(rootDir: String, fileOps: FileOperations) {
        TODO("jvmMain: release lock; posixMain: flock unlock")
    }

    /** Growable blob: CoW extent append / truncate. */
    fun growBlob(rootDir: String, subvol: String, path: String, appended: ByteArray, fileOps: FileOperations): Boolean {
        // common path: UserspaceBtrfs CoW — fetch existing bytes, append, write back as a NEW extent.
        // Durability rides FileOperations.writeAtomically inside UserspaceBtrfs.persistManifest.
        val btrfs = UserspaceBtrfs(rootDir, fileOps)
        if (!btrfs.hasSubvolume(subvol)) return false
        val existing = btrfs.fetchFile(subvol, path) ?: ByteArray(0)
        return btrfs.writeFile(subvol, path, existing + appended)
    }

    fun truncateBlob(rootDir: String, subvol: String, path: String, newSize: Long, fileOps: FileOperations): Boolean =
        TODO("posixMain: fallocate FALLOC_FL_PUNCH_HOLE via nio; jvmMain: SeekableByteChannel.truncate")

    /** Linkable per span mount: reflink extent across subvolumes/devices on same filesystem. */
    fun reflinkBlob(srcSubvol: String, srcPath: String, dstSubvol: String, dstPath: String, fileOps: FileOperations): Boolean =
        TODO("posixMain: ioctl FICLONE / cp --reflink=always; jvmMain: FileOperations reflink via BtrfsReflinkStore.reflinkCopy; span mount: device tree must share chunk tree")

    fun reflinkRange(srcFd: Int, dstFd: Int, srcOff: Long, len: Long): Boolean =
        TODO("linuxMain: ioctl FICLONERANGE via zlinux_uring; js/wasm: TODO no reflink")

    /** Raidable per c userspace btrfs RAID code: chunk tree parity via BtrfsChunkItem type. */
    fun allocateChunk(logical: ULong, length: ULong, raidType: UByte, stripes: List<BtrfsStripe>): BtrfsChunkItem {
        // c parity type bits (btrfs_block_group_flags): SINGLE=0x0 DUP=0x01 RAID0=0x8
        // RAID1=0x10 RAID10=0x20 RAID5=0x40 RAID6=0x80 — BtrfsChunkTree.parse verifies.
        require(stripes.isNotEmpty()) { "chunk needs at least one stripe" }
        val profile = BtrfsBlockGroupProfile.fromBits(raidType)
        profile.validate(stripes)
        val subStripes: UShort = if (profile == BtrfsBlockGroupProfile.RAID10) 2u else 0u
        return BtrfsChunkItem(
            stripeLength = length,
            type = raidType,
            numStripes = stripes.size.toUShort(),
            subStripes = subStripes,
            stripes = stripes,
        )
    }

    fun stripeForRaid(raidType: UByte, devids: List<ULong>): List<BtrfsStripe> {
        require(devids.isNotEmpty()) { "need at least one devid" }
        val profile = BtrfsBlockGroupProfile.fromBits(raidType)
        require(devids.size >= if (profile == BtrfsBlockGroupProfile.DUP) 1 else profile.minStripes) {
            "${profile.name} needs enough devices for ${profile.minStripes} stripes"
        }
        val stripes = when (profile) {
            // mirror layouts: one stripe per device at the same offset (DUP mirrors on ONE device)
            BtrfsBlockGroupProfile.DUP -> listOf(BtrfsStripe(devids[0], 0uL), BtrfsStripe(devids[0], 0uL))
            BtrfsBlockGroupProfile.RAID1, BtrfsBlockGroupProfile.RAID10 -> devids.map { BtrfsStripe(it, 0uL) }
            // stripe layouts: one stripe per device, data striped across them
            BtrfsBlockGroupProfile.SINGLE -> listOf(BtrfsStripe(devids[0], 0uL))
            BtrfsBlockGroupProfile.RAID0, BtrfsBlockGroupProfile.RAID5, BtrfsBlockGroupProfile.RAID6 -> devids.map { BtrfsStripe(it, 0uL) }
        }
        profile.validate(stripes)
        return stripes
    }

    /** On-disk seed layout. It is not mountable until the offline btrfs-progs oracle passes. */
    fun writeMountableImage(imagePath: String, totalBytes: ULong, fileOps: FileOperations): Boolean {
        BtrfsSeedImageLayout.requireTotalBytes(totalBytes)
        val imgSize = BtrfsSeedImageLayout.requiredBytes(totalBytes).toLong()
        val img = ByteArray(imgSize.toInt())
        for (offset in BtrfsSeedImageLayout.superblockOffsets(totalBytes)) {
            // btrfs writes the 64MiB mirror only on devices big enough to hold it.
            BtrfsSeedImageLayout.superblockBytes(offset.toULong(), totalBytes).copyInto(img, offset.toInt())
        }
        fileOps.writeAtomically(imagePath, img)
        return true
    }

    /** Compatibility uring path for disposable pre-sized image files; returns backend/completion receipts. */
    suspend fun writeImageViaUring(
        imagePath: String,
        totalBytes: ULong,
        entries: Int = 8,
        ebpfPrograms: List<UringEbpfProgram> = emptyList(),
    ): BtrfsImageWriteReceipt =
        BtrfsImageIo.writeSeedImage(imagePath, totalBytes, entries, ebpfPrograms)

    /** Creates a new seed image exclusively through the selected channel; existing images are preserved. */
    suspend fun writeImageViaUring(
        channel: UringChannel,
        imagePath: String,
        totalBytes: ULong,
        backend: NioCapabilityReport,
        channelReport: BtrfsUringChannelReport? = null,
    ): BtrfsImageWriteReceipt =
        BtrfsImageIo.writeSeedImage(channel, imagePath, totalBytes, backend, channelReport)

    fun verifyWithCBtrfs(imagePath: String): Boolean =
        TODO("posixMain: pinned btrfs-progs ProcessOperations.exec(\"btrfs\", \"check\", imagePath) == 0; jvmMain: local superblock parse is not a mountability oracle")

    /** Persistent userspace block volume over owned image members and uring channels. */
    suspend fun openRaidImage(
        scope: CoroutineScope,
        config: BtrfsRaidImageConfig,
        create: Boolean = false,
        resize: Boolean = false,
        unavailable: Set<Int> = emptySet(),
    ): BtrfsRaidImage = BtrfsRaidImage.open(scope, config, create, resize, unavailable = unavailable)

    /** Concatenates member data regions; this entry does not mount a kernel filesystem. */
    suspend fun spanMount(
        scope: CoroutineScope,
        config: BtrfsRaidImageConfig,
        create: Boolean = false,
        resize: Boolean = false,
    ): BtrfsRaidImage {
        require(config.layout == BtrfsVolumeLayout.SPAN) { "spanMount requires SPAN layout" }
        return openRaidImage(scope, config, create, resize)
    }

    /** Legacy device-tree entry retained for source compatibility; filesystem mounting remains incomplete. */
    fun spanMount(devices: List<String>, fileOps: FileOperations): BtrfsDeviceTree =
        TODO("BtrfsDeviceTree(devItems) + BtrfsChunkTree with stripes spanning devids; linkable reflink only within same span")

    /** Graal local-exclusive FileSystem view over the same UserspaceBtrfs volume. */
    fun graalFileSystem(rootDir: String, liveSubvol: String, fileOps: FileOperations): Any =
        TODO("jvmMain: TrikeShedGraalVfs(fileOps, rootDir, liveSubvol) with allowInternalResourceAccess only; js/wasm: TODO no Graal polyglot")

    // ── Wiring (commonMain composition, no platform import) ───────────────────

    /**
     * RAID/SPAN block volumes, directory-backed blobs, and seed-image fixtures
     * are distinct consumers. A mountable filesystem still requires complete
     * canonical trees and an offline Btrfs oracle.
     */
    fun wireInfo(): String = buildString {
        appendLine("NioBtrfsGraalBlobStore userspace wiring:")
        appendLine(" - openRaidImage / spanMount -> BtrfsRaidImage -> BtrfsRaidVolume -> owned uring image members")
        appendLine(" - userspace nio FileOperations (InMemoryFileOperations | PosixFileOperations | JvmFileOperations)")
        appendLine(" - UserspaceBtrfs CoW extents + snapshot (O(entries) not bytes)")
        appendLine(" - BtrfsReflinkStore models shared blobs; filesystem reflink ioctl wiring remains incomplete")
        appendLine(" - BtrfsChunkTree/BtrfsStripe retain local profile metadata fixtures")
        appendLine(" - exclusive filesystem locking and Graal mount entry points remain incomplete")
        appendLine(" - BtrfsUringFileVolume: nonvolatile userspace.nio.Volume over selected uring channel")
        appendLine(" - on-disk seed: superblock BTRFS_MAGIC=0x4D5F53665248425F at 64K; btrfs-progs oracle still required")
    }
}
