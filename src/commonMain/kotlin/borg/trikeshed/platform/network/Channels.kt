package borg.trikeshed.platform.network

import borg.trikeshed.platform.network.Protocol
import kotlin.concurrent.AtomicLong

/**
 * Metadata about a channel
 */
class ChannelMetadata(
    val remoteAddr: String? = null,
    val localAddr: String? = null,
    val protocol: Protocol? = null
) {
    private val _bytesRead = AtomicLong(0L)
    private val _bytesWritten = AtomicLong(0L)

    fun bytesRead(): Long = _bytesRead.get()
    fun bytesWritten(): Long = _bytesWritten.get()

    internal fun addBytesRead(n: Int) { _bytesRead.addAndFetch(n.toLong()) }
    internal fun addBytesWritten(n: Int) { _bytesWritten.addAndFetch(n.toLong()) }
}

/**
 * Trait for network channels that support blocking I/O
 */
interface Channel {
    val channelType: String
    fun isConnected(): Boolean
    val metadata: ChannelMetadata? get() = null
    suspend fun read(buf: ByteArray): Result<Int>
    suspend fun write(buf: ByteArray): Result<Int>
}

/**
 * Basic TCP channel implementation
 */
class TcpChannel(
    private val remoteAddr: String,
    private val metadata: ChannelMetadata
) : Channel {
    override val channelType: String = "TCP"
    private var connected = false

    companion object {
        fun connect(addr: String): Result<TcpChannel> {
            return Result.success(
                TcpChannel(
                    remoteAddr = addr,
                    metadata = ChannelMetadata(remoteAddr = addr)
                ).apply { connected = true }
            )
        }
    }

    override fun isConnected(): Boolean = connected

    override val metadata: ChannelMetadata
        get() = metadata

    override suspend fun read(buf: ByteArray): Result<Int> {
        // Platform-specific implementation
        return Result.failure(NotImplementedError("Platform-specific read()"))
    }

    override suspend fun write(buf: ByteArray): Result<Int> {
        // Platform-specific implementation
        return Result.failure(NotImplementedError("Platform-specific write()"))
    }
}

/**
 * Provider for creating channels
 */
interface ChannelProvider {
    fun createChannel(addr: String): Result<Channel>
    val providerName: String
}

/**
 * Default TCP channel provider
 */
object TcpChannelProvider : ChannelProvider {
    override fun createChannel(addr: String): Result<Channel> = TcpChannel.connect(addr)
    override val providerName: String = "TCP"
}
