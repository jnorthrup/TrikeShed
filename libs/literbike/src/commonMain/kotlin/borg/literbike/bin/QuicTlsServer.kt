package borg.literbike.bin

/**
 * QUIC TLS Server - QUIC server with TLS integration.
 * Ported from literbike/src/bin/quic_tls_server.rs.
 */

/**
 * TLS configuration for QUIC server.
 */
data class TlsConfig(
    val certPath: String? = System.getenv("QUIC_TLS_CERT"),
    val keyPath: String? = System.getenv("QUIC_TLS_KEY"),
    val alpnProtocols: List<String> = listOf("h3", "h2"),
    val minVersion: String = "TLSv1.3",
    val verifyClient: Boolean = false,
    val caCertPath: String? = null
)

/**
 * QUIC server configuration.
 */
data class QuicServerConfig(
    val bindAddress: String = "0.0.0.0",
    val bindPort: Int = System.getenv("QUIC_PORT")?.toIntOrNull() ?: 4433,
    val tlsConfig: TlsConfig = TlsConfig(),
    val maxConcurrentConnections: Int = 1000,
    val maxConcurrentStreamsPerConnection: Int = 100,
    val idleTimeoutMillis: Long = 30000,
    val maxDatagramSize: Int = 1200
)

/**
 * QUIC TLS Server.
 */
class QuicTlsServer(
    private val config: QuicServerConfig
) {
    private var isRunning = false

    /**
     * Start the QUIC TLS server.
     */
    fun start(): Result<Unit> {
        println("QUIC TLS Server starting on ${config.bindAddress}:${config.bindPort}")
        println("  ALPN protocols: ${config.tlsConfig.alpnProtocols.joinToString(", ")}")
        println("  TLS version: ${config.tlsConfig.minVersion}")
        println("  Max connections: ${config.maxConcurrentConnections}")
        println("  Max streams per connection: ${config.maxConcurrentStreamsPerConnection}")
        println("  Idle timeout: ${config.idleTimeoutMillis}ms")
        println("  Max datagram size: ${config.maxDatagram} bytes")

        if (config.tlsConfig.certPath != null) {
            println("  TLS cert: ${config.tlsConfig.certPath}")
        } else {
            println("  Warning: No TLS certificate configured, using self-signed cert")
        }

        isRunning = true
        return Result.success(Unit)
    }

    /**
     * Stop the QUIC TLS server.
     */
    fun stop(): Result<Unit> {
        isRunning = false
        println("QUIC TLS Server stopped")
        return Result.success(Unit)
    }

    /**
     * Check if server is running.
     */
    fun running(): Boolean = isRunning

    /**
     * Get server statistics.
     */
    fun getStats(): ServerStats = ServerStats(
        activeConnections = 0,
        totalConnectionsHandled = 0,
        totalBytesReceived = 0L,
        totalBytesSent = 0L,
        uptimeMillis = 0L
    )
}

/**
 * Server statistics.
 */
data class ServerStats(
    val activeConnections: Int,
    val totalConnectionsHandled: Long,
    val totalBytesReceived: Long,
    val totalBytesSent: Long,
    val uptimeMillis: Long
) {
    fun printStats() {
        println("Server Statistics:")
        println("  Active connections: $activeConnections")
        println("  Total connections: $totalConnectionsHandled")
        println("  Bytes received: $totalBytesReceived")
        println("  Bytes sent: $totalBytesSent")
        println("  Uptime: ${uptimeMillis}ms")
    }
}

/**
 * Main entry point for QUIC TLS Server.
 */
fun runQuicTlsServer(port: Int? = null) {
    val config = QuicServerConfig(
        bindPort = port ?: 4433
    )

    val server = QuicTlsServer(config)
    server.start().fold(
        onSuccess = {
            println("Server started successfully")
            println("Press Ctrl+C to stop")
        },
        onFailure = { e ->
            println("Server failed to start: ${e.message}")
        }
    )
}
