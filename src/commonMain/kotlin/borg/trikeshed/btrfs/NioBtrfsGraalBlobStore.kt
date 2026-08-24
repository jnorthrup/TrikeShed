package borg.trikeshed.btrfs

import borg.trikeshed.userspace.nio.file.spi.FileOperations

/**
 * Nio ↔ Btrfs ↔ Graal blob wiring — all commonMain, per-target chokepoints as TODO.
 *
 * One mount = local-exclusive GraalVM-access filesystem blobs:
 *  - growable, linkable (per span mount), raidable (per c userspace btrfs RAID)
 *  - on-disk layout stays mountable by `btrfs check` (superblock + chunk tree + dev item)
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
    fun growBlob(rootDir: String, subvol: String, path: String, appended: ByteArray, fileOps: FileOperations): Boolean =
        TODO("common path: UserspaceBtrfs.writeFile(subvol, path, existing+appended) CoW; target: FileOperations.writeAtomically for durability")

    fun truncateBlob(rootDir: String, subvol: String, path: String, newSize: Long, fileOps: FileOperations): Boolean =
        TODO("posixMain: fallocate FALLOC_FL_PUNCH_HOLE via nio; jvmMain: SeekableByteChannel.truncate")

    /** Linkable per span mount: reflink extent across subvolumes/devices on same filesystem. */
    fun reflinkBlob(srcSubvol: String, srcPath: String, dstSubvol: String, dstPath: String, fileOps: FileOperations): Boolean =
        TODO("posixMain: ioctl FICLONE / cp --reflink=always; jvmMain: FileOperations reflink via BtrfsReflinkStore.reflinkCopy; span mount: device tree must share chunk tree")

    fun reflinkRange(srcFd: Int, dstFd: Int, srcOff: Long, len: Long): Boolean =
        TODO("linuxMain: ioctl FICLONERANGE via zlinux_uring; js/wasm: TODO no reflink")

    /** Raidable per c userspace btrfs RAID code: chunk tree parity via BtrfsChunkItem type. */
    fun allocateChunk(logical: ULong, length: ULong, raidType: UByte, stripes: List<BtrfsStripe>): BtrfsChunkItem =
        TODO("common: BtrfsChunkItem(stripeLength=length, type=raidType, numStripes=stripes.size, …); c parity: reuse type bits SINGLE=0x0 RAID0=0x8 RAID1=0x10 RAID10=0x20 RAID5=0x40 RAID6=0x80 DUP=0x01 — BtrfsChunkTree.parse verifies")

    fun stripeForRaid(raidType: UByte, devids: List<ULong>): List<BtrfsStripe> =
        TODO("c userspace btrfs: mirror for RAID1/DUP/Raid10, stripe for RAID0/Raid5/6 — BtrfsStripe(devid, offset) per device")

    /** On-disk mountable layout: superblock + chunk tree + dev item inside btrfs.img. */
    fun writeMountableImage(imagePath: String, totalBytes: ULong, fileOps: FileOperations): Boolean =
        TODO("common header: ByteArray(4096) with BTRFS_MAGIC at le16, generation, root, chunkRoot, totalBytes; write at SUPER_OFFSET_PRIMARY + mirrors; jvmMain: FileOperations.writeAtomically")

    fun verifyWithCBtrfs(imagePath: String): Boolean =
        TODO("posixMain: ProcessOperations.exec(\"btrfs\", \"check\", imagePath) == 0; jvmMain: BtrfsSuperblock.parse(readAt(SUPER_OFFSET_PRIMARY)).magic == BTRFS_MAGIC; test harness tags as mountable")

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
     *  btrfs.img (superblock+chunk tree, mountable) ◄──── writeMountableImage
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
        appendLine(" - on-disk mountable: superblock BTRFS_MAGIC=0x4D5F53665248425F at 64K, chunk+dev trees")
    }
}
