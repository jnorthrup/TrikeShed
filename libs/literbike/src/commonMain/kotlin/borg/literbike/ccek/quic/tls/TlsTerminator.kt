package borg.literbike.ccek.quic.tls

// ============================================================================
// TLS Termination -- ported from tls/mod.rs
// ALPN protocol identifiers and TLS terminator stub
// ============================================================================

/** ALPN protocol identifiers for H2/H3 negotiation */
object Alpn {
    val H3: ByteArray = byteArrayOf(0x68u.toByte(), 0x33u.toByte())                  // "h3"
    val H2: ByteArray = byteArrayOf(0x68u.toByte(), 0x32u.toByte())                  // "h2"
    val HQ_INTEROP: ByteArray = byteArrayOf(0x68u.toByte(), 0x71u.toByte(),
                                           0x2du.toByte(), 0x69u.toByte(),
                                           0x6eu.toByte(), 0x74u.toByte(),
                                           0x65u.toByte(), 0x72u.toByte(),
                                           0x6fu.toByte(), 0x70u.toByte())           // "hq-interop"
    val CUSTOM_QUIC: ByteArray = byteArrayOf(0x63u.toByte(), 0x75u.toByte(),
                                            0x73u.toByte(), 0x74u.toByte(),
                                            0x6fu.toByte(), 0x6du.toByte(),
                                            0x71u.toByte(), 0x75u.toByte(),
                                            0x69u.toByte(), 0x63u.toByte())          // "customquic"

    fun supported(): List<ByteArray> = listOf(H3, HQ_INTEROP, CUSTOM_QUIC, H2)
}

/**
 * TLS terminator stub.
 * In production, integrate with Bouncy Castle, Conscrypt, or your platform TLS.
 *
 * Ported from Rust TlsTerminator (rcgen + rustls + OpenSSL).
 */
class TlsTerminator(
    val certDer: ByteArray,
    val keyDer: ByteArray
) {
    companion object {
        /** Create a TLS terminator from DER-encoded cert and key */
        fun create(certDer: ByteArray, keyDer: ByteArray): TlsTerminator {
            return TlsTerminator(certDer, keyDer)
        }

        /** Create a self-signed TLS terminator for localhost */
        fun localhost(): TlsTerminator {
            // Stub: in production, generate cert via Bouncy Castle or load from resources
            val certDer = ByteArray(0)
            val keyDer = ByteArray(0)
            return TlsTerminator(certDer, keyDer)
        }

        /** Create a TLS terminator for a specific domain */
        fun domain(domain: String): TlsTerminator {
            // Stub: in production, load cert/key for domain
            return TlsTerminator(ByteArray(0), ByteArray(0))
        }

        /** Create from PEM files */
        fun fromPemFiles(certPath: String, keyPath: String): TlsTerminator {
            // Stub: in production, read PEM and convert to DER
            return TlsTerminator(ByteArray(0), ByteArray(0))
        }
    }

    fun alpnProtocols(): List<ByteArray> = Alpn.supported()

    fun supportsAlpn(protocol: ByteArray): Boolean {
        return Alpn.supported().any { it.contentEquals(protocol) }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TlsTerminator) return false
        if (!certDer.contentEquals(other.certDer)) return false
        if (!keyDer.contentEquals(other.keyDer)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = certDer.contentHashCode()
        result = 31 * result + keyDer.contentHashCode()
        return result
    }
}
