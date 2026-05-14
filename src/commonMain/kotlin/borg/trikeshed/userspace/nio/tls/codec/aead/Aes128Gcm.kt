package borg.trikeshed.userspace.nio.tls.codec.aead

import kotlin.coroutines.CoroutineContext

/**
 * AES-128-GCM AEAD cipher (RFC 5116, NIST SP 800-38D).
 *
 * Used by TLS 1.3 cipher suite TLS_AES_128_GCM_SHA256 (0x1301).
 * The 12-byte nonce is constructed per RFC 8446 §5.3:
 *   nonce = XOR(sequence_number, write_iv)
 *
 * @property keyLength  16 bytes (AES-128)
 * @property ivLength   12 bytes (fixed for TLS 1.3)
 * @property tagLength  16 bytes (GCM authentication tag)
 */
interface Aes128Gcm : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<Aes128Gcm>

    val keyLength: Int get() = 16
    val ivLength: Int get() = 12
    val tagLength: Int get() = 16

    /**
     * Encrypt plaintext → ciphertext + tag.
     *
     * @param key       16-byte AES key
     * @param nonce     12-byte nonce (sequence XOR write_iv)
     * @param aad       additional authenticated data (record header)
     * @param plaintext data to encrypt
     * @return ciphertext with appended 16-byte authentication tag
     */
    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray

    /**
     * Decrypt ciphertext + tag → plaintext (or null on auth failure).
     *
     * @param key        16-byte AES key
     * @param nonce      12-byte nonce
     * @param aad        additional authenticated data
     * @param ciphertext ciphertext with appended 16-byte GCM tag
     * @return decrypted plaintext, or null if tag verification fails
     */
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray?
}
