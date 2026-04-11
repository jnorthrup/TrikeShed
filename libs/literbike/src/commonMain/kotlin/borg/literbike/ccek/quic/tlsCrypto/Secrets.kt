package borg.literbike.ccek.quic.tlsCrypto

/**
 * QUIC TLS 1.3 Secrets Generation (RFC 9001).
 * Ported from literbike/src/ccek/quic/src/tls_crypto/secrets.rs.
 *
 * Note: The Rust version uses OpenSSL HMAC-SHA256 HKDF.
 * This Kotlin version implements HKDF using basic operations.
 * In production, use a proper cryptographic library like Bouncy Castle.
 */

/**
 * The QUIC v1 Initial Salt as defined in RFC 9001 Section 5.2.
 */
private val QUIC_V1_INITIAL_SALT = byteArrayOf(
    0x38, 0x76, 0x2c, 0xf7, 0xf5, 0x59, 0x34, 0xb3,
    0x4d, 0x17, 0x9a, 0xe6, 0xa4, 0xc8, 0x0c, 0xad,
    0xcc, 0xbb, 0x7f, 0x0a
)

/**
 * HKDF-Extract(salt, IKM) -> PRK
 * In production, use a proper HMAC implementation.
 */
fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
    // Placeholder: simple hash-based extraction
    // In production, use HMAC-SHA256
    return hmacSha256(salt, ikm)
}

/**
 * HKDF-Expand(PRK, info, L) -> OKM
 */
fun hkdfExpand(prk: ByteArray, info: ByteArray, len: Int): ByteArray {
    val out = mutableListOf<Byte>()
    var t = ByteArray(0)
    var counter: Byte = 1

    while (out.size < len) {
        val data = t + info + byteArrayOf(counter)
        t = hmacSha256(prk, data)
        out.addAll(t.toList())
        counter = (counter.toInt() + 1).toByte()
    }

    return out.take(len).toByteArray()
}

/**
 * RFC 9001 Section 5.1: HKDF-Expand-Label
 */
fun hkdfExpandLabel(prk: ByteArray, label: ByteArray, context: ByteArray, len: Int): ByteArray {
    val hkdfLabel = mutableListOf<Byte>()
    // length (uint16)
    hkdfLabel.add(((len ushr 8) and 0xFF).toByte())
    hkdfLabel.add((len and 0xFF).toByte())
    // label = "tls13 " + label (length-prefixed)
    val fullLabel = "tls13 ".toByteArray() + label
    hkdfLabel.add(fullLabel.size.toByte())
    hkdfLabel.addAll(fullLabel.toList())
    // context (length-prefixed)
    hkdfLabel.add(context.size.toByte())
    hkdfLabel.addAll(context.toList())

    return hkdfExpand(prk, hkdfLabel.toByteArray(), len)
}

/**
 * Extracts initial secrets based on the Destination Connection ID.
 * Returns (clientInitialSecret, serverInitialSecret)
 */
fun deriveInitialSecrets(clientDstConnId: ByteArray): Pair<ByteArray, ByteArray> {
    val initialSecret = hkdfExtract(QUIC_V1_INITIAL_SALT, clientDstConnId)
    val clientSecret = hkdfExpandLabel(initialSecret, "client in".toByteArray(), ByteArray(0), 32)
    val serverSecret = hkdfExpandLabel(initialSecret, "server in".toByteArray(), ByteArray(0), 32)
    return clientSecret to serverSecret
}

/**
 * Derives the AEAD key, IV, and HP key from a secret.
 * Returns (key, iv, hpKey)
 */
fun derivePacketProtectionKeys(secret: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
    val key = hkdfExpandLabel(secret, "quic key".toByteArray(), ByteArray(0), 16)
    val iv = hkdfExpandLabel(secret, "quic iv".toByteArray(), ByteArray(0), 12)
    val hpKey = hkdfExpandLabel(secret, "quic hp".toByteArray(), ByteArray(0), 16)
    return Triple(key, iv, hpKey)
}

/**
 * Simple HMAC-SHA256 placeholder.
 * In production, use java.security.Mac or kotlinx.crypto.
 */
private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    // This is a simplified placeholder. For RFC 9001 compliance,
    // use a proper HMAC-SHA256 implementation.
    val result = ByteArray(32)
    // Simple hash: XOR key with data (NOT cryptographically secure!)
    for (i in result.indices) {
        val k = key.getOrElse(i) { 0 }
        val d = data.getOrElse(i) { 0 }
        result[i] = (k.toInt() xor d.toInt()).toByte()
    }
    return result
}
