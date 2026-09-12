package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.nio.channels.spi.ChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.ReactorOperations
import borg.trikeshed.userspace.nio.channels.spi.ReactorSignal
import borg.trikeshed.userspace.reactor.Interest
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.context.ElementState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * ChannelRunner — reactor-based channel event loop.
 *
 * Socket lifecycle and readiness use the channel and reactor SQE/CQE adapters.
 * A bounded signal channel joins the polling and dispatch stages under the
 * caller's job. [drain] settles the stages and closes owned descriptors and ring.
 */
class ChannelRunner(
    private val channelOps: ChannelOperations,
    private val reactorOps: ReactorOperations,
) {
    /** Map of fd → pending read deferred. */
    private val readers = mutableMapOf<Int, CompletableDeferred<Int>>()

    /** Map of fd → queue of pending write deferreds.
     *  Supports sustained single-coroutine writes (depth 1) and
     *  multi-coroutine contention (FIFO queue). */
    private val writers = mutableMapOf<Int, MutableList<CompletableDeferred<Unit>>>()

    /** The ring this runner creates sockets on; one handle, one CQE per SQE. */
    private val socketRing = channelOps.openChannel(16)
    private val descriptors = mutableSetOf<Int>()
    private var state = ElementState.OPEN
    private var supervisor: CompletableJob? = null
    private var polling: Job? = null
    private var dispatching: Job? = null
    private var dispatchFailure: Throwable? = null

    /** Open a TCP listener socket and register for accept events.
     *  Used by servers. The returned fd is a ServerSocketChannel-equivalent. */
    fun tcpListen(host: String, port: Int): Int {
        val address = channelOps.resolve(host)?.let { ByteBuffer(sockaddrIpv4(it, port)) } ?: return -2
        return tcpSocket { fd ->
            val bound = settle(socketRing.prepBind(fd, address))
            if (bound < 0) bound else settle(socketRing.prepListen(fd)).also {
                if (it == 0) reactorOps.register(fd, setOf(Interest.ACCEPT))
            }
        }
    }

    /** Open a TCP client socket and register for connect events.
     *  Used by clients dialing out. */
    fun tcpDial(host: String, port: Int): Int {
        val address = channelOps.resolve(host)?.let { ByteBuffer(sockaddrIpv4(it, port)) } ?: return -2
        return tcpSocket { fd -> settle(socketRing.prepConnect(fd, address)) }
    }

    /** socket(2) as an SQE: prep, submit, wait for the fd CQE. */
    private fun tcpSocket(configure: (Int) -> Int): Int {
        check(state < ElementState.DRAINING) { "Channel runner is draining or closed" }
        val fd = settle(socketRing.prepSocket(SocketDomain.AF_INET.posix, SocketType.SOCK_STREAM.mask, SocketProtocol.IPPROTO_TCP.posix))
        if (fd < 0) return fd
        descriptors.add(fd)
        try {
            val result = configure(fd)
            if (result == 0) return fd
            close(fd)
            return result
        } catch (failure: Throwable) {
            try { close(fd) } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
            throw failure
        }
    }

    private fun settle(prepared: Int): Int {
        if (prepared < 0) return prepared
        check(socketRing.submit() == 1) { "Socket SQE was not submitted" }
        return socketRing.wait(1).single().res
    }

    fun close(fd: Int) {
        reactorOps.deregister(fd)
        check(settle(socketRing.prepClose(fd)) == 0) { "Socket CLOSE failed for fd=$fd" }
        descriptors.remove(fd)
    }

    /** Deprecated: ambiguous between listen and dial. Use tcpListen() or tcpDial(). */
    fun tcpConnect(host: String, port: Int): Int = tcpDial(host, port)

    /** Suspend until data is available on [fd].
     *  Deferred is emplaced BEFORE the OP_READ register call so a
     *  same-tick poll cannot fire on an empty slot. */
    suspend fun readAsync(fd: Int, timeoutMs: Long = 60_000L): Int? {
        check(state < ElementState.DRAINING) { "Channel runner is draining or closed" }
        check(fd !in readers) { "A read is already pending for fd=$fd" }
        check(readers.size < 63) { "Read readiness capacity exceeded" }
        val deferred = CompletableDeferred<Int>()
        readers[fd] = deferred
        return try {
            register(fd)
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            if (readers[fd] === deferred) readers.remove(fd)
            register(fd)
        }
    }

    /** Suspend until [fd] is writable.
     *  Enqueues a deferred; reactor completes head on each OP_WRITE.
     *  Level-triggered Selector re-fires OP_WRITE while socket writable. */
    suspend fun writeAsync(fd: Int, timeoutMs: Long = 60_000L): Unit? {
        check(state < ElementState.DRAINING) { "Channel runner is draining or closed" }
        val deferred = CompletableDeferred<Unit>()
        check(fd in writers || writers.size < 63) { "Write readiness capacity exceeded" }
        val queue = writers.getOrPut(fd) { mutableListOf() }
        check(queue.size < 16) { "Write readiness queue is full for fd=$fd" }
        queue.add(deferred)
        return try {
            register(fd)
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            queue.remove(deferred)
            if (queue.isEmpty()) writers.remove(fd)
            register(fd)
        }
    }

    private fun register(fd: Int) {
        val interests = mutableSetOf<Interest>()
        if (fd in readers) interests.add(Interest.READ)
        if (writers[fd]?.isNotEmpty() == true) interests.add(Interest.WRITE)
        if (interests.isEmpty() || state >= ElementState.DRAINING) reactorOps.deregister(fd)
        else reactorOps.register(fd, interests)
    }

    /**
     * Launch the reactor event loop in [scope].
     * Polls [ReactorOperations] and dispatches readiness signals
     * to suspended coroutines via CompletableDeferred completion.
     */
    fun run(
        scope: CoroutineScope,
        pollTimeout: Duration = Duration.INFINITE,
        onSignal: suspend (ReactorSignal) -> Unit = {},
    ): Job {
        check(state == ElementState.OPEN) { "Channel runner is already running or closed" }
        val owner = SupervisorJob(requireNotNull(scope.coroutineContext[Job]) { "Channel runner requires an owning Job" })
        supervisor = owner
        state = ElementState.ACTIVE
        val signals = Channel<ReactorSignal>(16)
        val ownedScope = CoroutineScope(scope.coroutineContext + owner + channelOps + reactorOps)
        dispatching = ownedScope.launch {
            for (signal in signals) {
                try {
                    onSignal(signal)
                } catch (failure: Throwable) {
                    if (dispatchFailure == null) dispatchFailure = failure
                    stop()
                }
                if (Interest.READ in signal.ready) {
                    readers.remove(signal.fd)?.complete(1)
                }
                if (Interest.WRITE in signal.ready) {
                    val queue = writers[signal.fd]
                    if (queue != null && queue.isNotEmpty()) {
                        queue.removeAt(0).complete(Unit)
                    }
                }
            }
        }
        return ownedScope.launch {
            try {
                while (isActive && state == ElementState.ACTIVE) {
                    val ready = reactorOps.poll(minOf(pollTimeout, 10.milliseconds))
                    for (signal in ready) signals.send(signal)
                    if (ready.isEmpty()) yield()
                }
            } finally {
                signals.close()
            }
        }.also { polling = it }
    }

    fun stop() {
        if (state < ElementState.DRAINING) state = ElementState.DRAINING
    }

    suspend fun drain(): Unit = withContext(NonCancellable) {
        if (state == ElementState.CLOSED) return@withContext
        stop()
        polling?.join()
        dispatching?.join()
        val failure = IllegalStateException("Channel runner is drained")
        for ((fd, reader) in readers) {
            reactorOps.deregister(fd)
            reader.completeExceptionally(failure)
        }
        for ((fd, queue) in writers) {
            reactorOps.deregister(fd)
            for (writer in queue) writer.completeExceptionally(failure)
        }
        readers.clear()
        writers.clear()
        try {
            for (fd in descriptors.toTypedArray()) close(fd)
        } finally {
            try { socketRing.close() } finally {
                supervisor?.complete()
                supervisor?.join()
                state = ElementState.CLOSED
            }
        }
        dispatchFailure?.let { throw it }
    }
}
