package borg.trikeshed.tls

/**
 * WASM actual: stub TLS engine.
 * WASM in browser has no raw socket access — TLS must be proxied
 * through WebSocket or fetch with HTTPS.
 */
actual fun createTlsEngine(settings: TlsSettings): TlsEngine = PassthroughTlsEngine

private object PassthroughTlsEngine : TlsEngine {
    override suspend fun wrap(plain: ByteArray): ByteArray = plain
    override suspend fun unwrap(encrypted: ByteArray): ByteArray = encrypted
    override suspend fun close() {}
}
