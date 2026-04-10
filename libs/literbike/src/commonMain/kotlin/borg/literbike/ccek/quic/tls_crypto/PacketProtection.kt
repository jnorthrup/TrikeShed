package borg.literbike.ccek.quic.tls_crypto

// ============================================================================
// Packet Protection -- ported from tls_crypto/packet_protection.rs
// QUIC AEAD payload and header protection
// ============================================================================

/**
 * QUIC crypto state for a single encryption level.
 * Ported from Rust QuicCryptoState.
 */
class QuicCryptoState(
    val algorithm: QuicAeadAlgorithm,
    private val key: ByteArray,
    private val iv: ByteArray,
    private val hpKey: ByteArray
) {
    companion object {
        fun create(
            algorithm: QuicAeadAlgorithm,
            keyBytes: ByteArray,
            iv: ByteArray,
            hpKeyBytes: ByteArray
        ): Result<QuicCryptoState> = runCatching {
            QuicCryptoState(algorithm, keyBytes, iv, hpKeyBytes)
        }
    }

    /** Compute the nonce for a given packet number (IV XOR packet_number) */
    private fun computeNonce(packetNumber: ULong): ByteArray {
        val nonce = iv.copyOf()
        val pnBytes = packetNumber.toBigEndianBytes()
        val ivLen = nonce.size
        for (i in 0 until 8) {
            nonce[ivLen - 8 + i] = (nonce[ivLen - 8 + i].toInt() xor pnBytes[i].toInt()).toByte()
        }
        return nonce
    }

    /**
     * Encrypt the packet payload; appends 16-byte authentication tag.
     * In production, use javax.crypto.Cipher with AES/GCM/NoPadding.
     */
    fun encryptPayload(
        packetNumber: ULong,
        aad: ByteArray,
        payloadAndTag: MutableList<Byte>
    ): Result<Unit> = runCatching {
        // Stub: in production, use AES-GCM AEAD encryption
        val nonce = computeNonce(packetNumber)
        val plaintext = payloadAndTag.toByteArray()
        // Real impl: cipher.doFinal(plaintext) with AAD
        // For now, just append a dummy 16-byte tag
        payloadAndTag.clear()
        payloadAndTag.addAll(plaintext.map { it })
        payloadAndTag.addAll(List(16) { 0.toByte() })  // dummy tag
    }

    /**
     * Decrypt ciphertext_and_tag in place; returns plaintext.
     * In production, use javax.crypto.Cipher with AES/GCM/NoPadding.
     */
    fun decryptPayload(
        packetNumber: ULong,
        aad: ByteArray,
        ciphertextAndTag: MutableList<Byte>
    ): Result<ByteArray> = runCatching {
        if (ciphertextAndTag.size < 16) {
            throw IllegalArgumentException("ciphertext too short for AES-GCM tag")
        }
        val tagStart = ciphertextAndTag.size - 16
        val tag = ciphertextAndTag.subList(tagStart, ciphertextAndTag.size).toByteArray()
        val ciphertext = ciphertextAndTag.subList(0, tagStart).toByteArray()
        val nonce = computeNonce(packetNumber)
        // Real impl: cipher.doFinal(ciphertext) with AAD and tag
        // For now, return ciphertext as-is (stub)
        ciphertext
    }

    /**
     * Generate the 5-byte header protection mask via AES-ECB on a 16-byte sample.
     * In production, use javax.crypto.Cipher with AES/ECB/NoPadding.
     */
    fun generateHeaderProtectionMask(sample: ByteArray): Result<ByteArray> = runCatching {
        require(sample.size >= 16) { "sample must be at least 16 bytes" }
        // Real impl: AES-ECB encrypt sample with hpKey
        // For now, return first 5 bytes of sample as dummy mask
        sample.copyOf(5)
    }
}

/** Convert ULong to big-endian byte array (8 bytes) */
private fun ULong.toBigEndianBytes(): ByteArray {
    val bytes = ByteArray(8)
    var value = this
    for (i in 7 downTo 0) {
        bytes[i] = (value and 0xFFuL).toByte()
        value = value shr 8
    }
    return bytes
}
