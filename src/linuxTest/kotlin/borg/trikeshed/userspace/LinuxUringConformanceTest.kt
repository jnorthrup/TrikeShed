@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.userspace

import kotlinx.cinterop.cstr
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import borg.trikeshed.lib.get
import borg.trikeshed.lib.toSeries
import borg.trikeshed.userspace.UringOp.Companion.Submissions
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import platform.posix.close
import platform.posix.mkstemp
import platform.posix.unlink
import platform.posix.AF_UNIX
import platform.posix.SOCK_STREAM
import platform.posix.socketpair
import platform.posix.write
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs the common assertions against the actual selected Linux adapter, when built on Linux. */
class LinuxUringConformanceTest {
    @Test
    fun returnedDescriptorSurvivesRingClose() = runBlocking {
        val backend = openUserspaceChannelBackend(8)
        var fd = -1
        try {
            assertTrue(backend.nativeCapabilities and UringOp.OPENAT.mask != 0L, backend.availability)
            fd = backend.batchEnqueue(listOf(
                Submissions.openat("/dev/null", flags = 0, userData = 1)
            ).toSeries())[0].res
            assertTrue(fd >= 0)
            backend.close()
            assertTrue(platform.posix.fcntl(fd, platform.posix.F_GETFD) >= 0,
                "closing a submission ring must preserve the descriptor returned to its caller")
        } finally {
            backend.close()
            if (fd >= 0) close(fd)
        }
    }

    @Test
    fun close_preserves_unreaped_terminal_results() {
        val backend = openUserspaceChannelBackend(8)
        try {
            assertTrue(backend.nativeCapabilities and UringOp.NOP.mask != 0L, backend.availability)
            val immediate = backend.submitBatch(listOf(Submissions.nop(1), Submissions.nop(2)))
            backend.close()
            val completed = immediate + backend.reapCompletions(0)
            assertEquals(2, completed.size)
            assertEquals(mapOf(1L to 0, 2L to 0), completed.associate { it.userData to it.res })
            assertTrue(backend.reapCompletions(0).isEmpty())
        } finally { backend.close() }
    }

    @Test
    fun pending_receive_allows_independent_completion_and_awaits_original_cancellation() = runBlocking {
        val backend = openUserspaceChannelBackend(8)
        val sockets = sockets()
        try {
            assertTrue(backend.nativeCapabilities and UringOp.RECV.mask != 0L, backend.availability)
            val buffer = ByteBuffer(1)
            val results = mutableMapOf<Long, Int>()
            fun collect(completions: List<SelectionResult>) {
                completions.forEach { assertEquals(null, results.put(it.userData, it.res)) }
            }
            collect(backend.submitBatch(listOf(
                UringSubmission(UringOp.RECV, sockets[0], 0, 1, 0, userData = 1, buffer = buffer),
                Submissions.nop(2),
            )))
            withTimeout(5_000) {
                while (2L !in results) { collect(backend.reapCompletions(0)); delay(1) }
            }
            assertEquals(0, results[2])
            assertFalse(1L in results)
            backend.cancelPending()
            withTimeout(5_000) {
                while (1L !in results) { collect(backend.reapCompletions(0)); delay(1) }
            }
            assertEquals(-125, results[1])
            assertEquals(0, buffer.position())
            assertTrue(backend.reapCompletions(0).isEmpty(), "cancellation control CQEs escaped")
        } finally {
            backend.cancelPending()
            backend.close()
            sockets.forEach { close(it) }
        }
    }

    @Test
    fun shared_buffer_receives_and_descriptor_close_keep_submission_order() = runBlocking {
        val backend = openUserspaceChannelBackend(8)
        val sockets = sockets()
        var receiverClosed = false
        try {
            val bytes = ByteArray(2)
            val buffer = ByteBuffer(bytes)
            val results = mutableMapOf<Long, Int>()
            fun collect(completions: List<SelectionResult>) {
                completions.forEach {
                    assertEquals(null, results.put(it.userData, it.res))
                    if (it.userData == 3L && it.res == 0) receiverClosed = true
                }
            }
            val receive = UringSubmission(UringOp.RECV, sockets[0], 0, 1, 0, userData = 1, buffer = buffer)
            collect(backend.submitBatch(listOf(receive, receive.copy(userData = 2), Submissions.close(sockets[0], 3))))
            assertTrue(results.isEmpty(), "a dependent close completed before either receive")
            byteArrayOf(17, 29).usePinned { assertEquals(2L, write(sockets[1], it.addressOf(0), 2u)) }
            withTimeout(5_000) {
                while (results.size < 3) { collect(backend.reapCompletions(0)); delay(1) }
            }
            assertEquals(mapOf(1L to 1, 2L to 1, 3L to 0), results)
            assertContentEquals(byteArrayOf(17, 29), bytes)
            assertEquals(2, buffer.position())
        } finally {
            backend.cancelPending()
            backend.close()
            if (!receiverClosed) close(sockets[0])
            close(sockets[1])
        }
    }

    @Test
    fun direct_batches_progress_independently_and_cancellation_settles_native_receive() = runBlocking {
        val backend = openUserspaceChannelBackend(8)
        val sockets = sockets()
        try {
            val buffer = ByteBuffer(1)
            val call = async(start = CoroutineStart.UNDISPATCHED) {
                backend.batchEnqueue(listOf(
                    UringSubmission(UringOp.RECV, sockets[0], 0, 1, 0, userData = 1, buffer = buffer)
                ).toSeries())
            }
            val siblingBuffer = ByteBuffer(1)
            val sibling = async(start = CoroutineStart.UNDISPATCHED) {
                backend.batchEnqueue(listOf(
                    UringSubmission(UringOp.RECV, sockets[0], 0, 1, 0, userData = 3, buffer = siblingBuffer)
                ).toSeries())
            }
            val independent = withTimeout(5_000) {
                backend.batchEnqueue(listOf(Submissions.nop(2)).toSeries())
            }
            assertEquals(0, independent[0].res)
            withTimeout(5_000) { call.cancelAndJoin() }
            assertEquals(0, buffer.position())
            assertFalse(sibling.isCompleted, "one caller's cancellation settled another caller's receive")
            byteArrayOf(43).usePinned { assertEquals(1L, write(sockets[1], it.addressOf(0), 1u)) }
            assertEquals(1, withTimeout(5_000) { sibling.await() }[0].res)
            assertContentEquals(byteArrayOf(43), siblingBuffer.array())
            assertTrue(backend.reapCompletions(0).isEmpty())
        } finally {
            backend.cancelPending()
            backend.close()
            sockets.forEach { close(it) }
        }
    }

    private fun sockets(): IntArray = memScoped {
        val descriptors = allocArray<IntVar>(2)
        check(socketpair(AF_UNIX, SOCK_STREAM, 0, descriptors) == 0)
        intArrayOf(descriptors[0], descriptors[1])
    }

    @Test
    fun selected_backend_file_contract() = runTest {
        val path = memScoped {
            val template = "/tmp/trikeshed-uring-XXXXXX".cstr.getPointer(this)
            val fd = mkstemp(template)
            assertTrue(fd >= 0, "disposable file creation failed")
            close(fd)
            template.toKString()
        }
        val backend = openUserspaceChannelBackend(8)
        try {
            println("uring selection: ${backend.availability}; capabilities=${backend.capabilities}; native=${backend.nativeCapabilities}")
            UringFileConformance.file(FunctionalUringFacade(8, backend), path)
        } finally {
            backend.close()
            unlink(path)
        }
    }
}
