package borg.trikeshed.userspace.kernel

import kotlin.time.Duration

/**
 * Small common reactor surface.
 */

interface SelectableChannelOps {
    suspend fun pollReadable(timeout: Duration? = null): Boolean
    suspend fun pollWritable(timeout: Duration? = null): Boolean
    fun tryRead(buf: ByteArray): Int
    fun tryWrite(buf: ByteArray): Int
}

interface Reactor {
    fun register(channel: SelectableChannelOps): Int
    fun unregister(id: Int)
    suspend fun tick(maxWait: Duration? = null): Int
    fun channelCount(): Int
}

class SimpleReactor : Reactor {
   val channels = mutableListOf<SelectableChannelOps?>()

    override fun register(channel: SelectableChannelOps): Int {
        channels.add(channel)
        return channels.size - 1
    }

    override fun unregister(id: Int) {
        if (id in channels.indices) {
            channels[id] = null
        }
    }

    override suspend fun tick(maxWait: Duration?): Int {
        var readyCount = 0
        for (channel in channels) {
            if (channel != null) {
                if (channel.pollReadable(Duration.ZERO)) readyCount++
                if (channel.pollWritable(Duration.ZERO)) readyCount++
            }
        }
        return readyCount
    }

    override fun channelCount(): Int = channels.count { it != null }
}

@Deprecated("Use SelectableChannelOps.", ReplaceWith("SelectableChannelOps"))
typealias NioChannel = SelectableChannelOps

@Deprecated("Use SelectableChannelOps.", ReplaceWith("SelectableChannelOps"))
typealias SelectableChannelReady = SelectableChannelOps
