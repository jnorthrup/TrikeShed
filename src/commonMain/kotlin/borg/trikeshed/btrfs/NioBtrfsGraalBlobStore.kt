package borg.trikeshed.btrfs

import borg.trikeshed.userspace.nio.file.spi.FileOperations

/**
 * Nio ↔ Btrfs ↔ Graal blob wiring — all commonMain, per-target chokepoints as TODO.
 *
 * One mount = local-exclusive GraalVM-access filesystem blobs:
 *  - growable, linkable (per span mount), raidable (per c userspace btrfs RAID)
 *  - on-disk layout must pass `btrfs check` before any mountable claim
 *
 * Every platform-specific seam is a TODO chokepoint so the whole object lives in commonMain.
 * Targets fill their actuals; jvmMainClasses stays green via TODO stubs.
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
        require(totalBytes >= (SUPER_OFFSET_PRIMARY + SUPER_SIZE).toULong()) {
            "image must at least hold the 64K superblock: totalBytes=$totalBytes"
        }
        // Superblock field layout matches BtrfsSuperblock.parse exactly:
        // bytenr@0 flags@8 magic@16 generation@24 root@32 chunkRoot@40 totalBytes@48 bytesUsed@56.
        val superBytes = ByteArray(SUPER_SIZE)
        writeULongLE(superBytes, 0, SUPER_OFFSET_PRIMARY.toULong())
        writeULongLE(superBytes, 8, 0uL)
        writeULongLE(superBytes, 16, BTRFS_MAGIC)
        writeULongLE(superBytes, 24, 1uL) // generation 1 — fresh filesystem
        writeULongLE(superBytes, 32, 0uL) // root tree bytenr skeleton
        writeULongLE(superBytes, 40, 0uL) // chunk root bytenr skeleton
        writeULongLE(superBytes, 48, totalBytes)
        writeULongLE(superBytes, 56, 0uL)

        val withMirror = totalBytes >= (SUPER_OFFSET_MIRRORS + SUPER_SIZE).toULong()
        val imgSize = if (withMirror) SUPER_OFFSET_MIRRORS + SUPER_SIZE else SUPER_OFFSET_PRIMARY + SUPER_SIZE
        val img = ByteArray(imgSize.toInt())
        superBytes.copyInto(img, SUPER_OFFSET_PRIMARY.toInt())
        if (withMirror) {
            // btrfs writes the 64MiB mirror only on devices big enough to hold it;
            // the mirror's bytenr field carries its own location.
            val mirrorBytes = superBytes.copyOf()
            writeULongLE(mirrorBytes, 0, SUPER_OFFSET_MIRRORS.toULong())
            mirrorBytes.copyInto(img, SUPER_OFFSET_MIRRORS.toInt())
        }
        fileOps.writeAtomically(imagePath, img)
        return true
    }

    private fun writeULongLE(buf: ByteArray, offset: Int, v: ULong) {
        for (i in 0..7) buf[offset + i] = ((v shr (i * 8)) and 0xFFuL).toByte()
    }

    fun verifyWithCBtrfs(imagePath: String): Boolean =
        TODO("posixMain: pinned btrfs-progs ProcessOperations.exec(\"btrfs\", \"check\", imagePath) == 0; jvmMain: local superblock parse is not a mountability oracle")

    /** Span mount: multiple devices in one volume (device tree). */
    fun spanMount(devices: List<String>, fileOps: FileOperations): BtrfsDeviceTree =
        TODO("BtrfsDeviceTree(devItems) + BtrfsChunkTree with stripes spanning devids; linkable reflink only within same span")

    /** Graal local-exclusive FileSystem view over the same UserspaceBtrfs volume. */
    fun graalFileSystem(rootDir: String, liveSubvol: String, fileOps: FileOperations): Any =
        TODO("jvmMain: TrikeShedGraalVfs(fileOps, rootDir, liveSubvol) with allowInternalResourceAccess only; js/wasm: TODO no Graal polyglot")

    // ── Wiring (commonMain composition, no platform import) ───────────────────

    /**
     * Wire sequence — all in commonMain, each step hits a chokepoint above:
     *
     *  fileOps (userspace nio) ──► UserspaceBtrfs(rootDir, fileOps)  // CoW extents + manifests
     *       │                         ▲ reflink (span mount)  ▲ raid (chunk tree)
     *       │                         │ BtrfsReflinkStore       │ BtrfsChunkItem
     *       ▼                         │                         │
     *  btrfs.img (superblock seed; btrfs-progs gated) ◄──── writeMountableImage
     *       │
     *       └─► acquireExclusive ──► TrikeShedGraalVfs / GraalBtrfsSupervisor  // local-exclusive
     *                              blobs are growable (CoW) + linkable + raidable
     *
     * Test: writeMountableImage(...) then verifyWithCBtrfs(...) before any blob grow/link/raid.
     */
    fun wireInfo(): String = buildString {
        appendLine("NioBtrfsGraalBlobStore wiring (all commonMain, chokepoints=TODO):")
        appendLine(" - userspace nio FileOperations (InMemoryFileOperations | PosixFileOperations | JvmFileOperations)")
        appendLine(" - UserspaceBtrfs CoW extents + snapshot (O(entries) not bytes)")
        appendLine(" - BtrfsReflinkStore reflink (span mount linkable)")
        appendLine(" - BtrfsChunkTree/BtrfsStripe RAID (c userspace btrfs type bits)")
        appendLine(" - TrikeShedGraalVfs local-exclusive (file lock)")
        appendLine(" - on-disk seed: superblock BTRFS_MAGIC=0x4D5F53665248425F at 64K; btrfs-progs oracle still required")
    }
}
