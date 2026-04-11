package borg.literbike.ccek.quic.tls

/**
 * TLS termination for QUIC server.
 * Ported from literbike/src/ccek/quic/src/tls/mod.rs.
 *
 * Note: The Rust version uses rustls, rcgen, and OpenSSL.
 * This Kotlin version provides the same API surface as stubs since
 * these libraries are not available in Kotlin Multiplatform commonMain.
 */

/**
 * ALPN protocol identifiers for H2/H3 negotiation.
 */
object Alpn {
    val H3: ByteArray = "h3".toByteArray()
    val H2: ByteArray = "h2".toByteArray()
    val HQ_INTEROP: ByteArray = "hq-interop".toByteArray()
    val CUSTOM_QUIC: ByteArray = "customquic".toByteArray()

    fun supported(): List<ByteArray> = listOf(H3, HQ_INTEROP, CUSTOM_QUIC, H2)
}

/**
 * TLS terminator configuration.
 * In the Kotlin port, this is a configuration holder since actual TLS
 * termination requires platform-specific implementations.
 */
data class TlsTerminatorConfig(
    val certDer: ByteArray? = null,
    val keyDer: ByteArray? = null,
    val alpnProtocols: List<ByteArray> = Alpn.supported()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TlsTerminatorConfig) return false
        return certDer?.contentEquals(other.certDer ?: ByteArray(0)) == true &&
                keyDer?.contentEquals(other.keyDer ?: ByteArray(0)) == true &&
                alpnProtocols.map { it.toList() } == other.alpnProtocols.map { it.toList() }
    }

    override fun hashCode(): Int {
        var result = certDer?.contentHashCode() ?: 0
        result = 31 * result + (keyDer?.contentHashCode() ?: 0)
        result = 31 * result + alpnProtocols.hashCode()
        return result
    }
}

/**
 * TLS terminator stub for Kotlin Multiplatform.
 * On JVM, use platform TLS (JSSE) or Bouncy Castle.
 * On native, use platform-specific TLS libraries.
 */
class TlsTerminator(
    val config: TlsTerminatorConfig
) {
    companion object {
        fun localhost(): Result<TlsTerminator> {
            // In production, generate a self-signed cert using Bouncy Castle
            return Result.failure(Exception("TLS terminator requires platform-specific implementation"))
        }

        fun domain(domain: String): Result<TlsTerminator> {
            return Result.failure(Exception("TLS terminator requires platform-specific implementation"))
        }

        fun new(certDer: ByteArray, keyDer: ByteArray): Result<TlsTerminator> {
            return Result.success(TlsTerminator(TlsTerminatorConfig(certDer, keyDer)))
        }
    }

    fun alpnProtocols(): List<ByteArray> = config.alpnProtocols

    fun supportsAlpn(protocol: ByteArray): Boolean {
        return config.alpnProtocols.any { it.contentEquals(protocol) }
    }
}
