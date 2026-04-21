package borg.trikeshed.userspace.network

/**
 * Network protocol adapters ported from literbike.
 */

enum class AdapterType {
    Http, Https, Quic, Ssh, WebSocket, Raw
}

interface NetworkAdapter {
    fun adapterType(): AdapterType
    fun remoteAddr(): String
    fun isConnected(): Boolean
    fun close(): Result<Unit>
    fun read(buf: ByteArray): Int
    fun write(buf: ByteArray): Int
}

class HttpAdapter(
    private val remote: String
) : NetworkAdapter {
    private var connected = true

    override fun adapterType() = AdapterType.Http
    override fun remoteAddr() = remote
    override fun isConnected() = connected
    override fun close(): Result<Unit> {
        connected = false
        return Result.success(Unit)
    }
    override fun read(buf: ByteArray): Int = 0 // Implementation placeholder
    override fun write(buf: ByteArray): Int = 0 // Implementation placeholder
}

class QuicAdapter(
    private val remote: String,
    val streamId: Long
) : NetworkAdapter {
    private var connected = true

    override fun adapterType() = AdapterType.Quic
    override fun remoteAddr() = remote
    override fun isConnected() = connected
    override fun close(): Result<Unit> {
        connected = false
        return Result.success(Unit)
    }
    override fun read(buf: ByteArray): Int = 0
    override fun write(buf: ByteArray): Int = 0
}
