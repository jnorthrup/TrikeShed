package borg.trikeshed.userspace

import borg.trikeshed.lib.*
import borg.trikeshed.context.Synchronization
import borg.trikeshed.userspace.UringOp.Companion.Submissions
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.sockaddrIpv4
import borg.trikeshed.userspace.nio.channels.spi.JvmReactorOperations
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import borg.trikeshed.userspace.reactor.Interest
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import kotlinx.coroutines.*
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class JvmReactorWakeTest {
    @Test
    fun registrationWakesEmptyPollAndCancellationJoinsReadinessCleanup() = runBlocking {
        reactorWake(Synchronization.MONITOR)
    }

    @Test
    fun ownerSerializedReactorPreservesReadinessAndWaitCleanup() = runBlocking {
        reactorWake(Synchronization.NONE)
    }

    @Test
    fun nioSupervisorPassesTheSelectedSynchronizationToItsReactor() = runBlocking {
        for (synchronization in Synchronization.entries) {
            val nio = NioSupervisor(reactorSynchronization = synchronization)
            try {
                nio.open()
                assertEquals(synchronization, assertNotNull(nio.service<JvmReactorOperations>()).synchronization)
            } finally { nio.drain() }
        }
    }

    private suspend fun CoroutineScope.reactorWake(synchronization: Synchronization) {
        val backend = JvmUserspaceChannelBackend()
        val reactor = JvmReactorOperations(synchronization)
        var fd = -1
        try {
            ServerSocketChannel.open().use { server ->
                server.bind(InetSocketAddress("127.0.0.1", 0))
                fd = backend.submitBatch(listOf(UringSubmission(UringOp.SOCKET, 2, 0, 0, 1))).single().res
                assertTrue(fd >= 0)
                val address = sockaddrIpv4(byteArrayOf(127, 0, 0, 1), (server.localAddress as InetSocketAddress).port)
                assertEquals(0, withTimeout(5_000) { backend.batchEnqueue(s_[Submissions.connect(fd, address, 1)])[0].res })
                server.accept().use { peer ->
                    val empty = async(start = CoroutineStart.UNDISPATCHED) { reactor.poll(Duration.INFINITE) }
                    assertFalse(empty.isCompleted)
                    peer.write(java.nio.ByteBuffer.wrap(byteArrayOf(42)))
                    if (synchronization == Synchronization.MONITOR) withContext(Dispatchers.Default) {
                        reactor.register(fd, setOf(Interest.READ), 19)
                    } else reactor.register(fd, setOf(Interest.READ), 19)
                    val signal = withTimeout(5_000) { empty.await() }.single()
                    assertEquals(fd, signal.fd)
                    assertEquals(setOf(Interest.READ), signal.ready)
                    assertEquals(19L, signal.userData)
                    val received = ByteBuffer(1)
                    assertEquals(1, backend.submitBatch(listOf(Submissions.read(fd, 0, 1, -1, 2).copy(buffer = received))).single().res)
                    assertEquals(42.toByte(), received.array()[0], "Readiness must leave the payload for its reader")

                    val waiting = async(start = CoroutineStart.UNDISPATCHED) { reactor.poll(Duration.INFINITE) }
                    assertFalse(waiting.isCompleted)
                    withTimeout(5_000) { waiting.cancelAndJoin() }
                    assertTrue(waiting.isCancelled)
                    reactor.deregister(fd)
                    assertTrue(withTimeout(5_000) { reactor.poll(20.milliseconds) }.isEmpty())
                    withTimeout(5_000) { reactor.drain() }
                    assertTrue(FileImpl(fd).isOpen(), "The reactor owns observation, while the caller owns the descriptor")
                }
            }
        } finally {
            try { reactor.drain() }
            finally {
                if (fd >= 0) FileImpl(fd).close()
                backend.close()
            }
        }
    }
}
