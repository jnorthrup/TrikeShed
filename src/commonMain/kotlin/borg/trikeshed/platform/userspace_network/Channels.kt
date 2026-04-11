package borg.literbike.userspace_network

import java.io.IOException
import java.net.InetSocketAddress
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicLong
import kotlin.annotation.Volatile

/**
 * Network channel abstractions for unified I/O operations
 */
object ChannelsModule {

    /**
     * Metadata about a channel
     */
    class ChannelMetadata(
        val remoteAddr: InetSocketAddress?,
        val localAddr: InetSocketAddress?,
        val protocol: ProtocolsModule.Protocol?,
        private val bytesReadVal: AtomicLong = AtomicLong(0),
        private val bytesWrittenVal: AtomicLong = AtomicLong(0)
    ) {
        companion object {
            fun defaultMetadata(): ChannelMetadata = ChannelMetadata(
                remoteAddr = null,
                localAddr = null,
                protocol = null
            )
        }

        fun bytesRead(): Long = bytesReadVal.get()

        fun bytesWritten(): Long = bytesWrittenVal.get()

        fun addBytesRead(n: Int) {
            bytesReadVal.addAndFetch(n.toLong())
        }

        fun addBytesWritten(n: Int) {
            bytesWrittenVal.addAndFetch(n.toLong())
        }
    }

    /**
     * Trait for network channels that support blocking I/O
     */
    interface Channel {
        fun channelType(): String
        fun isConnected(): Boolean
        fun metadata(): ChannelMetadata? = null
        fun read(buf: ByteArray): Result<Int>
        fun write(buf: ByteArray): Result<Int>
    }

    /**
     * Basic TCP channel implementation using blocking I/O
     */
    class TcpChannel(
        private val channel: SocketChannel,
        private val meta: ChannelMetadata
    ) : Channel {
        companion object {
            fun create(remote: InetSocketAddress): Result<TcpChannel> = runCatching {
                val ch = SocketChannel.open(remote).apply {
                    configureBlocking(true)
                }
                val localAddr = ch.localAddress as? InetSocketAddress
                TcpChannel(
                    channel = ch,
                    meta = ChannelMetadata(
                        remoteAddr = remote,
                        localAddr = localAddr,
                        protocol = null
                    )
                )
            }

            fun connect(addr: String): Result<TcpChannel> = runCatching {
                val parts = addr.split(":")
                val host = parts[0]
                val port = parts.getOrNull(1)?.toIntOrNull() ?: 80
                create(InetSocketAddress(host, port))
            }
        }

        override fun channelType(): String = "TCP"

        override fun isConnected(): Boolean = channel.isConnected

        override fun metadata(): ChannelMetadata? = meta

        override fun read(buf: ByteArray): Result<Int> = runCatching {
            val n = channel.read(java.nio.ByteBuffer.wrap(buf))
            if (n > 0) meta.addBytesRead(n)
            n
        }

        override fun write(buf: ByteArray): Result<Int> = runCatching {
            val n = channel.write(java.nio.ByteBuffer.wrap(buf))
            if (n > 0) meta.addBytesWritten(n)
            n
        }
    }

    /**
     * Provider for creating channels
     */
    interface ChannelProvider {
        fun createChannel(addr: String): Result<Channel>
        fun providerName(): String
    }

    /**
     * Default TCP channel provider
     */
    object TcpChannelProvider : ChannelProvider {
        override fun createChannel(addr: String): Result<Channel> = TcpChannel.connect(addr)

        override fun providerName(): String = "TCP"
    }
}
