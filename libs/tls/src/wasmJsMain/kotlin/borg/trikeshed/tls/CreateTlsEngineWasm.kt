package borg.trikeshed.tls

/**
 * WASM TLS engine — passthrough. WASM in the browser has no raw socket access
 * and cannot perform TLS handshakes. TLS is handled by the runtime's WebSocket
 * implementation (which negotiates TLS internally).
 */
actual fun createTlsEngine(settings: TlsSettings): TlsEngine = object : TlsEngine {
    override suspend fun wrap(plain: ByteArray): ByteArray = plain
    override suspend fun unwrap(encrypted: ByteArray): ByteArray = encrypted
    override suspend fun close() {}
}
