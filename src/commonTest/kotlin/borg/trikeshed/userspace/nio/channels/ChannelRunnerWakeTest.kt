package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.spi.*
import borg.trikeshed.userspace.reactor.Interest
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelRunnerWakeTest {
    @Test
    fun idlePollParksUntilStopAndDrainJoinsObservationCleanup() = runTest {
        val operations = Operations()
        val cleanup = CompletableDeferred<Unit>()
        val reactor = Reactor(cleanup)
        val runner = ChannelRunner(operations, reactor)
        runner.run(this)
        try {
            runCurrent()
            assertEquals(listOf(Duration.INFINITE), reactor.timeouts)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(1, reactor.timeouts.size, "An idle runner has no periodic poll wakeup")
            repeat(32) { runner.stop() }
            val drained = async { runner.drain() }
            runCurrent()
            assertTrue(reactor.cleanupEntered.isCompleted)
            assertFalse(drained.isCompleted)
            assertFalse(operations.closed)
            cleanup.complete(Unit)
            drained.await()
            assertEquals(0, reactor.active)
            assertTrue(operations.closed)
        } finally {
            cleanup.complete(Unit)
            runner.drain()
        }
    }

    @Test
    fun stopDrainsAcceptedReadinessBeforeClosingDescriptors() = runTest {
        val events = mutableListOf<String>()
        val operations = Operations(events)
        val reactor = Reactor()
        val runner = ChannelRunner(operations, reactor)
        val fd = runner.tcpListen("127.0.0.1", 0)
        val dispatch = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val accepted = List(33) { ReactorSignal(fd, setOf(Interest.READ), it.toLong()) }
        reactor.ready.send(accepted)
        runner.run(this) { signal ->
            events.add("signal:${signal.userData}")
            if (signal.userData == 0L) {
                dispatch.complete(Unit)
                release.await()
            }
        }
        try {
            dispatch.await()
            runner.stop()
            val drained = async { runner.drain() }
            runCurrent()
            assertFalse(drained.isCompleted, "Drain owns signals beyond the bounded delivery channel")
            assertTrue(operations.closedDescriptors.isEmpty())
            release.complete(Unit)
            drained.await()
            assertEquals(accepted.map { "signal:${it.userData}" } + "close:$fd", events)
            assertEquals(listOf(fd), operations.closedDescriptors)
            assertTrue(operations.closed)
        } finally {
            release.complete(Unit)
            runner.drain()
        }
    }

    @Test
    fun parentCancellationDrainsReadinessAlreadyReturnedByPoll() = runTest {
        val operations = Operations()
        val reactor = Reactor()
        val runner = ChannelRunner(operations, reactor)
        val owner = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + owner)
        val release = CompletableDeferred<Unit>()
        val dispatch = CompletableDeferred<Unit>()
        val delivered = mutableListOf<Long>()
        reactor.ready.send(List(33) { ReactorSignal(41, setOf(Interest.READ), it.toLong()) })
        runner.run(scope) { signal ->
            delivered.add(signal.userData)
            if (signal.userData == 0L) { dispatch.complete(Unit); release.await() }
        }
        try {
            dispatch.await()
            owner.cancel()
            val drained = async { runner.drain() }
            runCurrent()
            assertFalse(drained.isCompleted)
            release.complete(Unit)
            drained.await()
            owner.join()
            assertEquals(List(33) { it.toLong() }, delivered)
            assertTrue(operations.closed)
        } finally {
            release.complete(Unit)
            runner.drain()
            owner.cancelAndJoin()
        }
    }

    private class Reactor(val cleanup: CompletableDeferred<Unit> = CompletableDeferred(Unit)) : ReactorOperations {
        val ready = Channel<List<ReactorSignal>>(1)
        val timeouts = mutableListOf<Duration>()
        val cleanupEntered = CompletableDeferred<Unit>()
        var active = 0
        override fun register(fd: Int, interests: Set<Interest>, userData: Long) {}
        override fun deregister(fd: Int) {}
        override suspend fun poll(timeout: Duration): List<ReactorSignal> {
            timeouts.add(timeout)
            active++
            try {
                return ready.receive()
            } finally {
                withContext(NonCancellable) {
                    cleanupEntered.complete(Unit)
                    cleanup.await()
                    active--
                }
            }
        }
    }

    private class Operations(val events: MutableList<String> = mutableListOf()) : ChannelOperations {
        var closed = false
        val closedDescriptors = mutableListOf<Int>()
        override fun resolve(host: String) = byteArrayOf(127, 0, 0, 1)
        override fun openChannel(entries: Int): ChannelOperations.ChannelHandle = object : ChannelOperations.ChannelHandle {
            override val id = 1
            var result = 0
            var closing: Int? = null
            override fun read(buffer: ByteBuffer, offset: Long) = -95
            override fun write(buffer: ByteBuffer, offset: Long) = -95
            override fun prepSocket(domain: Int, type: Int, protocol: Int, userData: Long): Int {
                result = 41
                return 0
            }
            override fun prepBind(fd: Int, address: ByteBuffer, userData: Long): Int { result = 0; return 0 }
            override fun prepListen(fd: Int, backlog: Int, userData: Long): Int { result = 0; return 0 }
            override fun prepClose(fd: Int, userData: Long): Int { closing = fd; result = 0; return 0 }
            override fun submit() = 1
            override fun wait(minComplete: Int): List<ChannelResult> {
                closing?.let { closedDescriptors.add(it); events.add("close:$it") }
                closing = null
                return listOf(ChannelResult(41, result, 0))
            }
            override fun close() { closed = true }
        }
    }
}
