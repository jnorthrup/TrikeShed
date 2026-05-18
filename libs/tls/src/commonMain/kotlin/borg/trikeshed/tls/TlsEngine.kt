package borg.trikeshed.tls

/**
 * Abstract TLS engine interface for pluggable TLS backends.
 * Minimal prototype: wrap/unwrap are passthrough in JVM fallback.
 */
interface TlsEngine {
    suspend fun wrap(plain: ByteArray): ByteArray
    suspend fun unwrap(encrypted: ByteArray): ByteArray
    suspend fun close()
}
