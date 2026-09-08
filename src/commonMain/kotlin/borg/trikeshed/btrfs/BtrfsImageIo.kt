package borg.trikeshed.btrfs

import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.UringChannel
import borg.trikeshed.userspace.nio.ebpf.UringEbpfProgram
import borg.trikeshed.userspace.nio.spi.NioCapabilityReport

data class BtrfsSeedSuperblockReceipt(
    val byteOffset: Long,
    val bytenr: ULong,
    val totalBytes: ULong,
    val generation: ULong,
    val root: ULong,
    val chunkRoot: ULong,
)

data class BtrfsImageWriteReceipt(
    val imagePath: String,
    val totalBytes: ULong,
    val blockSize: Int,
    val capacityBlocks: Long,
    val requiredBytes: ULong,
    val backend: NioCapabilityReport,
    val channelReport: BtrfsUringChannelReport?,
    val io: List<BtrfsVolumeIoCompletion>,
    val superblocks: List<BtrfsSeedSuperblockReceipt>,
) {
    val wroteMirror: Boolean get() = superblocks.any {
        it.byteOffset == NioBtrfsGraalBlobStore.SUPER_OFFSET_MIRRORS
    }
}

object BtrfsSeedImageLayout {
    fun requireTotalBytes(totalBytes: ULong) {
        require(totalBytes >= (NioBtrfsGraalBlobStore.SUPER_OFFSET_PRIMARY + NioBtrfsGraalBlobStore.SUPER_SIZE).toULong()) {
            "image must at least hold the 64K superblock: totalBytes=$totalBytes"
        }
        require(totalBytes % NioBtrfsGraalBlobStore.SECTOR_MIN.toULong() == 0uL) {
            "image size must be sector aligned: totalBytes=$totalBytes sector=${NioBtrfsGraalBlobStore.SECTOR_MIN}"
        }
    }

    fun superblockOffsets(totalBytes: ULong): List<Long> {
        requireTotalBytes(totalBytes)
        return if (totalBytes >= (NioBtrfsGraalBlobStore.SUPER_OFFSET_MIRRORS + NioBtrfsGraalBlobStore.SUPER_SIZE).toULong()) {
            listOf(NioBtrfsGraalBlobStore.SUPER_OFFSET_PRIMARY, NioBtrfsGraalBlobStore.SUPER_OFFSET_MIRRORS)
        } else {
            listOf(NioBtrfsGraalBlobStore.SUPER_OFFSET_PRIMARY)
        }
    }

    fun requiredBytes(totalBytes: ULong): ULong {
        val last = superblockOffsets(totalBytes).last()
        return (last + NioBtrfsGraalBlobStore.SUPER_SIZE).toULong()
    }

    fun superblockBytes(bytenr: ULong, totalBytes: ULong): ByteArray {
        requireTotalBytes(totalBytes)
        val superBytes = ByteArray(NioBtrfsGraalBlobStore.SUPER_SIZE)
        writeULongLE(superBytes, 0, bytenr)
        writeULongLE(superBytes, 8, 0uL)
        writeULongLE(superBytes, 16, BTRFS_MAGIC)
        writeULongLE(superBytes, 24, 1uL)
        writeULongLE(superBytes, 32, 0uL)
        writeULongLE(superBytes, 40, 0uL)
        writeULongLE(superBytes, 48, totalBytes)
        writeULongLE(superBytes, 56, 0uL)
        return superBytes
    }

    private fun writeULongLE(buf: ByteArray, offset: Int, v: ULong) {
        for (i in 0..7) buf[offset + i] = ((v shr (i * 8)) and 0xffuL).toByte()
    }
}

object BtrfsImageIo {
    suspend fun writeSeedImage(
        imagePath: String,
        totalBytes: ULong,
        entries: Int = 8,
        ebpfPrograms: List<UringEbpfProgram> = emptyList(),
    ): BtrfsImageWriteReceipt {
        BtrfsSeedImageLayout.requireTotalBytes(totalBytes)
        require(totalBytes <= Long.MAX_VALUE.toULong()) { "image size exceeds host Long range: $totalBytes" }
        val blockSize = NioBtrfsGraalBlobStore.SECTOR_MIN.toInt()
        val capacityBlocks = totalBytes.toLong() / blockSize
        val volume = BtrfsUringFileVolume(imagePath, blockSize, capacityBlocks, entries, ebpfPrograms)
        return writeSeedImage(volume, totalBytes)
    }

    suspend fun writeSeedImage(
        channel: UringChannel,
        imagePath: String,
        totalBytes: ULong,
        backend: NioCapabilityReport,
        channelReport: BtrfsUringChannelReport? = null,
    ): BtrfsImageWriteReceipt {
        BtrfsSeedImageLayout.requireTotalBytes(totalBytes)
        require(totalBytes <= Long.MAX_VALUE.toULong()) { "image size exceeds host Long range: $totalBytes" }
        val blockSize = NioBtrfsGraalBlobStore.SECTOR_MIN.toInt()
        val capacityBlocks = totalBytes.toLong() / blockSize
        val volume = BtrfsUringFileVolume.open(
            channel = channel,
            imagePath = imagePath,
            blockSize = blockSize,
            capacity = capacityBlocks,
            backendReport = backend,
            channelReport = channelReport,
        )
        return writeSeedImage(volume, totalBytes)
    }

    private suspend fun writeSeedImage(
        volume: BtrfsUringFileVolume,
        totalBytes: ULong,
    ): BtrfsImageWriteReceipt {
        try {
            val offsets = BtrfsSeedImageLayout.superblockOffsets(totalBytes)
            for (offset in offsets) {
                val bytes = BtrfsSeedImageLayout.superblockBytes(offset.toULong(), totalBytes)
                volume.write(offset / volume.blockSize, ByteBuffer.wrap(bytes))
            }
            volume.sync()

            val superblocks = offsets.map { offset ->
                val bytes = volume.read(offset / volume.blockSize, 1).let { buffer ->
                    val out = ByteArray(NioBtrfsGraalBlobStore.SUPER_SIZE)
                    buffer.get(out)
                    out
                }
                val parsed = BtrfsSuperblock.parse(bytes)
                require(parsed.bytenr == offset.toULong()) {
                    "superblock bytenr mismatch at $offset: ${parsed.bytenr}"
                }
                require(parsed.totalBytes == totalBytes) {
                    "superblock totalBytes mismatch at $offset: ${parsed.totalBytes}"
                }
                BtrfsSeedSuperblockReceipt(
                    byteOffset = offset,
                    bytenr = parsed.bytenr,
                    totalBytes = parsed.totalBytes,
                    generation = parsed.generation,
                    root = parsed.root,
                    chunkRoot = parsed.chunkRoot,
                )
            }
            volume.drain()
            return BtrfsImageWriteReceipt(
                imagePath = volume.imagePath,
                totalBytes = totalBytes,
                blockSize = volume.blockSize,
                capacityBlocks = volume.capacity,
                requiredBytes = BtrfsSeedImageLayout.requiredBytes(totalBytes),
                backend = volume.backendReport,
                channelReport = volume.channelReport,
                io = volume.ioReceipts(),
                superblocks = superblocks,
            )
        } catch (failure: Throwable) {
            runCatching { volume.drain() }
            throw failure
        }
    }
}
