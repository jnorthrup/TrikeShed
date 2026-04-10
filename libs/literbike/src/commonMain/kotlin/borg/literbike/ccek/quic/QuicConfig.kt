package borg.literbike.ccek.quic

// ============================================================================
// QUIC Configuration -- ported from quic_config.rs
// ============================================================================

/**
 * QUIC configuration for endpoints.
 * ALPN protocol identifiers, idle timeouts, payload sizes, and feature flags.
 */
data class QuicConfig(
    val alpn: List<ByteArray> = listOf(byteArrayOf(0x68u.toByte(), 0x33u.toByte())), // "h3"
    val maxIdleTimeoutMs: ULong = 30_000uL,
    val maxUdpPayloadSize: UInt = 1350u,
    val enableGso: Boolean = true,
    val enableEcn: Boolean = true
) {
    companion object {
        val DEFAULT = QuicConfig()
    }
}
