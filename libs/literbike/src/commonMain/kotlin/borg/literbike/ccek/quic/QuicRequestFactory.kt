package borg.literbike.ccek.quic

// ============================================================================
// QUIC Request Factory -- ported from quic_request_factory.rs
// ============================================================================

import kotlin.net.SocketAddress

/** Stub QUIC client for request factory */
class QuicClient private constructor() {
    companion object {
        fun create(): QuicClient = QuicClient()
    }

    suspend fun connect(addr: SocketAddress, host: String): Result<Unit> {
        // Stub: would establish QUIC connection in real impl
        return Result.success(Unit)
    }
}

/**
 * QUIC request factory for creating and dispatching requests.
 * Ported from Rust QuicRequestFactory.
 */
object QuicRequestFactory {
    suspend fun demo(host: String, port: Int): Result<Unit> = runCatching {
        val client = QuicClient.create()
        // Stub: would connect in real impl
        println("QUIC demo connection to $host:$port (stub)")
    }
}
