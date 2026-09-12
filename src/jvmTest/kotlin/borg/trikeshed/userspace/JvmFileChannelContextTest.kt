package borg.trikeshed.userspace

import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.file.StandardOpenOption
import kotlinx.coroutines.*
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.test.*

/** Common facade effects with a single-thread caller; Java Files only owns the temporary fixture. */
@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class JvmFileChannelContextTest {
    private data class Completion(val operation: UringOp, val fd: Int, val result: Int)
    private class Trace : UringTrace {
        val completions = ConcurrentLinkedQueue<Completion>()
        override fun channel(id: Long, availability: String, nativeCapabilities: Long) = Unit
        override fun submit(channel: Long, submission: UringSubmission) = Unit
        override fun failed(channel: Long, submission: UringSubmission, failure: String) = Unit
        override fun complete(channel: Long, submission: UringSubmission, result: Int) {
            completions.add(Completion(submission.opcode, submission.fd, result))
        }
    }

    @Test fun synchronousFileChannelUsesCallerTraceWithoutAQueuedConsumer() {
        val path = Files.createTempFile("trikeshed-context-file-", ".bin")
        val trace = Trace()
        val expected = ByteArray(8192) { (it * 37).toByte() }
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "file-channel-context").apply { isDaemon = true } }
        try {
            executor.asCoroutineDispatcher().use { dispatcher ->
                runBlocking(dispatcher) {
                    val scope = CoroutineScope(coroutineContext + trace)
                    val channel = FileChannel.open(scope, path.toString(), setOf(
                        StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))
                    try {
                        val source = ByteBuffer(expected)
                        while (source.hasRemaining()) assertTrue(channel.write(source) > 0)
                        channel.force(true)
                        channel.position(0)
                        val destination = ByteBuffer(expected.size)
                        while (destination.hasRemaining()) assertTrue(channel.read(destination) > 0)
                        assertContentEquals(expected, destination.array())
                        assertEquals(-1, channel.read(ByteBuffer(1)))
                    } finally { channel.close() }
                    // Synchronous compatibility has no retained consumer job for the scope to join.
                    assertFalse(coroutineContext[Job]!!.children.any())
                }
            }
            assertContentEquals(expected, Files.readAllBytes(path))
            assertTrue(trace.completions.any { it.operation == UringOp.WRITE && it.result > 0 })
            assertTrue(trace.completions.any { it.operation == UringOp.READ && it.result > 0 })
            assertTrue(trace.completions.any { it.operation == UringOp.FSYNC && it.result == 0 })
            val descriptor = trace.completions.single { it.operation == UringOp.OPENAT && it.result >= 0 }.result
            assertEquals(1, trace.completions.count { it.operation == UringOp.CLOSE && it.fd == descriptor && it.result == 0 })
        } finally {
            executor.shutdownNow()
            Files.deleteIfExists(path)
        }
    }

    @Test fun ownerCancellationStillAllowsCloseAndDoesNotUnmapTheRegion() {
        val path = Files.createTempFile("trikeshed-context-mapping-", ".bin")
        val trace = Trace()
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "file-channel-mapping").apply { isDaemon = true } }
        try {
            executor.asCoroutineDispatcher().use { dispatcher ->
                runBlocking(dispatcher) {
                    val owner = SupervisorJob(coroutineContext[Job])
                    val scope = CoroutineScope(coroutineContext + owner + trace)
                    val channel = FileChannel.open(scope, path.toString(), setOf(StandardOpenOption.READ, StandardOpenOption.WRITE))
                    var mapping: MemoryMapping? = null
                    try {
                        val bytes = ByteBuffer(ByteArray(65536) { 17 })
                        while (bytes.hasRemaining()) assertTrue(channel.write(bytes) > 0)
                        val region = channel.map(FileChannel.MapMode.READ_WRITE, 0, 65536).also { mapping = it }
                        owner.cancel()
                        channel.close()
                        assertFalse(channel.isOpen())
                        assertTrue(region.isOpen)
                        region[9] = 42
                        region.sync()
                        assertEquals(42.toByte(), region[9])
                        val eventsBeforeRejectedOpen = trace.completions.size
                        assertFailsWith<CancellationException> {
                            FileChannel.open(scope, path.toString(), setOf(StandardOpenOption.READ))
                        }
                        assertEquals(eventsBeforeRejectedOpen, trace.completions.size)
                    } finally {
                        try { mapping?.close() }
                        finally { channel.close(); owner.complete(); owner.join() }
                    }
                    assertFalse(mapping!!.isOpen)
                }
            }
            assertEquals(42.toByte(), Files.readAllBytes(path)[9])
            val descriptor = trace.completions.single { it.operation == UringOp.OPENAT && it.result >= 0 }.result
            assertEquals(1, trace.completions.count { it.operation == UringOp.CLOSE && it.fd == descriptor && it.result == 0 })
        } finally {
            executor.shutdownNow()
            Files.deleteIfExists(path)
        }
    }
}
