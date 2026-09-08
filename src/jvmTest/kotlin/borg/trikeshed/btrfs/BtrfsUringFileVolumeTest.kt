package borg.trikeshed.btrfs

import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.DocumentExtent
import borg.trikeshed.userspace.nio.DocumentInputElement
import borg.trikeshed.userspace.UringOp
import kotlinx.coroutines.test.runTest
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BtrfsUringFileVolumeTest {

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
                assertEquals("jvm_nio", volume.backendReport.backendName)
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
