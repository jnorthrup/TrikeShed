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

    /** Map of fd → pending write deferred. */
    private val writers = mutableMapOf<Int, CompletableDeferred<Unit>>()

    private var running = false

    /** Open a TCP listener socket and register for accept events.
     *  Used by servers. The returned fd is a ServerSocketChannel-equivalent. */
    fun tcpListen(host: String, port: Int): Int {
        val fd = channelOps.socket(2 /* AF_INET */, 1 /* SOCK_STREAM */, 0)
        reactorOps.register(fd, setOf(Interest.ACCEPT))
        return fd
    }

    /** Open a TCP client socket and register for connect events.
     *  Used by clients dialing out. */
    fun tcpDial(host: String, port: Int): Int {
        val fd = channelOps.socket(2 /* AF_INET */, 1 /* SOCK_STREAM */, 0)
        reactorOps.register(fd, setOf(Interest.CONNECT))
        return fd
    }

    /** Deprecated: ambiguous between listen and dial. Use tcpListen() or tcpDial(). */
    fun tcpConnect(host: String, port: Int): Int = tcpDial(host, port)

    /** Suspend until data is available on [fd].
     *  Deferred is emplaced BEFORE the OP_READ register call so a
     *  same-tick poll cannot fire on an empty slot. */
    suspend fun readAsync(fd: Int): Int {
        reactorOps.register(fd, setOf(Interest.READ))
        val deferred = CompletableDeferred<Int>()
        readers[fd] = deferred
        return deferred.await()
    }

    /** Suspend until [fd] is writable.
     *  Cursor-wakes any prior waiter before empacing a new deferred
     *  (one waiter per fd per OP_WRITE; the level-triggered JDK
     *  Selector re-fires OP_WRITE without an explicit re-register). */
    suspend fun writeAsync(fd: Int) {
        reactorOps.register(fd, setOf(Interest.READ, Interest.WRITE))
        writers.remove(fd)?.complete(Unit)
        val deferred = CompletableDeferred<Unit>()
        writers[fd] = deferred
        deferred.await()
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
            for (signal in signals) {
                onSignal(signal)
                if (Interest.READ in signal.ready) {
                    readers.remove(signal.fd)?.complete(1)
                }
                if (Interest.WRITE in signal.ready) {
                    writers.remove(signal.fd)?.complete(Unit)
                }
            }
        }
    }

    fun stop() {
        running = false
    }
}
