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

    /** Open a TCP socket and register for read/write events. */
    fun tcpConnect(host: CharSequence, port: Int): Int {
        val fd = channelOps.socket(2 /* AF_INET */, 1 /* SOCK_STREAM */, 0)
        reactorOps.register(fd, setOf(Interest.READ, Interest.WRITE))
        return fd
    }

    /** Suspend until data is available on [fd]. */
    suspend fun readAsync(fd: Int): Int {
        val deferred = CompletableDeferred<Int>()
        readers[fd] = deferred
        return deferred.await()
    }

    /** Suspend until [fd] is writable. */
    suspend fun writeAsync(fd: Int) {
        reactorOps.register(fd, setOf(Interest.READ, Interest.WRITE))
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
