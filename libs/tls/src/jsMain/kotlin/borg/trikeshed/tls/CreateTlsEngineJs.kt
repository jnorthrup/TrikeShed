package borg.trikeshed.tls

/**
 * JS/Node.js TLS engine — delegates to platform TLS (browser WebSocket or Node TLS module).
 * For commonMain WS connections, TLS is handled by the WebSocket transport layer.
 */
actual fun createTlsEngine(settings: TlsSettings): TlsEngine = object : TlsEngine {
    override suspend fun wrap(plain: ByteArray): ByteArray {
        // In JS, TLS is handled by the browser's WebSocket or Node's tls module.
        // The TlsElement is used for fingerprint configuration only; actual
        // encryption happens at the transport layer.
        return plain
    }
    override suspend fun unwrap(encrypted: ByteArray): ByteArray = encrypted
    override suspend fun close() {}
}
