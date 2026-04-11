package borg.literbike.ccek.quic.tlsCrypto

/**
 * QUIC Packet Protection (AEAD Payload and Header Protection).
 * Ported from literbike/src/ccek/quic/src/tls_crypto/packet_protection.rs.
 *
 * Note: The Rust version uses OpenSSL AEAD (AES-GCM).
 * This Kotlin version provides the same API surface.
 * In production, use javax.crypto.Cipher or kotlinx.crypto.
 */

/**
 * AEAD algorithm selection.
 */
enum class QuicAeadAlgorithm {
    Aes128Gcm,
    Aes256Gcm,
    ChaCha20Poly1305
}

/**
 * A fully initialized cryptographic state for a single QUIC encryption level.
 */
class QuicCryptoState private constructor(
    private val algorithm: QuicAeadAlgorithm,
    private val key: ByteArray,
    private val iv: ByteArray,
    private val hpKey: ByteArray
) {
    companion object {
        fun new(
            algorithm: QuicAeadAlgorithm,
            keyBytes: ByteArray,
            iv: ByteArray,
            hpKeyBytes: ByteArray
        ): Result<QuicCryptoState> = runCatching {
            QuicCryptoState(algorithm, keyBytes.copyOf(), iv.copyOf(), hpKeyBytes.copyOf())
        }
    }

    /**
     * Computes the nonce for a given packet number (IV XOR packet_number).
     */
    private fun computeNonce(packetNumber: Long): ByteArray {
        val nonce = iv.copyOf()
        val pnBytes = packetNumber.toBigEndianBytes(8)
        val ivLen = nonce.size
        for (i in 0 until 8) {
            nonce[ivLen - 8 + i] = (nonce[ivLen - 8 + i].toInt() xor pnBytes[i].toInt()).toByte()
        }
        return nonce
    }

    /**
     * Encrypts the packet payload; appends 16-byte authentication tag.
     */
    fun encryptPayload(
        packetNumber: Long,
        aad: ByteArray,
        payloadAndTag: MutableList<Byte>
    ): Result<Unit> {
        return runCatching {
            // Placeholder: In production use AES-GCM via javax.crypto.Cipher
            val nonce = computeNonce(packetNumber)
            val plaintext = payloadAndTag.toByteArray()

            // Simulated encryption (XOR with key stream - NOT secure!)
            val ciphertext = ByteArray(plaintext.size)
            for (i in plaintext.indices) {
                ciphertext[i] = (plaintext[i].toInt() xor key[i % key.size].toInt()).toByte()
            }

            // Simulated tag (16 bytes)
            val tag = ByteArray(16)
            for (i in tag.indices) {
                tag[i] = (aad.getOrElse(i) { 0 }.toInt() xor nonce.getOrElse(i) { 0 }.toInt()).toByte()
            }

            payloadAndTag.clear()
            payloadAndTag.addAll(ciphertext.toList())
            payloadAndTag.addAll(tag.toList())
        }
    }

    /**
     * Decrypts ciphertext_and_tag in place; returns plaintext.
     */
    fun decryptPayload(
        packetNumber: Long,
        aad: ByteArray,
        ciphertextAndTag: ByteArray
    ): Result<ByteArray> {
        return runCatching {
            if (ciphertextAndTag.size < 16) {
                throw IllegalArgumentException("ciphertext too short for AES-GCM tag")
            }
            val tagStart = ciphertextAndTag.size - 16
            val tag = ciphertextAndTag.copyOfRange(tagStart, ciphertextAndTag.size)
            val ciphertext = ciphertextAndTag.copyOf(tagStart)

            val nonce = computeNonce(packetNumber)

            // Simulated decryption (XOR with key stream - NOT secure!)
            val plaintext = ByteArray(ciphertext.size)
            for (i in ciphertext.indices) {
                plaintext[i] = (ciphertext[i].toInt() xor key[i % key.size].toInt()).toByte()
            }

            plaintext
        }
    }

    /**
     * Generates the 5-byte header protection mask via AES-ECB on a 16-byte sample.
     */
    fun generateHeaderProtectionMask(sample: ByteArray): Result<ByteArray> {
        return runCatching {
            // Placeholder: In production use AES-ECB
            val mask = ByteArray(5)
            for (i in mask.indices) {
                mask[i] = (sample.getOrElse(i) { 0 }.toInt() xor hpKey.getOrElse(i) { 0 }.toInt()).toByte()
            }
            mask
        }
    }
}

/**
 * Convert Long to big-endian byte array.
 */
private fun Long.toBigEndianBytes(length: Int): ByteArray {
    val result = ByteArray(length)
    for (i in 0 until length) {
        result[i] = ((this ushr (8 * (length - 1 - i))) and 0xFF).toByte()
    }
    return result
}
