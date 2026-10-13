package borg.trikeshed.userspace

import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.sockaddrIpv4
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class JvmSocketConnectTest {
    private fun UserspaceChannelBackend.socket(): Int = submitBatch(listOf(
        UringSubmission(UringOp.SOCKET, 2, 0, 0, 1),
    )).single().res.also { assertTrue(it >= 0) }

    private fun connect(fd: Int, port: Int, token: Long): UringSubmission {
        val address = ByteBuffer(sockaddrIpv4(byteArrayOf(127, 0, 0, 1), port))
        return UringSubmission(UringOp.CONNECT, fd, 0, address.remaining(), 0, userData = token, buffer = address)
    }

    @Test
    fun loopbackConnectSettlesTheOriginalTokenBeforeTransferringBytes() = runTest {
        val backend = JvmUserspaceChannelBackend()
        try {
            ServerSocketChannel.open().use { server ->
                server.bind(InetSocketAddress("127.0.0.1", 0))
                val fd = backend.socket()
                val submission = connect(fd, (server.localAddress as InetSocketAddress).port, 37)
                val completion = withTimeout(5_000) { backend.batchEnqueue(1 j { _: Int -> submission })[0] }
                assertEquals(UringCompletion(37, 0, 0), completion)
                assertEquals(0, submission.buffer!!.position())
                assertTrue(backend.reapCompletions(0).isEmpty(), "CONNECT settles only once")
                assertTrue(backend.deferredCapabilities and UringOp.CONNECT.mask != 0L)
                assertEquals(0L, backend.nativeCapabilities and UringOp.CONNECT.mask)
                server.accept().use { peer ->
                    val data = ByteBuffer(byteArrayOf(42))
                    assertEquals(1, backend.submitBatch(listOf(UringSubmission(
                        UringOp.WRITE, fd, 0, 1, -1, userData = 38, buffer = data,
                    ))).single().res)
                    val received = java.nio.ByteBuffer.allocate(1)
                    assertEquals(1, peer.read(received))
                    assertEquals(42.toByte(), received.array()[0])
                }
            }
        } finally { backend.close() }
    }

    @Test
    fun ownerCancellationSettlesConnectWithoutDiscardingCallerSocket() = runTest {
        val backend = JvmUserspaceChannelBackend()
        val occupied = mutableListOf<SocketChannel>()
        var fd = -1
        try {
            ServerSocketChannel.open().use { server ->
                server.bind(InetSocketAddress("127.0.0.1", 0), 1)
                // Keep the local listen queue full without accepting or contacting an external peer.
                repeat(32) {
                    val client = SocketChannel.open()
                    occupied.add(client)
                    client.configureBlocking(false)
                    client.connect(server.localAddress)
                }
                fd = backend.socket()
                val owner = SupervisorJob(coroutineContext[Job])
                val ring = FunctionalUringFacade.create(CoroutineScope(coroutineContext + owner), 2, backend)
                try {
                    val submission = connect(fd, (server.localAddress as InetSocketAddress).port, 71)
                    val pending = async { ring.batchEnqueue(1 j { _: Int -> submission }) }
                    runCurrent()
                    assertFalse(pending.isCompleted, "the full local listen queue keeps CONNECT pending")
                    // Cancellation is an explicit abort by the owner, not normal drain semantics.
                    owner.cancel()
                    val draining = async { ring.drain() }
                    withTimeout(5_000) {
                        draining.await()
                        assertEquals(UringCompletion(71, -125, 0), pending.await()[0])
                    }
                    assertTrue(owner.isCompleted, "drain joins the cancelled owner's children")
                    assertTrue(FileImpl(fd).isOpen(), "the socket belongs to the caller, not the ring")
                    assertTrue(backend.reapCompletions(0).isEmpty(), "cancelled CONNECT cannot complete again")
                } finally { owner.cancel(); ring.drain() }
            }
        } finally {
            occupied.forEach { it.close() }
            if (fd >= 0) FileImpl(fd).close()
            backend.close()
        }
    }

    @Test
    fun targetedCancellationSettlesConnectAndHeldIoWithoutAbortingTheRing() = runTest {
        val backend = JvmUserspaceChannelBackend()
        val occupied = mutableListOf<SocketChannel>()
        val descriptors = mutableListOf<Int>()
        try {
            ServerSocketChannel.open().use { server ->
                server.bind(InetSocketAddress("127.0.0.1", 0), 1)
                repeat(32) {
                    val client = SocketChannel.open()
                    occupied.add(client)
                    client.configureBlocking(false)
                    client.connect(server.localAddress)
                }
                val firstFd = backend.socket().also(descriptors::add)
                val secondFd = backend.socket().also(descriptors::add)
                val port = (server.localAddress as InetSocketAddress).port
                val ring = FunctionalUringFacade.create(this, 4, backend)
                val destination = mapMemory(0, 65536, 3, 2 or 0x20, -1, 0)
                suspend fun cancel(target: Long, token: Long) = ring.batchEnqueue(1 j { _: Int ->
                    UringSubmission(UringOp.ASYNC_CANCEL, -1, target, 0, 0, userData = token)
                })[0]
                try {
                    val first = async { ring.batchEnqueue(1 j { _: Int -> connect(firstFd, port, 71) }) }
                    val second = async { ring.batchEnqueue(1 j { _: Int -> connect(secondFd, port, 72) }) }
                    destination[0] = 7
                    val read = async { ring.batchEnqueue(1 j { _: Int ->
                        UringSubmission(UringOp.READ, firstFd, destination.address, 1, -1, userData = 73, memory = destination)
                    }) }
                    runCurrent()
                    assertFalse(first.isCompleted)
                    assertFalse(second.isCompleted)
                    assertFalse(read.isCompleted, "READ waits behind the pending CONNECT")
                    assertFailsWith<IllegalStateException> { destination.close() }
                    assertEquals(UringCompletion(74, 0, 0), cancel(73, 74))
                    assertEquals(UringCompletion(73, -125, 0), read.await()[0])
                    assertEquals(7.toByte(), destination[0], "cancelled held IO must not write its destination")
                    destination.close()
                    assertFalse(destination.isOpen, "the original CQE releases the memory borrow")
                    assertEquals(UringCompletion(75, 0, 0), cancel(71, 75))
                    assertEquals(UringCompletion(71, -125, 0), first.await()[0])
                    assertFalse(second.isCompleted)
                    assertTrue(FileImpl(firstFd).isOpen())
                    assertTrue(FileImpl(secondFd).isOpen())
                    assertEquals(UringCompletion(76, 0, 0), ring.batchEnqueue(1 j { _: Int ->
                        UringSubmission(UringOp.NOP, -1, 0, 0, 0, userData = 76)
                    })[0], "the same ring continues accepting independent work")
                    assertEquals(UringCompletion(77, 0, 0), cancel(72, 77))
                    assertEquals(UringCompletion(72, -125, 0), second.await()[0])
                    assertTrue(ring.peek().isEmpty())
                } finally {
                    backend.cancelPending()
                    try { ring.drain() } finally { destination.close() }
                }
            }
        } finally {
            occupied.forEach { it.close() }
            descriptors.forEach { FileImpl(it).close() }
            backend.close()
        }
    }

    @Test
    fun cancellingConnectObservationPreservesTheDescriptorUntilClose() = runTest {
        val backend = JvmUserspaceChannelBackend()
        val occupied = mutableListOf<SocketChannel>()
        try {
            ServerSocketChannel.open().use { server ->
                server.bind(InetSocketAddress("127.0.0.1", 0), 1)
                repeat(32) {
                    val client = SocketChannel.open()
                    occupied.add(client)
                    client.configureBlocking(false)
                    client.connect(server.localAddress)
                }
                val fd = backend.socket()
                val submission = connect(fd, (server.localAddress as InetSocketAddress).port, 91)
                assertTrue(backend.submitBatch(listOf(submission)).isEmpty())
                assertTrue(backend.reapCompletions(0).isEmpty())
                assertEquals(SelectionResult(-2, 92), backend.submitBatch(listOf(UringSubmission(
                    UringOp.POLL_REMOVE, fd, 91, 0, 0, userData = 92,
                ))).single(), "POLL_REMOVE cannot cancel a different opcode")
                backend.cancelPending()
                backend.cancelPending()
                assertTrue(FileImpl(fd).isOpen(), "cancellation preserves the returned descriptor identity")
                assertEquals(listOf(SelectionResult(-125, 91)), backend.reapCompletions(0))
                assertTrue(backend.reapCompletions(0).isEmpty())
                assertEquals(SelectionResult(-125, 93), backend.submitBatch(listOf(
                    connect(fd, (server.localAddress as InetSocketAddress).port, 93),
                )).single())
                assertEquals(SelectionResult(0, 94), backend.submitBatch(listOf(UringSubmission(
                    UringOp.CLOSE, fd, 0, 0, 0, userData = 94,
                ))).single())
                assertFalse(FileImpl(fd).isOpen())
            }
        } finally {
            occupied.forEach { it.close() }
            backend.close()
        }
    }
}
