package borg.trikeshed.userspace.network

import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.userspace.nio.spi.ByteRegion
import kotlin.concurrent.Volatile

/**
 * Small common network surface aligned to NIO naming.
 */

class ChannelMetadata(
    val remoteAddr: CharSequence? = null,
    val localAddr: CharSequence? = null,
    var protocol: Protocol? = null
) {
    @Volatile var bytesRead: Long = 0
    @Volatile var bytesWritten: Long = 0
}

interface Channel {
    fun channelType(): CharSequence
    fun isConnected(): Boolean
    fun metadata(): ChannelMetadata?
    fun read(dst: ByteRegion): Int
    fun write(src: ByteSeries): Int
}

interface Channels {
    fun open(addr: CharSequence): Channel
    fun providerName(): CharSequence
    }

@Deprecated("Use Channels.", ReplaceWith("Channels"))
typealias ChannelProvider = Channels
