package borg.trikeshed.btrfs

import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.DocumentExtent
import borg.trikeshed.userspace.nio.DocumentInputElement
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.nio.channels.UringChannels
import borg.trikeshed.userspace.nio.spi.currentNioCapabilityReport
import kotlinx.coroutines.test.runTest
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BtrfsUringFileVolumeTest {

    @Test
    fun invalid_entries_fail_before_creating_a_compatibility_file() {
        val directory = Files.createTempDirectory("trikeshed-btrfs-construction-")
        val path = directory.resolve("missing.img")
        try {
            assertFailsWith<IllegalArgumentException> {
                BtrfsUringFileVolume(path.toString(), blockSize = 512, capacity = 1, entries = 0)
            }
            assertFalse(Files.exists(path))
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun connected_reopen_preserves_capacity_and_read_only_data_until_explicit_resize() = runTest {
        val directory = Files.createTempDirectory("trikeshed-btrfs-reopen-")
        val path = directory.resolve("volume.img")
        val channel = UringChannels.open(entries = 8)
        val payload = byteArrayOf(1, 2, 3, 4)
        try {
            val created = BtrfsUringFileVolume.open(channel, path.toString(), blockSize = 512, capacity = 16,
                backendReport = currentNioCapabilityReport())
            try {
                created.write(3, ByteBuffer.wrap(payload))
                created.sync()
            } finally {
                created.drain()
            }
            assertEquals(8192L, Files.size(path))
            val reopened = BtrfsUringFileVolume.open(channel, path.toString(), blockSize = 512, capacity = 8,
                create = false, backendReport = currentNioCapabilityReport(), readOnly = true)
            try {
                assertEquals(8192L, Files.size(path))
                val bytes = ByteArray(payload.size)
                reopened.read(3, 1).get(bytes)
                assertContentEquals(payload, bytes)
                assertFailsWith<IllegalStateException> { reopened.write(3, ByteBuffer.wrap(payload)) }
                assertFalse(reopened.ioReceipts().any { it.opcode == UringOp.FTRUNCATE })
            } finally {
                reopened.drain()
            }
            val resized = BtrfsUringFileVolume.open(channel, path.toString(), blockSize = 512, capacity = 32,
                create = false, resize = true, backendReport = currentNioCapabilityReport())
            try {
                assertEquals(16384L, Files.size(path))
                val bytes = ByteArray(payload.size)
                resized.read(3, 1).get(bytes)
                assertContentEquals(payload, bytes)
                val padding = ByteArray(512)
                resized.read(31, 1).get(padding)
                assertContentEquals(ByteArray(512), padding)
            } finally {
                resized.drain()
            }
        } finally {
            channel.drain()
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun file_backed_volume_survives_reopen_through_common_uring_channel() = runTest {
        val path = Files.createTempFile("trikeshed-btrfs-volume-", ".img")
        try {
            RandomAccessFile(path.toFile(), "rw").use { it.setLength(16L * 512L) }
            val payload = "nonvolatile document bytes".encodeToByteArray()
            val writeVolume = BtrfsUringFileVolume(path.toString(), blockSize = 512, capacity = 16)
            try {
                val volume = writeVolume
                volume.write(3, ByteBuffer.wrap(payload))
                volume.sync()
                assertEquals(currentNioCapabilityReport(), volume.backendReport)
                assertTrue(volume.ioReceipts().any { it.opcode == UringOp.WRITE && it.res == payload.size })
                assertTrue(volume.ioReceipts().any { it.opcode == UringOp.FSYNC && it.res == 0 })
            } finally {
                writeVolume.close()
            }

            val readVolume = BtrfsUringFileVolume(path.toString(), blockSize = 512, capacity = 16)
            try {
                val reopened = readVolume
                val read = reopened.read(3, 1)
                val actual = ByteArray(payload.size)
                read.get(actual)
                assertContentEquals(payload, actual)
                assertTrue(reopened.ioReceipts().any { it.opcode == UringOp.READ && it.res == 512 })
            } finally {
                readVolume.close()
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun document_input_can_read_from_file_backed_userspace_nio_volume() = runTest {
        val path = Files.createTempFile("trikeshed-document-volume-", ".img")
        try {
            RandomAccessFile(path.toFile(), "rw").use { it.setLength(32L * 512L) }
            val bytes = "DocumentFeed durable volume boundary.".encodeToByteArray()
            val volume = BtrfsUringFileVolume(path.toString(), blockSize = 512, capacity = 32)
            try {
                volume.write(4, ByteBuffer.wrap(bytes))
                volume.sync()
                val input = DocumentInputElement.create(this, volume, blocksPerRead = 1)
                try {
                    val extent = DocumentExtent(4, bytes.size, "contract.txt", "text/plain", ContentId.of(bytes))
                    val read = input.read(extent)
                    assertContentEquals(bytes, read.bytes)
                    assertEquals(ContentId.of(bytes), read.cid)
                } finally {
                    input.drain()
                }
            } finally {
                volume.close()
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
