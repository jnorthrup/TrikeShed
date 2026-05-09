package borg.trikeshed.tls.codec.aead

import kotlin.coroutines.CoroutineContext

/**
 * ChaCha20-Poly1305 AEAD cipher (RFC 8439).
 *
 * Used by TLS 1.3 cipher suite TLS_CHACHA20_POLY1305_SHA256 (0x1303).
 * Nonce construction identical to AES-GCM per RFC 8446 §5.3.
 */
interface ChaCha20Poly1305 : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<ChaCha20Poly1305>

    val keyLength: Int get() = 32
    val ivLength: Int get() = 12
    val tagLength: Int get() = 16

    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray?
}
