package borg.trikeshed.tls

import borg.trikeshed.collections.s_
import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.lib.Series
import kotlin.coroutines.CoroutineContext

/**
 * Real TLS protocol versions.
 */
enum class TlsProtocolVersion {
    /** TLS 1.2 (RFC 5246) */
    V1_2,
    /** TLS 1.3 (RFC 8446) */
    V1_3,
}

/**
 * Certificate hash for identity verification.
 */
data class CertificateFingerprint(val sha256: CharSequence)

/**
 * TLS client configuration.
 *
 * @param protocolVersion minimum acceptable TLS version
 * @param cipherSuites    allowed cipher suites
 * @param serverName      SNI hostname
 * @param pinnedCertificates optional certificate pinning (public key hashes)
 */
data class TlsSettings(
    val protocolVersion: TlsProtocolVersion = TlsProtocolVersion.V1_3,
    val cipherSuites: Series<CharSequence> = s_["TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256"],
    val serverName: CharSequence? = null,
    val pinnedCertificates: List<CertificateFingerprint> = emptyList(),
) {
    companion object {
        /** Accept all versions (least secure — for legacy). */
        val acceptAllVersions = TlsSettings(protocolVersion = TlsProtocolVersion.V1_2)

        /** Modern default: TLS 1.3 with strong cipher suites. */
        val default = TlsSettings()
    }
}

/**
 * Platform-specific TLS engine factory.
 * Implemented via expect/actual: OpenSSL on native, JDK SSLEngine on JVM, Web Crypto on JS.
 */
expect fun createTlsEngine(settings: TlsSettings): TlsEngine

/**
 * TlsElement wraps a real TLS engine with async lifecycle management.
 */
class TlsElement(
    val settings: TlsSettings = TlsSettings.default,
) : AsyncContextElement() {
    override val key: CoroutineContext.Key<*> = Key
    companion object Key : CoroutineContext.Key<TlsElement>

    /** Lazily created after [open]. */
    private var engine: TlsEngine? = null

    override suspend fun open() {
        if (state.isAtLeast(ElementState.OPEN)) return
        super.open()
        engine = createTlsEngine(settings)
    }

    override suspend fun close() {
        if (state.isAtLeast(ElementState.OPEN) && state.isLessThan(ElementState.CLOSED)) {
            state = ElementState.DRAINING
            engine?.close()
        }
        super.close()
    }

    suspend fun wrap(plain: ByteArray): ByteArray {
        check(state.isAtLeast(ElementState.OPEN)) { "TlsElement not open" }
        return engine!!.wrap(plain)
    }

    suspend fun unwrap(encrypted: ByteArray): ByteArray {
        check(state.isAtLeast(ElementState.OPEN)) { "TlsElement not open" }
        return engine!!.unwrap(encrypted)
    }
}
