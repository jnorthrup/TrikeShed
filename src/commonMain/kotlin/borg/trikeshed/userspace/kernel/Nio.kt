package borg.trikeshed.userspace.kernel

import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.userspace.ByteRegion
import kotlin.time.Duration

/**
 * Reactor-level readiness surface. Speaks ByteRegion/ByteSeries — no ByteArray calling conventions.
 * Readiness polling is separated from buffer transfer so callers control buffer lifecycle.
 */

interface SelectableChannelOps {
    suspend fun pollReadable(timeout: Duration? = null): Boolean
    suspend fun pollWritable(timeout: Duration? = null): Boolean
    fun tryRead(dst: ByteRegion): Int
    fun tryWrite(src: ByteSeries): Int
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
        if (id in channels.indices) channels[id] = null
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
