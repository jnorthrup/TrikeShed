package borg.trikeshed.userspace

import borg.trikeshed.lib.*
import borg.trikeshed.userspace.UringOp.Companion.Submissions
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import kotlin.test.*

class JvmUringBatchTest {
    private fun native(): UserspaceChannelBackend {
        val discovery = discoverJvmUringBackend(8)
        assumeTrue(discovery.backend != null, discovery.report.description)
        return requireNotNull(discovery.backend)
    }

    private fun collect(backend: UserspaceChannelBackend, count: Int): Map<Long, Int> {
        val results = LinkedHashMap<Long, Int>()
        while (results.size < count) for (completion in backend.reapCompletions(1)) {
            assertNull(results.put(completion.userData, completion.res), "duplicate CQE")
        }
        return results
    }

    private fun one(backend: UserspaceChannelBackend, sub: UringSubmission): Int {
        val immediate = backend.submitBatch(listOf(sub))
        return if (immediate.isEmpty()) collect(backend, 1).getValue(sub.userData) else immediate.single().res
    }

    @Test
    fun descriptorBarriersSpanSubmissionsAndCloseWaitsForTerminalCqes() {
        val backend = native()
        val path = Files.createTempFile("uring-admissions-", ".bin")
        try {
            val fd = one(backend, Submissions.openat(path.toString(), 2, 1))
            assertTrue(fd >= 0)
            val descriptor = JvmFileTable.descriptor(fd) as JvmNativeDescriptor
            val first = ByteBuffer("first".encodeToByteArray())
            val second = ByteBuffer("second".encodeToByteArray())
            assertTrue(backend.submitBatch(listOf(
                Submissions.write(fd, 0, first.remaining(), 0, 2).copy(buffer = first),
                Submissions.write(fd, 0, second.remaining(), 64, 3).copy(buffer = second),
            )).isEmpty())
            val read = ByteBuffer(5)
            assertTrue(backend.submitBatch(listOf(Submissions.fsync(fd, 4))).isEmpty())
            assertTrue(backend.submitBatch(listOf(Submissions.read(fd, 0, 5, 0, 5).copy(buffer = read))).isEmpty())
            assertTrue(backend.submitBatch(listOf(Submissions.close(fd, 6))).isEmpty())
            assertTrue(descriptor.isOpen(), "queued CLOSE must not release an active descriptor")
            assertEquals(mapOf(2L to 5, 3L to 6, 4L to 0, 5L to 5, 6L to 0), collect(backend, 5))
            assertContentEquals("first".encodeToByteArray(), read.array())
            assertEquals(5, read.position())
            assertFalse(descriptor.isOpen())
            assertNull(JvmFileTable.descriptor(fd))
        } finally {
            backend.close()
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun callerCloseRetainsOsDescriptorUntilOriginalCompletionIsReaped() {
        val backend = native()
        val path = Files.createTempFile("uring-descriptor-", ".bin")
        try {
            val fd = one(backend, Submissions.openat(path.toString(), 2, 10))
            val descriptor = JvmFileTable.descriptor(fd) as JvmNativeDescriptor
            val bytes = ByteBuffer("retained".encodeToByteArray())
            assertTrue(backend.submitBatch(listOf(Submissions.write(fd, 0, bytes.remaining(), 0, 11).copy(buffer = bytes))).isEmpty())
            assertEquals(0, JvmFileTable.close(fd))
            assertFalse(descriptor.isOpen())
            assertTrue(JvmUring.size(descriptor.fd) >= 0, "kernel descriptor is retained until its original CQE")
            assertEquals(mapOf(11L to 8), collect(backend, 1))
            assertEquals(-9L, JvmUring.size(descriptor.fd))
        } finally {
            backend.close()
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun fifoReadAdmissionAndReadinessCancellationLeaveOriginalBufferOwned() = runBlocking {
        val backend = native()
        val directory = Files.createTempDirectory("uring-fifo-")
        val fifo = directory.resolve("input")
        try {
            assertEquals(0, ProcessBuilder("mkfifo", fifo.toString()).start().waitFor())
            val fd = one(backend, Submissions.openat(fifo.toString(), 2, 20))
            val bytes = ByteBuffer(1)
            assertTrue(backend.submitBatch(listOf(Submissions.read(fd, 0, 1, -1, 21).copy(buffer = bytes))).isEmpty())
            assertTrue(backend.reapCompletions(0).isEmpty(), "blocking FIFO READ is admitted without waiting")
            backend.awaitCompletions() // Clear the admission wake before blocking on the original READ.
            val waiting = List(3) { launch(start = CoroutineStart.UNDISPATCHED) { backend.awaitCompletions() } }
            delay(20)
            assertTrue(waiting.any { it.isActive }, "readiness waits must remain pending for the blocked READ")
            withTimeout(2_000) { waiting.forEach { it.cancelAndJoin() } }
            assertTrue(backend.reapCompletions(0).isEmpty(), "readiness cancellation is not a terminal CQE")
            assertEquals(0, bytes.position())
            val cancel = UringSubmission(UringOp.ASYNC_CANCEL, fd = -1, addr = 21, len = 0, offset = 0, userData = 22)
            val immediate = backend.submitBatch(listOf(cancel))
            assertTrue(immediate.isEmpty())
            assertEquals(mapOf(21L to -125, 22L to 0), collect(backend, 2))
            assertEquals(0, one(backend, Submissions.close(fd, 23)))
        } finally {
            backend.close()
            Files.deleteIfExists(fifo)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun completionReadinessStaysLevelTriggeredUntilReaped() = runBlocking {
        val backend = native()
        try {
            val submissions = (0 until 8).map { Submissions.nop(Long.MIN_VALUE + it) }
            assertTrue(backend.submitBatch(submissions).isEmpty())
            withTimeout(2_000) {
                backend.awaitCompletions()
                backend.awaitCompletions()
            }
            assertEquals(submissions.associate { it.userData to 0 }, collect(backend, 8))
        } finally { backend.close() }
    }

    @Test
    fun closeJoinsDispatchedReadinessAndCancellation() = runBlocking {
        val directory = Files.createTempDirectory("uring-close-wait-")
        val fifo = directory.resolve("input")
        try {
            assertEquals(0, ProcessBuilder("mkfifo", fifo.toString()).start().waitFor())
            withTimeout(10_000) {
                repeat(32) {
                    val backend = native()
                    var fd = -1
                    try {
                        fd = one(backend, Submissions.openat(fifo.toString(), 2, 40))
                        val bytes = ByteBuffer(1)
                        assertTrue(backend.submitBatch(listOf(Submissions.read(fd, 0, 1, -1, 41).copy(buffer = bytes))).isEmpty())
                        backend.awaitCompletions()
                        val waiter = launch(start = CoroutineStart.UNDISPATCHED) { backend.awaitCompletions() }
                        if (it % 2 == 0) {
                            // The owner cannot run a dispatch-back continuation while synchronous close is joining it.
                            backend.close()
                            waiter.cancelAndJoin()
                        } else {
                            val closed = async(Dispatchers.Default) { backend.close() }
                            waiter.cancelAndJoin()
                            closed.await()
                        }
                        assertEquals(0, bytes.position())
                    } finally {
                        backend.close()
                        if (fd >= 0) JvmFileTable.close(fd)
                    }
                }
            }
        } finally {
            Files.deleteIfExists(fifo)
            Files.deleteIfExists(directory)
        }
    }
}
