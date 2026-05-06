package borg.trikeshed.userspace.network

import kotlin.concurrent.Volatile

/**
 * Small common network surface aligned to NIO naming.
 */

class ChannelMetadata(
    val remoteAddr: String? = null,
    val localAddr: String? = null,
    var protocol: Protocol? = null
) {
    @Volatile var bytesRead: Long = 0
    @Volatile var bytesWritten: Long = 0
}

interface Channel {
    fun channelType(): String
    fun isConnected(): Boolean
    fun metadata(): ChannelMetadata?
    fun read(buf: ByteArray): Int
    fun write(buf: ByteArray): Int
}

interface Channels {
    fun open(addr: String): Channel
    fun providerName(): String
}

@Deprecated("Use Channels.", ReplaceWith("Channels"))
typealias ChannelProvider = Channels
