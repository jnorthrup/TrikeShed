package borg.trikeshed.userspace

import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.sockaddrIpv4
import borg.trikeshed.userspace.nio.channels.sockaddrUnix
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class JvmSocketRingTest {
    private fun UserspaceChannelBackend.execute(submission: UringSubmission): Int = runBlocking {
        withTimeout(5_000) {
            batchEnqueue(1 j { _: Int -> submission })[0]
                .also { assertEquals(submission.userData, it.userData) }.res
        }
    }

    private inline fun socketPair(block: (UserspaceChannelBackend, Int, SocketChannel) -> Unit) {
        val backend = JvmUserspaceChannelBackend()
        var fd = -1
        try {
            ServerSocketChannel.open().use { server ->
                server.bind(InetSocketAddress("127.0.0.1", 0))
                fd = backend.execute(UringSubmission(UringOp.SOCKET, 2, 0, 0, 1))
                assertTrue(fd >= 0)
                val address = ByteBuffer(sockaddrIpv4(byteArrayOf(127, 0, 0, 1),
                    (server.localAddress as InetSocketAddress).port))
                assertEquals(0, backend.execute(UringSubmission(UringOp.CONNECT, fd, 0, address.remaining(), 0, buffer = address)))
                server.accept().use { peer -> block(backend, fd, peer) }
            }
        } finally {
            try { if (fd >= 0) FileImpl(fd).close() } finally { backend.close() }
        }
    }

    @Test
    fun pollAdmissionHasOneCompletionOnlyWhenReady() = runTest {
        socketPair { backend, fd, peer ->
            val ring = FunctionalUringFacade.create(this, 2, backend)
            try {
                val admitted = async {
                    ring.batchEnqueue(1 j { _: Int ->
                        UringSubmission(UringOp.POLL_ADD, fd, 0, 0, 0, userData = 0, operationFlags = 1)
                    })
                }
                runCurrent()
                assertFalse(admitted.isCompleted, "registration is not a completion")
                peer.write(java.nio.ByteBuffer.wrap(byteArrayOf(7)))
                val results = admitted.await()
                val cqe = (0 until results.a).map { results[it] }.first { it.userData == 0L }
                assertEquals(0L, cqe.userData)
                assertEquals(1, cqe.res and 1)
                assertTrue(ring.wait(0).isEmpty(), "a one-shot poll cannot complete twice")
            } finally { ring.drain() }
        }
    }

    @Test
    fun pollRemoveAndDrainSettleOriginalTokens() = runTest {
        socketPair { backend, fd, peer ->
            val ring = FunctionalUringFacade.create(this, 3, backend)
            try {
                // Both polls and the removal are admitted through the facade as one
                // batch; every CQE — including the removal's own token-2 completion —
                // comes back through the facade's completion path.
                val results = async {
                    ring.batchEnqueue(3 j { i: Int ->
                        when (i) {
                            0 -> UringSubmission(UringOp.POLL_ADD, fd, 0, 0, 0, userData = 0, operationFlags = 1)
                            1 -> UringSubmission(UringOp.POLL_ADD, fd, 0, 0, 0, userData = 1, operationFlags = 1)
                            else -> UringSubmission(UringOp.POLL_REMOVE, fd, 0, 0, 0, userData = 2)
                        }
                    })
                }
                runCurrent()
                val draining = async { ring.drain() }
                runCurrent()
                assertFalse(draining.isCompleted, "normal drain waits for the surviving poll")
                assertFalse(results.isCompleted)
                peer.write(java.nio.ByteBuffer.wrap(byteArrayOf(7)))
                draining.await()
                val settled = results.await()
                val byToken = (0 until settled.a).associate { settled[it].userData to settled[it].res }
                assertEquals(-125, byToken.getValue(0L), "removed poll settles ECANCELED")
                assertEquals(UringOp.POLLIN, byToken.getValue(1L) and UringOp.POLLIN, "the surviving poll completes on actual data")
                assertEquals(0, byToken.getValue(2L), "POLL_REMOVE itself completes successfully")
                assertTrue(ring.wait(0).isEmpty(), "every token accounted for exactly once")
            } finally { peer.shutdownOutput(); ring.drain() }
        }
    }

    @Test
    fun scopedDrainWaitsForPendingPollAndPreservesCallerSocket() = runTest {
        socketPair { backend, fd, peer ->
            val ring = FunctionalUringFacade.create(this, 2, backend)
            try {
                val submission = UringSubmission(UringOp.POLL_ADD, fd, 0, 0, 0, userData = 7, operationFlags = 1)
                val pending = async { ring.batchEnqueue(1 j { _: Int -> submission }) }
                runCurrent()
                assertFalse(pending.isCompleted, "an unread socket keeps the poll admitted")
                val draining = async { ring.drain() }
                runCurrent()
                assertFalse(draining.isCompleted)
                assertFailsWith<IllegalStateException> {
                    ring.batchEnqueue(1 j { _: Int -> submission.copy(userData = 8) })
                }
                peer.write(java.nio.ByteBuffer.wrap(byteArrayOf(42)))
                draining.await()
                val completion = pending.await()[0]
                assertEquals(7L, completion.userData)
                assertEquals(UringOp.POLLIN, completion.res and UringOp.POLLIN)
                assertTrue(FileImpl(fd).isOpen(), "the caller owns the socket after ring drain")
                assertTrue(backend.reapCompletions(0).isEmpty(), "the readiness settles once")
                // A separate ring may use the same live descriptor after the first ring joins and closes.
                val next = JvmUserspaceChannelBackend()
                try {
                    val received = ByteBuffer(1)
                    assertEquals(1, next.execute(UringSubmission(UringOp.READ, fd, 0, 1, -1, buffer = received)))
                    assertEquals(42, received.get(0).toInt())
                    assertEquals(1, next.execute(UringSubmission(UringOp.WRITE, fd, 0, 1, -1, buffer = ByteBuffer(byteArrayOf(7)))))
                    val reply = java.nio.ByteBuffer.allocate(1)
                    assertEquals(1, peer.read(reply))
                    assertEquals(7, reply.get(0).toInt())
                } finally { next.close() }
            } finally { peer.shutdownOutput(); ring.drain() }
        }
    }

    @Test
    fun cancellingOnePollPreservesOtherAdmissionsAndCompletedResults() = runTest {
        socketPair { backend, fd, peer ->
            val ring = FunctionalUringFacade.create(this, 4, backend)
            suspend fun cancel(target: Long, token: Long, flags: Int = 0) = ring.batchEnqueue(1 j { _: Int ->
                UringSubmission(UringOp.ASYNC_CANCEL, -1, target, 0, 0, userData = token, operationFlags = flags)
            })[0]
            try {
                val first = async { ring.batchEnqueue(1 j { _: Int -> UringOp.Companion.Submissions.pollAdd(fd, UringOp.POLLIN, 17) }) }
                val second = async { ring.batchEnqueue(1 j { _: Int -> UringOp.Companion.Submissions.pollAdd(fd, UringOp.POLLIN, 18) }) }
                runCurrent()
                assertFalse(first.isCompleted)
                assertFalse(second.isCompleted)
                assertEquals(UringCompletion(19, -95, 0), cancel(17, 19, flags = 1), "unsupported cancel flags must not widen cancellation")
                assertFalse(first.isCompleted)
                assertEquals(UringCompletion(100, -22, 0), ring.batchEnqueue(1 j { _: Int ->
                    UringSubmission(UringOp.ASYNC_CANCEL, -1, 17, 1, 0, userData = 100)
                })[0], "invalid cancellation must not settle the target")
                assertEquals(UringCompletion(101, -2, 0), cancel(999, 101))
                assertFalse(first.isCompleted)
                assertEquals(UringCompletion(20, 0, 0), cancel(17, 20))
                assertEquals(UringCompletion(17, -125, 0), first.await()[0])
                assertFalse(second.isCompleted, "cancelling one request must not drain the ring")
                assertEquals(UringCompletion(21, -2, 0), cancel(17, 21), "a settled token cannot complete again")
                peer.write(java.nio.ByteBuffer.wrap(byteArrayOf(7)))
                assertEquals(UringOp.POLLIN, second.await()[0].res and UringOp.POLLIN)
                assertEquals(UringCompletion(22, -2, 0), cancel(18, 22), "readiness already won the cancellation race")
                assertTrue(ring.peek().isEmpty())
                assertTrue(FileImpl(fd).isOpen())
            } finally { backend.cancelPending(); ring.drain() }
        }
    }

    @Test
    fun failedSynchronousSubmissionSettlesAdmittedPollBeforeClosing() = runTest {
        for (synchronousClose in listOf(false, true)) socketPair { effects, fd, peer ->
            val failure = IllegalStateException("transport failed after poll admission")
            var cancellations = 0
            var closes = 0
            val backend = object : UserspaceChannelBackend by effects {
                override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> {
                    val result = effects.submitBatch(submissions)
                    if (submissions.any { it.opcode == UringOp.POLL_ADD }) throw failure
                    return result
                }
                override fun cancelPending() { cancellations++; effects.cancelPending() }
                override fun reapCompletions(minComplete: Int): List<SelectionResult> {
                    // Bound the failing case: an unread peer cannot complete this poll.
                    // Cleanup must request cancellation before attempting a blocking reap.
                    if (minComplete > 0) check(cancellations > 0) { "failed submission was not aborted" }
                    return effects.reapCompletions(minComplete)
                }
                override fun close() { closes++; effects.close() }
            }
            val ring = FunctionalUringFacade(2, backend)
            try {
                ring.enqueue(UringSubmission(UringOp.POLL_ADD, fd, 0, 0, 0, userData = 17, operationFlags = UringOp.POLLIN))
                assertSame(failure, assertFailsWith<IllegalStateException> { ring.submit() })
                assertTrue(effects.reapCompletions(0).isEmpty(), "the admitted poll is still pending")
                assertFailsWith<IllegalStateException> { ring.enqueue(UringSubmission(UringOp.NOP, -1, 0, 0, 0, userData = 18)) }
                if (synchronousClose) ring.closeNow() else ring.drain()
                assertTrue(cancellations > 0)
                assertEquals(1, closes)
                assertEquals(listOf(SelectionResult(-125, 17)), ring.peek())
                assertTrue(ring.peek().isEmpty(), "the original poll settles exactly once")
                ring.closeNow()
                assertEquals(1, closes)
                assertTrue(FileImpl(fd).isOpen(), "a failed ring does not own the caller's socket")
                peer.write(java.nio.ByteBuffer.wrap(byteArrayOf(42)))
                val next = JvmUserspaceChannelBackend()
                try {
                    assertEquals(UringOp.POLLIN, next.execute(UringOp.Companion.Submissions.pollAdd(fd, UringOp.POLLIN, 23)) and UringOp.POLLIN)
                    val received = ByteBuffer(1)
                    assertEquals(1, next.execute(UringSubmission(UringOp.READ, fd, 0, 1, -1, buffer = received)))
                    assertEquals(42, received.get(0).toInt())
                } finally { next.close() }
            } finally {
                backend.cancelPending()
                ring.closeNow()
            }
        }
    }

    @Test
    fun cancelledBackendRejectsSubsequentPollAdmissionWithCancellationCqe() = socketPair { backend, fd, _ ->
        backend.cancelPending()
        backend.cancelPending()
        assertEquals(-125, backend.execute(UringSubmission(UringOp.POLL_ADD, fd, 0, 0, 0, userData = 7, operationFlags = 1)))
        assertTrue(backend.reapCompletions(0).isEmpty())
        assertEquals(0, backend.execute(UringSubmission(UringOp.NOP, -1, 0, 0, 0, userData = 8)))
    }

    @Test
    fun unixBindListenConnectPreserveDescriptorAndReturnRealEffects() {
        val directory = Files.createTempDirectory(Path.of("/tmp"), "uring-")
        val path = directory.resolve("socket")
        val address = ByteBuffer(sockaddrUnix(path.toString()))
        val backend = JvmUserspaceChannelBackend()
        try {
            assertEquals(-9, backend.execute(UringSubmission(UringOp.BIND, -1, 0, address.remaining(), 0, buffer = address)))
            val server = backend.execute(UringSubmission(UringOp.SOCKET, 1, 0, 0, 1))
            assertTrue(server >= 0)
            val descriptor = JvmFileTable.descriptor(server)
            assertEquals(0, backend.execute(UringSubmission(UringOp.BIND, server, 0, address.remaining(), 0, buffer = address)))
            assertTrue(Files.exists(path))
            assertEquals(0, address.position(), "sockaddr input remains borrowed without advancing")
            assertEquals(0, backend.execute(UringSubmission(UringOp.LISTEN, server, 0, 8, 0)))
            assertSame(descriptor, JvmFileTable.descriptor(server), "listen must preserve the bound OS descriptor")
            assertEquals(-11, backend.execute(UringSubmission(UringOp.ACCEPT, server, 0, 0, 0)))
            val client = backend.execute(UringSubmission(UringOp.SOCKET, 1, 0, 0, 1))
            assertTrue(client >= 0)
            assertEquals(0, backend.execute(UringSubmission(UringOp.CONNECT, client, 0, address.remaining(), 0, buffer = address)))
            val accepted = backend.execute(UringSubmission(UringOp.ACCEPT, server, 0, 0, 0))
            assertTrue(accepted >= 0)
            assertEquals(0, backend.execute(UringSubmission(UringOp.SHUTDOWN, client, 0, 1, 0)))
            val target = ByteBuffer(1)
            assertEquals(0, backend.execute(UringSubmission(UringOp.READ, accepted, 0, 1, -1, buffer = target)))
            assertEquals(0, target.position())
        } finally {
            backend.close()
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }
}
