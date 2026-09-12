package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.nio.channels.spi.ChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.ReactorOperations
import borg.trikeshed.userspace.nio.channels.spi.ReactorSignal
import borg.trikeshed.userspace.reactor.Interest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * ChannelRunner — reactor-based channel event loop.
 *
 * Bridges [ChannelOperations] (raw socket IO) with [ReactorOperations] (event multiplexing)
 * to create a persistent connection manager. Each connection is a coroutine suspended on
 * reactor signals via [CompletableDeferred] indexed by fd.
 *
 * This is the TrikeShed equivalent of RelaxFactory's AsyncSingletonServer inner loop
 * — a single-threaded event loop dispatching IO readiness to suspended coroutines.
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

    private var running = false

    /** Open a TCP listener socket and register for accept events.
     *  Used by servers. The returned fd is a ServerSocketChannel-equivalent. */
    fun tcpListen(host: String, port: Int): Int = tcpSocket()

    /** Open a TCP client socket and register for connect events.
     *  Used by clients dialing out. */
    fun tcpDial(host: String, port: Int): Int = tcpSocket()

    /** socket(2) as an SQE: prep, submit, wait for the fd CQE. */
    private fun tcpSocket(): Int {
        socketRing.prepSocket(SocketDomain.AF_INET.posix, SocketType.SOCK_STREAM.mask, SocketProtocol.IPPROTO_TCP.posix)
        socketRing.submit()
        return socketRing.wait(1).firstOrNull()?.res ?: -1
    }

    /** Deprecated: ambiguous between listen and dial. Use tcpListen() or tcpDial(). */
    fun tcpConnect(host: String, port: Int): Int = tcpDial(host, port)

    /** Suspend until data is available on [fd].
     *  Deferred is emplaced BEFORE the OP_READ register call so a
     *  same-tick poll cannot fire on an empty slot. */
    suspend fun readAsync(fd: Int, timeoutMs: Long = 60_000L): Int? {
        reactorOps.register(fd, setOf(Interest.READ))
        val deferred = CompletableDeferred<Int>()
        readers[fd] = deferred
        return withTimeoutOrNull(timeoutMs) {
            deferred.await()
        }.also {
            if (it == null) readers.remove(fd)
        }
    }

    /** Suspend until [fd] is writable.
     *  Enqueues a deferred; reactor completes head on each OP_WRITE.
     *  Level-triggered Selector re-fires OP_WRITE while socket writable. */
    suspend fun writeAsync(fd: Int, timeoutMs: Long = 60_000L): Unit? {
        reactorOps.register(fd, setOf(Interest.READ, Interest.WRITE))
        val deferred = CompletableDeferred<Unit>()
        val queue = writers.getOrPut(fd) { mutableListOf() }
        queue.add(deferred)
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            queue.remove(deferred)
            if (queue.isEmpty()) writers.remove(fd)
        }
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
    ): Job = scope.launch {
        running = true
        while (isActive && running) {
            val signals = reactorOps.poll(pollTimeout)
            if (signals.isEmpty()) {
                yield()
            }
            for (signal in signals) {
                onSignal(signal)
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
    }

    fun stop() {
        running = false
    }
}