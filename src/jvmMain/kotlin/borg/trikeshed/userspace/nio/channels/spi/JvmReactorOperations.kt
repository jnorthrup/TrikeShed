package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.reactor.Interest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.time.Duration
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.spi.SelectorProvider as JdkSelectorProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
) : ReactorOperations {

    // fd -> (Channel, interests, userData)
    private val fdRegistry = ConcurrentHashMap<Int, RegistryEntry>()
    private val fdCounter = AtomicInteger(1000)

    override fun register(fd: Int, interests: Set<Interest>, userData: Long) {
        val entry = fdRegistry[fd] ?: return
        val mask = Interest.toMask(interests)
        fdRegistry[fd] = RegistryEntry(entry.channel, interests, userData)

        var ops = 0
        if (Interest.READ in interests) ops = ops or SelectionKey.OP_READ
        if (Interest.WRITE in interests) ops = ops or SelectionKey.OP_WRITE
        if (Interest.ACCEPT in interests) ops = ops or SelectionKey.OP_ACCEPT
        if (Interest.CONNECT in interests) ops = ops or SelectionKey.OP_CONNECT

        try {
            entry.channel.register(selector, ops)
        } catch (e: Exception) {
            selector.keys().firstOrNull { it.channel() == entry.channel }?.cancel()
            entry.channel.register(selector, ops)
        }
    }

    override fun deregister(fd: Int) {
        fdRegistry.remove(fd)?.channel?.let { ch ->
            selector.keys().firstOrNull { it.channel() == ch }?.cancel()
        }
    }

    override suspend fun poll(timeout: Duration): List<ReactorSignal> {
        val ms = if (timeout.isInfinite()) 0L else timeout.inWholeMilliseconds

        return withContext(kotlinx.coroutines.Dispatchers.IO) {
            var n = 0
            try {
                n = if (ms == 0L) selector.selectNow() else selector.select(ms)
            } catch (e: Exception) {
                throw e
            }

            if (n == 0) {
                emptyList()
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
                ready
            }
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