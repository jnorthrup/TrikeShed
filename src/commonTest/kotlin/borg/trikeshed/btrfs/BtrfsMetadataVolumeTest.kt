package borg.trikeshed.btrfs

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.btrfs.BtrfsCodecLE
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.Volume
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class BtrfsMetadataVolumeTest {
    private class SparseVolume(
        override val blockSize: Int = 4096,
        override val capacity: Long = BtrfsFilesystemImage.MINIMUM_BYTES / blockSize,
    ) : Volume {
        val blocks = mutableMapOf<Long, ByteArray>()
        val writes = mutableListOf<Join<Long, Int>>()
        var syncs = 0
        override suspend fun read(lba: Long, count: Int): ByteBuffer {
            require(count >= 0 && lba >= 0 && lba <= capacity - count)
            val bytes = ByteArray(count * blockSize)
            repeat(count) { index -> blocks[lba + index]?.copyInto(bytes, index * blockSize) }
            return ByteBuffer.wrap(bytes)
        }
        override suspend fun write(lba: Long, data: ByteBuffer) {
            val bytes = bytes(data)
            val count = (bytes.size + blockSize - 1) / blockSize
            require(lba >= 0 && lba <= capacity - count)
            writes.add(lba j count)
            repeat(count) { index ->
                val block = blocks.getOrPut(lba + index) { ByteArray(blockSize) }
                bytes.copyInto(block, 0, index * blockSize, minOf(bytes.size, (index + 1) * blockSize))
            }
        }
        override suspend fun sync() { syncs++ }
    }

    @Test fun nativeTreesOccupyOnlyCompressedBackingAndReopenAsCanonicalBytes() = runTest {
        for (codec in arrayOf(BtrfsMetadataCompression.ZSTD, BtrfsMetadataCompression.LZ4)) {
            val raw = SparseVolume()
            val staging = BtrfsMetadataVolume.stage(raw)
            val files = InMemoryFileOperations()
            val content = ByteArray(8197) { (it * 31).toByte() }
            val written = BtrfsFilesystemImage.write(staging,
                listOf(BtrfsImageEntry("documents/report.bin", content)), "compressed trees", 7uL)
            assertTrue(staging.ranges.size >= 6, "Native Btrfs tree writer must identify actual tree blocks")
            var treeBytes = 0
            for (range in staging.ranges.view) {
                treeBytes += range.b * raw.blockSize
                assertTrue(bytes(raw.read(range.a, range.b)).all { it == 0.toByte() }, "Raw tree addresses must remain unwritten")
                assertTrue(raw.writes.none { it.a < range.a + range.b && range.a < it.a + it.b })
                assertTrue(bytes(staging.read(range.a, range.b)).any { it != 0.toByte() })
            }
            val descriptor = staging.finish(files, "/tree-${codec.name}", codec)
            assertTrue(descriptor.storedBytes < treeBytes / 2, "Tree storage must actually shrink")
            assertEquals(codec, BtrfsMetadataCompression.storedCompression(files.readAllBytes(descriptor.path)))
            assertTrue(raw.syncs > 0)
            val opened = BtrfsMetadataVolume.open(raw, files, descriptor)
            val restored = BtrfsFilesystemImage.read(opened)
            assertEquals(written.generation, restored.generation)
            assertEquals(written.label, restored.label)
            assertContentEquals(written.fsid, restored.fsid)
            assertContentEquals(content, restored.entries.single { it.path == "documents/report.bin" }.data)
            assertFailsWith<IllegalStateException> { opened.write(0, ByteBuffer.wrap(byteArrayOf(1))) }
            assertFailsWith<IllegalStateException> { staging.writeMetadata(0, ByteBuffer.wrap(ByteArray(raw.blockSize))) }
        }
    }

    @Test fun overlayPreservesPartialRawWritesAndDefendsCapturedExtentOwnership() = runTest {
        val raw = SparseVolume(blockSize = 16, capacity = 32)
        val stage = BtrfsMetadataVolume.stage(raw)
        stage.write(2, ByteBuffer.wrap(byteArrayOf(1, 2, 3)))
        val captured = ByteArray(32) { (it + 10).toByte() }
        val expected = captured.copyOf()
        stage.writeMetadata(3, ByteBuffer.wrap(captured))
        captured.fill(0)
        assertContentEquals(byteArrayOf(1, 2, 3) + ByteArray(13) + expected + ByteArray(16), bytes(stage.read(2, 4)))
        assertContentEquals(ByteArray(32), bytes(raw.read(3, 2)))
        val ranges = stage.ranges
        assertEquals(3L, ranges[0].a)
        assertEquals(2, ranges[0].b)
        assertFailsWith<IllegalArgumentException> { stage.write(3, ByteBuffer.wrap(byteArrayOf(1))) }
        assertFailsWith<IllegalArgumentException> { stage.writeMetadata(4, ByteBuffer.wrap(ByteArray(32))) }
        assertFailsWith<IllegalArgumentException> { stage.writeMetadata(3, ByteBuffer.wrap(ByteArray(16))) }
        assertFailsWith<IllegalArgumentException> { stage.writeMetadata(Long.MAX_VALUE, ByteBuffer.wrap(ByteArray(16))) }
        stage.writeMetadata(8, ByteBuffer.wrap(ByteArray(16) { 7 }))
        assertEquals(1, ranges.size, "Captured range snapshots must remain immutable")
        assertEquals(2, stage.ranges.size)
        assertContentEquals(ByteArray(0), bytes(stage.read(raw.capacity, 0)))
        assertFailsWith<IllegalArgumentException> { stage.write(raw.capacity, ByteBuffer.wrap(byteArrayOf(1))) }
    }

    @Test fun sidecarFailureKeepsStagingUnpublishedAndExistingSidecarsAreNeverOverwritten() = runTest {
        val raw = SparseVolume()
        val stage = BtrfsMetadataVolume.stage(raw)
        val backing = InMemoryFileOperations()
        stage.writeMetadata(8, ByteBuffer.wrap(ByteArray(16384) { 42 }))
        val failed = object : FileOperations by backing {
            override fun writeAtomically(filename: String, bytes: ByteArray) { error("Injected sidecar publication failure") }
        }
        assertFailsWith<IllegalStateException> { stage.finish(failed, "/failed", BtrfsMetadataCompression.ZSTD) }
        assertFalse(backing.exists("/failed"))
        stage.write(0, ByteBuffer.wrap(byteArrayOf(9)))
        backing.write("/existing", byteArrayOf(1))
        assertFailsWith<IllegalArgumentException> { stage.finish(backing, "/existing", BtrfsMetadataCompression.LZ4) }
        assertContentEquals(byteArrayOf(1), backing.readAllBytes("/existing"))
        val descriptor = stage.finish(backing, "/success", BtrfsMetadataCompression.LZ4)
        assertContentEquals(ByteArray(16384) { 42 }, bytes(BtrfsMetadataVolume.open(raw, backing, descriptor).read(8, 4)))
    }

    @Test fun descriptorIntegrityGeometryAndExtentBoundsAreValidatedBeforeExposure() = runTest {
        val raw = SparseVolume()
        val stage = BtrfsMetadataVolume.stage(raw)
        val files = InMemoryFileOperations()
        stage.writeMetadata(8, ByteBuffer.wrap(ByteArray(16384) { 42 }))
        val descriptor = stage.finish(files, "/valid", BtrfsMetadataCompression.LZ4)
        assertFailsWith<IllegalArgumentException> { BtrfsMetadataVolume.open(raw, files, descriptor.copy(storedBytes = descriptor.storedBytes + 1)) }
        assertFailsWith<IllegalArgumentException> { BtrfsMetadataVolume.open(raw, files, descriptor.copy(decodedBytes = 64)) }
        assertFailsWith<IllegalArgumentException> { BtrfsMetadataVolume.open(raw, files, descriptor.copy(contentId = ContentId.of(byteArrayOf(0)))) }
        assertFailsWith<IllegalArgumentException> { BtrfsMetadataVolume.open(SparseVolume(capacity = raw.capacity + 1), files, descriptor) }
        val decoded = BtrfsMetadataCompression.decode(files.readAllBytes(descriptor.path))
        // Independent malformed records have valid outer checksums and content identities.
        for (offset in arrayOf(28, 32, 40, 44)) {
            val altered = decoded.copyOf()
            when (offset) {
                32 -> BtrfsCodecLE.writeLong(Long.MAX_VALUE, altered, offset)
                40 -> BtrfsCodecLE.writeInt(Int.MAX_VALUE, altered, offset)
                else -> BtrfsCodecLE.writeInt(1, altered, offset)
            }
            val stored = BtrfsMetadataCompression.RAW.encode(altered)
            files.write("/malformed", stored)
            val hostile = descriptor.copy(path = "/malformed", contentId = ContentId.of(stored), storedBytes = stored.size)
            assertFailsWith<IllegalArgumentException>("Malformed sidecar field $offset") { BtrfsMetadataVolume.open(raw, files, hostile) }
        }
    }

    companion object {
        private fun bytes(buffer: ByteBuffer): ByteArray = ByteArray(buffer.remaining()).also { buffer.get(it) }
    }
}
