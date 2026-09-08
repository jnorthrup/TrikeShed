package borg.trikeshed.userspace.nio

import borg.trikeshed.job.ContentId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DocumentInputElementTest {
    @Test
    fun stagedBytesAreSyncedAndReadBackWithoutPadding() = runTest {
        val stored = InMemoryVolume(16, 4)
        val events = mutableListOf<String>()
        val volume = object : Volume by stored {
            override suspend fun write(lba: Long, data: ByteBuffer) {
                events.add("write")
                stored.write(lba, data)
            }
            override suspend fun sync() { events.add("sync") }
            override suspend fun read(lba: Long, count: Int): ByteBuffer {
                events.add("read")
                return stored.read(lba, count)
            }
        }
        val input = DocumentInputElement.create(this, volume)
        val bytes = "Exact λ source.".encodeToByteArray()
        try {
            val staged = input.stage(1, bytes, "source.txt", "text/plain")
            assertContentEquals(bytes, staged.bytes)
            assertEquals(ContentId.of(bytes), staged.cid)
            assertEquals(listOf("write", "sync", "read"), events)
            assertEquals(1L, staged.extent.lba)
        } finally { input.drain() }
    }

    @Test
    fun failedStageDoesNotReadOrReportSourceBytes() = runTest {
        val stored = InMemoryVolume(16, 2)
        var read = false
        val volume = object : Volume by stored {
            override suspend fun sync() { error("source sync failed") }
            override suspend fun read(lba: Long, count: Int): ByteBuffer {
                read = true
                return stored.read(lba, count)
            }
        }
        val input = DocumentInputElement.create(this, volume)
        try {
            assertFailsWith<IllegalStateException> { input.stage(0, byteArrayOf(1), "source") }
            assertFalse(read)
        } finally { input.drain() }
    }

    @Test
    fun drainWaitsForActiveAndQueuedReads() = runTest {
        val stored = InMemoryVolume(16, 2)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var reads = 0
        val volume = object : Volume by stored {
            override suspend fun read(lba: Long, count: Int): ByteBuffer {
                reads++
                entered.complete(Unit)
                release.await()
                return stored.read(lba, count)
            }
        }
        val input = DocumentInputElement.create(this, volume, capacity = 1)
        val extent = DocumentExtent(0, 1, "pending")
        val active = async(start = CoroutineStart.UNDISPATCHED) { input.read(extent) }
        entered.await()
        val queued = async(start = CoroutineStart.UNDISPATCHED) { input.read(extent) }
        val draining = async(start = CoroutineStart.UNDISPATCHED) { input.drain() }
        assertFalse(draining.isCompleted)
        assertFalse(active.isCompleted)
        assertFalse(queued.isCompleted)
        assertFailsWith<IllegalStateException> { input.read(extent) }
        release.complete(Unit)
        assertEquals(1, active.await().bytes.size)
        assertEquals(1, queued.await().bytes.size)
        draining.await()
        assertEquals(2, reads)
        assertTrue(input.job.isCompleted)
    }

    @Test
    fun readsExactExtentThroughVolumeAndJoinsAcceptedReads() = runTest {
        val stored = InMemoryVolume(16, 16)
        var reads = 0
        val volume = object : Volume by stored {
            override suspend fun read(lba: Long, count: Int): ByteBuffer {
                reads++
                return stored.read(lba, count)
            }
        }
        val bytes = "An exact document extent, excluding block padding.".encodeToByteArray()
        volume.write(2, ByteBuffer(bytes))
        val input = DocumentInputElement.create(this, volume, capacity = 1, blocksPerRead = 1)
        val extent = DocumentExtent(2, bytes.size, "contract.txt", expectedCid = ContentId.of(bytes))
        coroutineScope {
            val first = async { input.read(extent) }
            val second = async { input.read(extent) }
            assertContentEquals(bytes, first.await().bytes)
            assertContentEquals(bytes, second.await().bytes)
        }
        input.drain()
        assertEquals(8, reads)
        assertTrue(input.job.isCompleted)
        assertFailsWith<IllegalStateException> { input.read(extent) }
    }

    @Test
    fun invalidExtentDoesNotStopTheConsumer() = runTest {
        val volume = InMemoryVolume(16, 2)
        val input = DocumentInputElement.create(this, volume)
        try {
            assertFailsWith<IllegalArgumentException> { input.read(DocumentExtent(2, 1, "bad")) }
            assertEquals(0, input.read(DocumentExtent(2, 0, "empty")).bytes.size)
        } finally {
            input.drain()
        }
    }
}
