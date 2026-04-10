package borg.literbike.ccek.quic

// ============================================================================
// TLS CCEK -- ported from tls_ccek.rs
// TLS integration with CCEK context
// ============================================================================

import java.util.concurrent.atomic.AtomicReference

/**
 * TLS CCEK Service.
 * Ported from Rust TlsCcekService.
 *
 * Provides TLS configuration and state for QUIC connections,
 * injectable into the CoroutineContext.
 */
class TlsCcekService(
    val config: TlsConfig
) : ContextElement {
    override val key: String = "TlsCcekService"

    companion object {
        fun create(config: TlsConfig): TlsCcekService = TlsCcekService(config)

        fun createDefault(): TlsCcekService = TlsCcekService(TlsConfig())
    }
}

/**
 * TLS Configuration for QUIC.
 */
data class TlsConfig(
    val alpnProtocols: List<ByteArray> = listOf(
        byteArrayOf(0x68u.toByte(), 0x33u.toByte()),  // "h3"
        byteArrayOf(0x68u.toByte(), 0x71u.toByte(),
                    0x2du.toByte(), 0x69u.toByte(),
                    0x6eu.toByte(), 0x74u.toByte(),
                    0x65u.toByte(), 0x72u.toByte(),
                    0x6fu.toByte(), 0x70u.toByte())   // "hq-interop"
    ),
    val maxIdleTimeoutMs: ULong = 30_000uL,
    val maxUdpPayloadSize: UInt = 1350u,
    val enableEarlyData: Boolean = false
)

/**
 * TLS Connection State.
 * Tracks the state of a single TLS connection.
 */
class TlsConnectionState {
    private val state = AtomicReference(TlsState.Initial)

    fun getState(): TlsState = state.get()
    fun setState(newState: TlsState) {
        state.set(newState)
    }

    fun isComplete(): Boolean = state.get() == TlsState.Connected

    enum class TlsState {
        Initial,
        ClientHello,
        ServerHello,
        Handshake,
        Connected,
        Closed
    }
}

/**
 * TLS Handshake Result.
 */
data class TlsHandshakeResult(
    val success: Boolean,
    val alpnProtocol: ByteArray? = null,
    val error: String? = null
)

/**
 * TLS Handshake Processor.
 * Processes TLS handshake messages for QUIC.
 */
class TlsHandshakeProcessor {
    private val connections = mutableMapOf<String, TlsConnectionState>()

    /** Process a TLS handshake message */
    fun processHandshakeMessage(
        connectionId: String,
        data: List<UByte>,
        isClient: Boolean
    ): Result<TlsHandshakeResult> = runCatching {
        val connState = connections.getOrPut(connectionId) { TlsConnectionState() }

        when (connState.getState()) {
            TlsConnectionState.TlsState.Initial -> {
                if (isClient) {
                    connState.setState(TlsConnectionState.TlsState.ClientHello)
                } else {
                    connState.setState(TlsConnectionState.TlsState.ServerHello)
                }
                TlsHandshakeResult(success = true)
            }
            TlsConnectionState.TlsState.ClientHello -> {
                if (!isClient) {
                    connState.setState(TlsConnectionState.TlsState.Handshake)
                }
                TlsHandshakeResult(success = true)
            }
            TlsConnectionState.TlsState.ServerHello -> {
                if (isClient) {
                    connState.setState(TlsConnectionState.TlsState.Handshake)
                }
                TlsHandshakeResult(success = true)
            }
            TlsConnectionState.TlsState.Handshake -> {
                connState.setState(TlsConnectionState.TlsState.Connected)
                TlsHandshakeResult(
                    success = true,
                    alpnProtocol = byteArrayOf(0x68u.toByte(), 0x33u.toByte())  // "h3"
                )
            }
            TlsConnectionState.TlsState.Connected -> {
                TlsHandshakeResult(success = true)
            }
            TlsConnectionState.TlsState.Closed -> {
                TlsHandshakeResult(success = false, error = "Connection closed")
            }
        }
    }

    /** Get connection state */
    fun getConnectionState(connectionId: String): TlsConnectionState.TlsState? {
        return connections[connectionId]?.getState()
    }

    /** Remove a connection */
    fun removeConnection(connectionId: String) {
        connections.remove(connectionId)
    }
}
