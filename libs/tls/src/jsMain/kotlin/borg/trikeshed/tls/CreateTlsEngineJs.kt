package borg.trikeshed.tls

/**
 * JS actual: stub TLS engine (Node.js `tls` module not bound yet).
 * Returns a passthrough for development — real crypto requires
 * `require('tls')` interop which needs a JS bridge.
 */
actual fun createTlsEngine(settings: TlsSettings): TlsEngine = PassthroughTlsEngine

private object PassthroughTlsEngine : TlsEngine {
    override suspend fun wrap(plain: ByteArray): ByteArray = plain
    override suspend fun unwrap(encrypted: ByteArray): ByteArray = encrypted
    override suspend fun close() {}
}
