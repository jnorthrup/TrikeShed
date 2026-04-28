package borg.trikeshed.userspace.network

import kotlin.concurrent.Volatile

/**
 * Network channel abstractions ported from literbike.
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

interface ChannelProvider {
    fun createChannel(addr: String): Channel
    fun providerName(): String
}
