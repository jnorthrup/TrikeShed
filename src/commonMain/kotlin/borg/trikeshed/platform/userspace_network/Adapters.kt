package borg.literbike.userspace_network

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.channels.SocketChannel
import kotlin.annotation.Volatile

/**
 * Network protocol adapters for various transport protocols
 */
object NetworkAdaptersModule {

    /**
     * Type of network adapter
     */
    enum class AdapterType {
        Http, Https, Quic, Ssh, WebSocket, Raw
    }

    /**
     * Trait for network protocol adapters
     */
    interface NetworkAdapter {
        fun adapterType(): AdapterType
        fun remoteAddr(): Result<InetSocketAddress>
        fun isConnected(): Boolean
        fun close(): Result<Unit>
        fun read(buf: ByteArray): Result<Int>
        fun write(buf: ByteArray): Result<Int>
    }

    /**
     * HTTP adapter for HTTP/1.1 and HTTP/2 protocols
     */
    class HttpAdapter(
        private val channel: SocketChannel,
        private val remote: InetSocketAddress,
        @Volatile private var connected: Boolean = true
    ) : NetworkAdapter {
        companion object {
            fun create(remote: InetSocketAddress): Result<HttpAdapter> = runCatching {
                val channel = SocketChannel.open(remote).apply {
                    configureBlocking(true)
                }
                HttpAdapter(channel, remote)
            }
        }

        override fun adapterType(): AdapterType = AdapterType.Http

        override fun remoteAddr(): Result<InetSocketAddress> = Result.success(remote)

        override fun isConnected(): Boolean = connected

        override fun close(): Result<Unit> {
            connected = false
            return runCatching { channel.close() }
        }

        override fun read(buf: ByteArray): Result<Int> = runCatching {
            channel.read(java.nio.ByteBuffer.wrap(buf))
        }

        override fun write(buf: ByteArray): Result<Int> = runCatching {
            channel.write(java.nio.ByteBuffer.wrap(buf))
        }
    }

    /**
     * QUIC adapter for QUIC protocol
     */
    class QuicAdapter(
        private val channel: SocketChannel,
        private val remote: InetSocketAddress,
        private val streamId: Long,
        @Volatile private var connected: Boolean = true
    ) : NetworkAdapter {
        companion object {
            fun create(remote: InetSocketAddress, streamId: Long = 0): Result<QuicAdapter> = runCatching {
                val channel = SocketChannel.open(remote).apply {
                    configureBlocking(true)
                }
                QuicAdapter(channel, remote, streamId)
            }
        }

        fun getStreamId(): Long = streamId

        override fun adapterType(): AdapterType = AdapterType.Quic

        override fun remoteAddr(): Result<InetSocketAddress> = Result.success(remote)

        override fun isConnected(): Boolean = connected

        override fun close(): Result<Unit> {
            connected = false
            return runCatching { channel.close() }
        }

        override fun read(buf: ByteArray): Result<Int> = runCatching {
            channel.read(java.nio.ByteBuffer.wrap(buf))
        }

        override fun write(buf: ByteArray): Result<Int> = runCatching {
            channel.write(java.nio.ByteBuffer.wrap(buf))
        }
    }

    /**
     * SSH adapter for SSH protocol
     */
    class SshAdapter(
        private val channel: SocketChannel,
        private val remote: InetSocketAddress,
        private var sessionId: ByteArray = byteArrayOf(),
        @Volatile private var connected: Boolean = true
    ) : NetworkAdapter {
        companion object {
            fun create(remote: InetSocketAddress): Result<SshAdapter> = runCatching {
                val channel = SocketChannel.open(remote).apply {
                    configureBlocking(true)
                }
                SshAdapter(channel, remote)
            }
        }

        fun setSessionId(id: ByteArray) {
            sessionId = id.copyOf()
        }

        fun getSessionId(): ByteArray = sessionId.copyOf()

        override fun adapterType(): AdapterType = AdapterType.Ssh

        override fun remoteAddr(): Result<InetSocketAddress> = Result.success(remote)

        override fun isConnected(): Boolean = connected

        override fun close(): Result<Unit> {
            connected = false
            return runCatching { channel.close() }
        }

        override fun read(buf: ByteArray): Result<Int> = runCatching {
            channel.read(java.nio.ByteBuffer.wrap(buf))
        }

        override fun write(buf: ByteArray): Result<Int> = runCatching {
            channel.write(java.nio.ByteBuffer.wrap(buf))
        }
    }

    /**
     * Factory for creating network adapters
     */
    object AdapterFactory {
        fun createAdapter(
            adapterType: AdapterType,
            remote: InetSocketAddress
        ): Result<NetworkAdapter> = when (adapterType) {
            AdapterType.Http, AdapterType.Https -> HttpAdapter.create(remote)
            AdapterType.Quic -> QuicAdapter.create(remote)
            AdapterType.Ssh -> SshAdapter.create(remote)
            else -> HttpAdapter.create(remote)
        }
    }
}
