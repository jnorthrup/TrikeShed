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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JvmSocketRingTest {
    private fun UserspaceChannelBackend.execute(submission: UringSubmission): Int =
        submitBatch(listOf(submission)).single().also { assertEquals(submission.userData, it.userData) }.res

    private inline fun socketPair(block: (UserspaceChannelBackend, Int, SocketChannel) -> Unit) {
        val backend = JvmUserspaceChannelBackend()
        try {
            ServerSocketChannel.open().use { server ->
                server.bind(InetSocketAddress("127.0.0.1", 0))
                val fd = backend.execute(UringSubmission(UringOp.SOCKET, 2, 0, 0, 1))
                assertTrue(fd >= 0)
                val address = ByteBuffer(sockaddrIpv4(byteArrayOf(127, 0, 0, 1),
                    (server.localAddress as InetSocketAddress).port))
                assertEquals(0, backend.execute(UringSubmission(UringOp.CONNECT, fd, 0, address.remaining(), 0, buffer = address)))
                server.accept().use { peer -> block(backend, fd, peer) }
            }
        } finally { backend.close() }
    }

    @Test
    fun pollAdmissionHasOneCompletionOnlyWhenReady() = socketPair { backend, fd, peer ->
        val ring = FunctionalUringFacade(2, backend)
        try {
            ring.enqueue(UringSubmission(UringOp.POLL_ADD, fd, 0, 0, 0, userData = 0, operationFlags = 1))
            assertEquals(1, ring.submit())
            assertTrue(ring.wait(0).isEmpty(), "registration is not a completion")
            peer.write(java.nio.ByteBuffer.wrap(byteArrayOf(7)))
            val cqe = ring.wait(1).single()
            assertEquals(0L, cqe.userData)
            assertEquals(1, cqe.res and 1)
            assertTrue(ring.wait(0).isEmpty(), "a one-shot poll cannot complete twice")
        } finally { ring.closeNow() }
    }

    @Test
    fun pollRemoveAndDrainSettleOriginalTokens() = socketPair { backend, fd, _ ->
        val ring = FunctionalUringFacade(3, backend)
        try {
            ring.enqueue(UringSubmission(UringOp.POLL_ADD, fd, 0, 0, 0, userData = 0, operationFlags = 1))
            ring.enqueue(UringSubmission(UringOp.POLL_ADD, fd, 0, 0, 0, userData = 1, operationFlags = 1))
            assertEquals(2, ring.submit())
            ring.enqueue(UringSubmission(UringOp.POLL_REMOVE, fd, 0, 0, 0, userData = 2))
            assertEquals(1, ring.submit())
            assertEquals(setOf(SelectionResult(0, 2), SelectionResult(-125, 0)), ring.wait(0).toSet())
            ring.closeNow()
            assertEquals(listOf(SelectionResult(-125, 1)), ring.wait(0))
            assertTrue(ring.wait(0).isEmpty())
        } finally { ring.closeNow() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun scopedDrainCancelsPendingPollBeforeJoiningConsumer() = runTest {
        socketPair { backend, fd, _ ->
            val ring = FunctionalUringFacade.create(this, 2, backend)
            try {
                val submission = UringSubmission(UringOp.POLL_ADD, fd, 0, 0, 0, userData = 7, operationFlags = 1)
                val pending = async { ring.batchEnqueue(1 j { _: Int -> submission }) }
                runCurrent()
                assertFalse(pending.isCompleted, "an unread socket keeps the poll admitted")
                val draining = async { ring.drain() }
                draining.await()
                assertEquals(UringCompletion(7, -125, 0), pending.await()[0])
                assertFalse(FileImpl(fd).isOpen())
            } finally { ring.drain() }
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
