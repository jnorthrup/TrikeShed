package borg.trikeshed.tls

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
}
