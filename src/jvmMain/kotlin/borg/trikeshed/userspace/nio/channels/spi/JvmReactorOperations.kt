package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.reactor.Interest
import kotlin.time.Duration
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.spi.SelectorProvider as JdkSelectorProvider

class JvmReactorOperations : ReactorOperations {

    private val selector: Selector = JdkSelectorProvider.provider().openSelector()

    companion object {
        internal val channelToFd = mutableMapOf<java.nio.channels.SelectableChannel, Int>()
        internal val fdUserData = mutableMapOf<Int, Long>()
        internal val fdInterests = mutableMapOf<Int, Set<Interest>>()
        private var nextFd = 100

        fun bindChannel(ch: java.nio.channels.SelectableChannel, interests: Set<Interest>): Int {
            val fd = nextFd++
            channelToFd[ch] = fd
            fdInterests[fd] = interests
            return fd
        }
    }

    override fun register(fd: Int, interests: Set<Interest>, userData: Long) {
        fdInterests[fd] = interests
        fdUserData[fd] = userData
        val ch = channelToFd.entries.firstOrNull { it.value == fd }?.key ?: return
        var ops = 0
        if (Interest.READ in interests) ops = ops or SelectionKey.OP_READ
        if (Interest.WRITE in interests) ops = ops or SelectionKey.OP_WRITE
        if (Interest.ACCEPT in interests) ops = ops or SelectionKey.OP_ACCEPT
        if (Interest.CONNECT in interests) ops = ops or SelectionKey.OP_CONNECT
        ch.register(selector, ops)
    }

    override fun deregister(fd: Int) {
        val ch = channelToFd.entries.firstOrNull { it.value == fd }?.key
        if (ch != null) {
            selector.keys().find { it.channel() == ch }?.cancel()
        }
        fdUserData.remove(fd)
        fdInterests.remove(fd)
    }

    override suspend fun poll(timeout: Duration): List<ReactorSignal> {
        val ms = if (timeout.isInfinite()) 0L else timeout.inWholeMilliseconds
        val n = selector.select(ms)
        if (n == 0) return emptyList()
        val ready = selector.selectedKeys().mapNotNull { key ->
            val fd = channelToFd[key.channel()] ?: return@mapNotNull null
            val sig = mutableSetOf<Interest>()
            if (key.isReadable) sig.add(Interest.READ)
            if (key.isWritable) sig.add(Interest.WRITE)
            if (key.isAcceptable) sig.add(Interest.ACCEPT)
            if (key.isConnectable) sig.add(Interest.CONNECT)
            ReactorSignal(fd, sig, fdUserData[fd] ?: 0L)
        }
        selector.selectedKeys().clear()
        return ready
    }

    fun bindChannel(ch: java.nio.channels.SelectableChannel, interests: Set<Interest>): Int {
        return Companion.bindChannel(ch, interests)
    }
}
