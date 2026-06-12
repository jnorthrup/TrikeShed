package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.reactor.Interest
import kotlin.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWith
import kotlin.time.Duration
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.spi.SelectorProvider as JdkSelectorProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.lang.Thread

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
    private val selector: Selector = java.nio.channels.spi.SelectorProvider.provider().openSelector(),
) : ReactorOperations {

    // fd -> (Channel, interests, userData)
    private val fdRegistry = ConcurrentHashMap<Int, RegistryEntry>()
    private val fdCounter = AtomicInteger(1000)

    override fun register(fd: Int, interests: Set<Interest>, userData: Long = 0L) {
        val entry = fdRegistry[fd] ?: return
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

    override suspend fun poll(timeout: Duration): List<ReactorSignal> = suspendCancellableCoroutine { cont ->
        val ms = if (timeout.isInfinite()) 0L else timeout.inWholeMilliseconds

        Thread(Runnable {
            var n: Int = 0
            try {
                n = if (ms == 0L) selector.selectNow() else selector.select(ms)
            } catch (e: Exception) {
                cont.resumeWith(kotlin.Result.failure(e))
                return
            }

            if (n == 0) {
                cont.resume(emptyList())
                return
            }

            val ready = selector.selectedKeys().mapNotNull { key ->
                val fdEntry = fdRegistry.entries.firstOrNull { it.value.channel == key.channel() }
                val fd = fdEntry?.key ?: return@mapNotNull null
                val sig = mutableSetOf<Interest>()
                if (key.isReadable) sig.add(Interest.READ)
                if (key.isWritable) sig.add(Interest.WRITE)
                if (key.isAcceptable) sig.add(Interest.ACCEPT)
                if (key.isConnectable) sig.add(Interest.CONNECT)
                val userData = fdRegistry[fd]?.userData ?: 0L
                ReactorSignal(fd, sig, userData)
            }.toList()
            selector.selectedKeys().clear()
            cont.resume(ready)
        }
    }

    /** Bind a raw channel to an fd for reactor tracking. Returns allocated fd. */
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