package borg.trikeshed.tls.codec.hash

import kotlin.coroutines.CoroutineContext

/**
 * SHA-256 hash primitive (FIPS 180-4).
 *
 * Registered in the coroutine context; the TLS layer reads it via
 * `coroutineContext[Sha256.Key]`. Platform implementations:
 *   JVM  → java.security.MessageDigest
 *   JS   → Web Crypto API (SubtleCrypto.digest)
 *   Native → OpenSSL SHA256
 */
interface Sha256 : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<Sha256>

    /** Hash [data] and return 32-byte digest. */
    fun hash(data: ByteArray): ByteArray

    /**
     * HMAC-SHA-256 (RFC 2104).
     *
     * HMAC(k, m) = H((k ⊕ opad) || H((k ⊕ ipad) || m))
     */
    fun hmac(key: ByteArray, data: ByteArray): ByteArray
}
