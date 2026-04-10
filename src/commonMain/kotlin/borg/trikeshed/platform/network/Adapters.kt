package borg.trikeshed.platform.network

/**
 * Network protocol adapters for various transport protocols
 */

/**
 * Type of network adapter
 */
enum class AdapterType {
    Http,
    Https,
    Quic,
    Ssh,
    WebSocket,
    Raw
}

/**
 * Trait for network protocol adapters
 */
interface NetworkAdapter {
    val adapterType: AdapterType
    fun remoteAddr(): Result<String>
    fun isConnected(): Boolean
    fun close(): Result<Unit>
    suspend fun read(buf: ByteArray): Result<Int>
    suspend fun write(buf: ByteArray): Result<Int>
}

/**
 * HTTP adapter for HTTP/1.1 and HTTP/2 protocols
 */
class HttpAdapter(
    private val remote: String,
    private var connected: Boolean = true
) : NetworkAdapter {
    override val adapterType: AdapterType = AdapterType.Http

    override fun remoteAddr(): Result<String> = Result.success(remote)
    override fun isConnected(): Boolean = connected
    override fun close(): Result<Unit> { connected = false; return Result.success(Unit) }
    override suspend fun read(buf: ByteArray): Result<Int> = Result.success(0)
    override suspend fun write(buf: ByteArray): Result<Int> = Result.success(0)
}

/**
 * QUIC adapter for QUIC protocol
 */
class QuicAdapter(
    private val remote: String,
    val streamId: Long,
    private var connected: Boolean = true
) : NetworkAdapter {
    override val adapterType: AdapterType = AdapterType.Quic

    override fun remoteAddr(): Result<String> = Result.success(remote)
    override fun isConnected(): Boolean = connected
    override fun close(): Result<Unit> { connected = false; return Result.success(Unit) }
    override suspend fun read(buf: ByteArray): Result<Int> = Result.success(0)
    override suspend fun write(buf: ByteArray): Result<Int> = Result.success(0)
}

/**
 * SSH adapter for SSH protocol
 */
class SshAdapter(
    private val remote: String,
    private var sessionId: ByteArray = ByteArray(0),
    private var connected: Boolean = true
) : NetworkAdapter {
    override val adapterType: AdapterType = AdapterType.Ssh

    fun setSessionId(id: ByteArray) { sessionId = id }

    override fun remoteAddr(): Result<String> = Result.success(remote)
    override fun isConnected(): Boolean = connected
    override fun close(): Result<Unit> { connected = false; return Result.success(Unit) }
    override suspend fun read(buf: ByteArray): Result<Int> = Result.success(0)
    override suspend fun write(buf: ByteArray): Result<Int> = Result.success(0)
}

/**
 * Factory for creating network adapters
 */
object AdapterFactory {
    fun createAdapter(
        adapterType: AdapterType,
        remote: String
    ): NetworkAdapter = when (adapterType) {
        AdapterType.Http, AdapterType.Https -> HttpAdapter(remote)
        AdapterType.Quic -> QuicAdapter(remote, 0L)
        AdapterType.Ssh -> SshAdapter(remote)
        else -> HttpAdapter(remote)
    }
}
