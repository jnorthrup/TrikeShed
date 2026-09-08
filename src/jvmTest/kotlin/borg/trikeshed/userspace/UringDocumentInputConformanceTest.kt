package borg.trikeshed.userspace

import borg.trikeshed.btrfs.BtrfsUringChannelReport
import borg.trikeshed.btrfs.BtrfsUringFileVolume
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.toSeries
import borg.trikeshed.userspace.UringOp.Companion.Submissions
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.DocumentExtent
import borg.trikeshed.userspace.nio.DocumentInputElement
import borg.trikeshed.userspace.nio.channels.UringChannel
import borg.trikeshed.userspace.nio.channels.UringChannels
import borg.trikeshed.userspace.nio.spi.currentNioCapabilityReport
import java.nio.file.Files
import java.lang.management.ManagementFactory
import com.sun.management.UnixOperatingSystemMXBean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Raw disposable storage through the production Volume and document-input consumer; no image-format assertion. */
class UringDocumentInputConformanceTest {
    @Test
    fun scoped_volume_receipts_exact_document_cid_drain_and_reopen() = runTest {
        val directory = Files.createTempDirectory("trikeshed-uring-document-")
        val path = directory.resolve("volume.bin")
        val payload = "Document bytes cross the shared submission and completion boundary.".encodeToByteArray()
        val cid = ContentId.of(payload)
        val extent = DocumentExtent(3, payload.size, "document.txt", "text/plain", cid)
        val blockSize = 16
        val capacity = 16L
        try {
            val channel = UringChannels.open(this, entries = 4)
            val observed = channel.report()
            try {
                val volume = BtrfsUringFileVolume.open(
                    channel, path.toString(), blockSize, capacity,
                    create = true, resize = true,
                    backendReport = currentNioCapabilityReport(), channelReport = observed,
                )
                try {
                    assertEquals(observed, volume.channelReport)
                    assertEquals(256L, Files.size(path))
                    volume.write(0, ByteBuffer(ByteArray(256) { 90 }))
                    val guarded = ByteArray(payload.size + 6) { 127 }
                    payload.copyInto(guarded, 3)
                    val source = ByteBuffer(guarded).slice(1, payload.size + 3).position(2)
                    volume.write(extent.lba, source)
                    assertEquals(payload.size + 2, source.position())
                    assertEquals(127, guarded[2].toInt())
                    assertEquals(127, guarded[payload.size + 3].toInt())
                    volume.sync()

                    val input = DocumentInputElement.create(this, volume, capacity = 1, blocksPerRead = 1)
                    try {
                        val first = async(start = CoroutineStart.UNDISPATCHED) { input.read(extent) }
                        val second = async(start = CoroutineStart.UNDISPATCHED) { input.read(extent) }
                        input.drain()
                        for (result in arrayOf(first.await(), second.await())) {
                            assertContentEquals(payload, result.bytes, "block padding is not document content")
                            assertEquals(cid, result.cid)
                            assertEquals(extent, result.extent)
                        }
                        assertTrue(input.job.isCompleted, "document input joins accepted reads")
                    } finally { input.drain() }
                } finally { volume.drain() }

                val receipts = volume.ioReceipts()
                for (opcode in arrayOf(UringOp.OPENAT, UringOp.FTRUNCATE, UringOp.WRITE,
                    UringOp.FSYNC, UringOp.READ, UringOp.CLOSE)) {
                    assertTrue(receipts.any { it.opcode == opcode && it.res >= 0 }, "missing successful $opcode receipt")
                }
                assertEquals(receipts.size, receipts.map { it.userData }.toSet().size)
                assertTrue(receipts.all { it.submitted == 1 })
                assertTrue(receipts.filter { it.opcode == UringOp.READ }.all { it.res == blockSize })
                val opened = receipts.first { it.opcode == UringOp.OPENAT }.res
                assertFalse(FileImpl(opened).isOpen(), "volume drain closes its descriptor")
                println("document IO: bytes=${payload.size} cid=${cid.value} receipts=${receipts.map { it.opcode }} native=${observed.nativeCapabilities}")
            } finally { channel.drain() }
            assertFails { channel.batchEnqueue(arrayOf(Submissions.nop(999)).toSeries()) }

            val stored = Files.readAllBytes(path)
            assertEquals(90, stored[extent.lba.toInt() * blockSize - 1].toInt())
            assertEquals(90, stored[extent.lba.toInt() * blockSize + payload.size].toInt())
            val reopenedChannel = UringChannels.open(this, entries = 4)
            try {
                val reopened = BtrfsUringFileVolume.open(
                    reopenedChannel, path.toString(), blockSize, capacity,
                    create = false, resize = false,
                    backendReport = currentNioCapabilityReport(), channelReport = reopenedChannel.report(),
                )
                try {
                    val input = DocumentInputElement.create(this, reopened, blocksPerRead = 1)
                    try {
                        val result = input.read(extent)
                        assertContentEquals(payload, result.bytes)
                        assertEquals(cid, result.cid)
                    } finally {
                        input.drain()
                        assertTrue(input.job.isCompleted)
                    }
                } finally { reopened.drain() }
                assertFalse(reopened.ioReceipts().any { it.opcode == UringOp.FTRUNCATE }, "reopening does not resize storage")
                assertEquals(UringOp.CLOSE, reopened.ioReceipts().last().opcode)
            } finally { reopenedChannel.drain() }
            assertContentEquals(stored, Files.readAllBytes(path), "reopen and reads preserve all stored bytes")
            assertFails { reopenedChannel.batchEnqueue(arrayOf(Submissions.nop(999)).toSeries()) }
            assertFalse(coroutineContext[Job]!!.children.any { it.isActive }, "both compositions joined their children")
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun presized_compatibility_close_and_drain_release_the_open_descriptor() = runTest {
        val descriptorCounts = ManagementFactory.getOperatingSystemMXBean() as? UnixOperatingSystemMXBean
        val path = Files.createTempFile("trikeshed-uring-presized-", ".bin")
        try {
            for (suspending in arrayOf(false, true)) {
                Files.write(path, ByteArray(64))
                val volume = BtrfsUringFileVolume(path.toString(), blockSize = 16, capacity = 4, entries = 2)
                try {
                    val bytes = ByteArray(16) { (it + 1).toByte() }
                    volume.write(0, ByteBuffer(bytes))
                    volume.sync()
                    assertContentEquals(bytes, volume.read(0, 1).array())
                    val before = descriptorCounts?.openFileDescriptorCount
                    if (suspending) volume.drain() else volume.close()
                    val after = descriptorCounts?.openFileDescriptorCount
                    if (before != null && after != null) {
                        assertTrue(after < before, "the owned file descriptor must close: before=$before after=$after")
                    }
                    assertEquals(1, volume.ioReceipts().count { it.opcode == UringOp.CLOSE && it.res == 0 })
                    assertFails { volume.read(0, 1) }
                    volume.drain()
                    volume.close()
                    assertEquals(1, volume.ioReceipts().count { it.opcode == UringOp.CLOSE })
                    assertContentEquals(bytes, Files.readAllBytes(path).copyOfRange(0, 16))
                    println("presized volume: drain=$suspending descriptors=$before->$after")
                } finally { volume.drain() }
            }
        } finally { Files.deleteIfExists(path) }
    }

    private fun UringChannel.report() = BtrfsUringChannelReport(availability, capabilities, nativeCapabilities)
}
