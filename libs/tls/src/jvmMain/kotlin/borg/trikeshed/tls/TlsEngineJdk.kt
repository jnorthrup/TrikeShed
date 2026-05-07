package borg.trikeshed.tls

/**
 * JVM fallback TLS engine: passthrough implementation for prototype and tests.
 * Real implementation should use SSLEngine or native OpenSSL bindings.
 */
class TlsEngineJdk : TlsEngine {
    override suspend fun wrap(plain: ByteArray): ByteArray = plain
    override suspend fun unwrap(encrypted: ByteArray): ByteArray = encrypted
    override suspend fun close() {}
}
