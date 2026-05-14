package borg.trikeshed.userspace.nio.tls.codec.hash

import kotlin.coroutines.CoroutineContext

/**
 * SHA-384 hash primitive (FIPS 180-4).
 *
 * Same CCEK pattern as [borg.trikeshed.userspace.nio.tls.codec.hash.Sha256]. Used by TLS_AES_256_GCM_SHA384.
 */
interface Sha384 : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<Sha384>

    fun hash(data: ByteArray): ByteArray  // 48 bytes
    fun hmac(key: ByteArray, data: ByteArray): ByteArray
}
