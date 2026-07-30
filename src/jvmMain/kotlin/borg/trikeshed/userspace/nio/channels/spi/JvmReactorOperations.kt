package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.reactor.Interest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.time.Duration
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.spi.SelectorProvider as JdkSelectorProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * JVM implementation of [ReactorOperations] using Java NIO Selector.
 *
 * Single-threaded event loop: register interests -> select -> signal ready.
 * Designed to be driven by the coroutine scheduler via suspend poll().
 *
 * NO GLOBAL STATE - each instance owns its Selector and registry.
 * Thread-safe via ConcurrentHashMap; Selector runs on a dedicated thread.
 */
class JvmReactorOperations(
    private val selector: Selector = JdkSelectorProvider.provider().openSelector(),
    // Optional bridge to JvmChannelOperations so register(fd, interests) can
    // lazily pick up a channel that was created on the channel-ops side
    // (e.g. via accept()) without coupling the classes in their constructors.
    private val channelOpsBridge: (Int) -> java.nio.channels.SelectableChannel? = { null },
) : ReactorOperations {

    // fd -> (Channel, interests, userData)
    private val fdRegistry = ConcurrentHashMap<Int, RegistryEntry>()
    private val fdCounter = AtomicInteger(1000)

    override fun register(fd: Int, interests: Set<Interest>, userData: Long) {
        // Look up in our own registry first; if missing, try the bridge so
        // dynamically accepted fds become visible to the Selector.
        val existing = fdRegistry[fd]
        val channel = existing?.channel ?: channelOpsBridge(fd) ?: return
        val mask = Interest.toMask(interests)
        fdRegistry[fd] = RegistryEntry(channel, interests, userData)

        var ops = 0
        if (Interest.READ in interests) ops = ops or SelectionKey.OP_READ
        if (Interest.WRITE in interests) ops = ops or SelectionKey.OP_WRITE
        if (Interest.ACCEPT in interests) ops = ops or SelectionKey.OP_ACCEPT
        if (Interest.CONNECT in interests) ops = ops or SelectionKey.OP_CONNECT

        try {
            channel.register(selector, ops)
        } catch (e: Exception) {
            selector.keys().firstOrNull { it.channel() == channel }?.cancel()
            channel.register(selector, ops)
        }
    }

    override fun deregister(fd: Int) {
        fdRegistry.remove(fd)?.channel?.let { ch ->
            selector.keys().firstOrNull { it.channel() == ch }?.cancel()
        }
    }

    override suspend fun poll(timeout: Duration): List<ReactorSignal> {
        return withContext(Dispatchers.IO) {
            var result: List<ReactorSignal>? = null
            while (isActive && result == null) {
                result = suspendCancellableCoroutine { cont ->
                    cont.invokeOnCancellation {
                        selector.wakeup()
                    }

                    var n = 0
                    try {
                        if (timeout == Duration.ZERO) {
                            n = selector.selectNow()
                        } else {
                            // Constraint 1: Every blocking IO call has a JVM-level timeout.
                            // We use 1000ms max timeout if duration is infinite.
                            val ms = if (timeout.isInfinite()) 1000L else timeout.inWholeMilliseconds.coerceAtLeast(1L)
                            n = selector.select(ms)
                        }
                    } catch (e: Exception) {
                        if (cont.isActive) {
                            cont.resumeWithException(e)
                        }
                        return@suspendCancellableCoroutine
                    }

                    if (n == 0) {
                        if (cont.isActive) {
                            if (!timeout.isInfinite()) {
                                cont.resume(emptyList())
                            } else {
                                // Resume with null to loop again in the while loop
                                cont.resume(null)
                            }
                        }
                    } else {
                        val ready = selector.selectedKeys().mapNotNull { key ->
                            val fd = fdRegistry.entries.firstOrNull { it.value.channel == key.channel() }?.key
                                ?: return@mapNotNull null
                            val sig = mutableSetOf<Interest>()
                            if (key.isReadable) sig.add(Interest.READ)
                            if (key.isWritable) sig.add(Interest.WRITE)
                            if (key.isAcceptable) sig.add(Interest.ACCEPT)
                            if (key.isConnectable) sig.add(Interest.CONNECT)
                            val userData = fdRegistry[fd]?.userData ?: 0L
                            ReactorSignal(fd, sig, userData)
                        }
                        selector.selectedKeys().clear()
                        if (cont.isActive) {
                            cont.resume(ready)
                        }
                    }
                }
            }
            result ?: emptyList()
        }
    }

    fun bindChannel(ch: java.nio.channels.SelectableChannel, interests: Set<Interest>, userData: Long = 0L): Int {
        val fd = fdCounter.incrementAndGet()
        fdRegistry[fd] = RegistryEntry(ch, interests, userData)
        register(fd, interests, userData)
        return fd
    }

    private data class RegistryEntry(
        val channel: java.nio.channels.SelectableChannel,
        var interests: Set<Interest>,
        val userData: Long,
    )
}
