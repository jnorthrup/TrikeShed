@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.userspace

import borg.trikeshed.lib.*
import borg.trikeshed.context.Synchronization
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.spi.PosixReactorOperations
import borg.trikeshed.userspace.reactor.Interest
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.posix.*
import kotlin.test.*
import kotlin.time.Duration

class PosixCompletionWakeTest {
    @Test fun emptyReactorWakesForRegistrationAndInfinitePollCanBeStopped() = runBlocking {
        reactorWake(Synchronization.MONITOR)
    }

    @Test fun ownerSerializedReactorPreservesReadinessAndWaitCleanup() = runBlocking {
        reactorWake(Synchronization.NONE)
    }

    private suspend fun CoroutineScope.reactorWake(synchronization: Synchronization) {
        val sockets = memScoped {
            val fds = allocArray<IntVar>(2)
            check(socketpair(AF_UNIX, SOCK_STREAM, 0, fds) == 0)
            fds[0] j fds[1]
        }
        val reactor = PosixReactorOperations(synchronization)
        try {
            val empty = async(start = CoroutineStart.UNDISPATCHED) { reactor.poll(Duration.INFINITE) }
            assertFalse(empty.isCompleted, "An empty reactor must suspend instead of spinning")
            byteArrayOf(7).usePinned { check(write(sockets.b, it.addressOf(0), 1.convert()) == 1L) }
            if (synchronization == Synchronization.MONITOR) withContext(Dispatchers.Default) {
                reactor.register(sockets.a, setOf(Interest.READ), 19)
            } else reactor.register(sockets.a, setOf(Interest.READ), 19)
            val signal = withTimeout(5_000) { empty.await() }.single()
            assertEquals(sockets.a, signal.fd)
            assertEquals(setOf(Interest.READ), signal.ready)
            assertEquals(19L, signal.userData)
            // The readiness wait leaves the byte for the descriptor's operation.
            val bytes = ByteArray(1)
            bytes.usePinned { check(read(sockets.a, it.addressOf(0), 1.convert()) == 1L) }
            assertEquals(7.toByte(), bytes[0])
            reactor.deregister(sockets.a)
            val stopped = async(start = CoroutineStart.UNDISPATCHED) { reactor.poll(Duration.INFINITE) }
            assertFalse(stopped.isCompleted)
            withTimeout(5_000) { stopped.cancelAndJoin() }
            assertTrue(stopped.isCancelled)
            assertTrue(reactor.poll(Duration.ZERO).isEmpty())
        } finally { close(sockets.a); close(sockets.b) }
    }

    @Test fun socketReadinessPersistsUntilReapAndCancelledWaitReleasesItsPipe() = runBlocking {
        val sockets = memScoped {
            val fds = allocArray<IntVar>(2)
            check(socketpair(AF_UNIX, SOCK_STREAM, 0, fds) == 0)
            fds[0] j fds[1]
        }
        val backend = openUserspaceChannelBackend(4, UringBackendMode.EMULATED)
        try {
            val receive = UringSubmission(UringOp.RECV, sockets.a, 0, 1, 0,
                userData = 1, buffer = ByteBuffer.allocate(1))
            assertTrue(backend.submitBatch(listOf(receive)).isEmpty())
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { backend.awaitCompletions() }
            assertFalse(waiting.isCompleted)
            byteArrayOf(42).usePinned { check(write(sockets.b, it.addressOf(0), 1.convert()) == 1L) }
            withTimeout(5_000) { waiting.await() }
            // Waiting again observes the same unread readiness and cannot consume the application CQE.
            withTimeout(5_000) { backend.awaitCompletions() }
            val completion = backend.reapCompletions(0).single()
            assertEquals(1, completion.res)
            assertEquals(1L, completion.userData)
            assertEquals(42.toByte(), receive.buffer!!.array()[0])

            assertTrue(backend.submitBatch(listOf(receive.copy(userData = 2,
                buffer = ByteBuffer.allocate(1)))).isEmpty())
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) { backend.awaitCompletions() }
            assertFalse(cancelled.isCompleted)
            withTimeout(5_000) { cancelled.cancelAndJoin() }
            // Cancellation of observation leaves the receive alive; backend cancellation owns its terminal CQE.
            assertTrue(backend.reapCompletions(0).isEmpty())
            backend.cancelPending()
            assertEquals(SelectionResult(-125, 2), backend.reapCompletions(1).single())
        } finally {
            backend.cancelPending(); backend.close()
            close(sockets.a); close(sockets.b)
        }
    }
}
