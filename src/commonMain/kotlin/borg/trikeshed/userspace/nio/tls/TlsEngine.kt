package borg.trikeshed.userspace.nio.tls

import kotlin.coroutines.CoroutineContext

/**
 * TLS engine interface — the single CCEK type for all TLS in TrikeShed.
 *
 * Implementations (JDK SSLEngine on JVM, native engine on macOS) provide the
 * actual wrap/unwrap/close.  Each registers into the coroutine context behind
 * this [Key].
 */
interface TlsEngine : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<TlsEngine>
    override val key: CoroutineContext.Key<*> get() = Key

    suspend fun wrap(plain: ByteArray): ByteArray
    suspend fun unwrap(encrypted: ByteArray): ByteArray
    suspend fun close()

    /**
     * Drive the TLS handshake. Default no-op for engines where handshake
     * is implicit (e.g. JDK SSLEngine handles it internally).
     * Engines that need explicit handshake (native) should override.
     *
     * [reader] returns one TLS record at a time.
     * [writer] sends a raw TLS record.
     */
    suspend fun handshake(
        reader: suspend () -> ByteArray,
        writer: suspend (ByteArray) -> Unit,
    ) {}
}
