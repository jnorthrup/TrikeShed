package borg.literbike.ccek.quic.tls_crypto

// ============================================================================
// Secrets Derivation -- ported from tls_crypto/secrets.rs
// QUIC TLS 1.3 secrets generation (RFC 9001) using HMAC-SHA256 HKDF
// ============================================================================

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The QUIC v1 Initial Salt as defined in RFC 9001 Section 5.2 */
private val QUIC_V1_INITIAL_SALT = byteArrayOf(
    0x38, 0x76, 0x2c, 0xf7, 0xf5, 0x59, 0x34, 0xb3,
    0x4d, 0x17, 0x9a, 0xe6, 0xa4, 0xc8, 0x0c, 0xad,
    0xcc, 0xbb, 0x7f, 0x0a
).map { it.toUByte() }

/** HMAC-SHA256 */
private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

/** HKDF-Extract(salt, IKM) -> PRK */
private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
    return hmacSha256(salt, ikm)
}

/** HKDF-Expand(PRK, info, L) -> OKM */
private fun hkdfExpand(prk: ByteArray, info: ByteArray, len: Int): ByteArray {
    val out = mutableListOf<Byte>()
    var t: ByteArray = ByteArray(0)
    var counter: UByte = 1u

    while (out.size < len) {
        val data = t.toMutableList()
        data.addAll(info.toList())
        data.add(counter.toByte())
        t = hmacSha256(prk, data.toByteArray())
        out.addAll(t.toList())
        counter++
    }

    return out.subList(0, len).toByteArray()
}

/**
 * RFC 9001 Section 5.1: HKDF-Expand-Label
 *
 * hkdf_expand_label(PRK, label, context, length):
 *   HKDF-Expand(PRK, HkdfLabel, length)
 *
 * Where HkdfLabel is:
 *   struct {
 *       uint16 length = Length;
 *       opaque label<7..255> = "tls13 " + Label;
 *       opaque context<0..255> = Context;
 *   } HkdfLabel;
 */
fun hkdfExpandLabel(prk: ByteArray, label: ByteArray, context: ByteArray, len: Int): ByteArray {
    val hkdfLabel = mutableListOf<Byte>()
    // length (uint16)
    hkdfLabel.add(((len shr 8) and 0xFF).toByte())
    hkdfLabel.add((len and 0xFF).toByte())
    // label = "tls13 " + label (length-prefixed)
    val fullLabel = byteArrayOf(0x74u.toByte(), 0x6cu.toByte(), 0x73u.toByte(),
                                0x31u.toByte(), 0x33u.toByte(), 0x20u.toByte()) + label
    hkdfLabel.add(fullLabel.size.toByte())
    hkdfLabel.addAll(fullLabel.toList())
    // context (length-prefixed)
    hkdfLabel.add(context.size.toByte())
    hkdfLabel.addAll(context.toList())

    return hkdfExpand(prk, hkdfLabel.toByteArray(), len)
}

/**
 * Extracts initial secrets based on the Destination Connection ID.
 * Returns (client_initial_secret, server_initial_secret)
 *
 * RFC 9001 Section 5.2: initial_secret = HKDF-Extract(initial_salt, client_dst_conn_id)
 *   client_initial_secret = HKDF-Expand-Label(initial_secret, "client in", "", key_length)
 *   server_initial_secret = HKDF-Expand-Label(initial_secret, "server in", "", key_length)
 */
fun deriveInitialSecrets(clientDstConnId: ByteArray): Pair<ByteArray, ByteArray> {
    val initialSecret = hkdfExtract(
        QUIC_V1_INITIAL_SALT.toByteArray(),
        clientDstConnId
    )
    val clientSecret = hkdfExpandLabel(initialSecret, "client in".encodeToByteArray(), ByteArray(0), 32)
    val serverSecret = hkdfExpandLabel(initialSecret, "server in".encodeToByteArray(), ByteArray(0), 32)
    return clientSecret to serverSecret
}

/**
 * Derives the AEAD key, IV, and HP key from a secret (raw bytes).
 * Returns (key, iv, hp_key)
 *
 * RFC 9001 Section 5:
 *   key = HKDF-Expand-Label(secret, "quic key", "", key_length)
 *   iv  = HKDF-Expand-Label(secret, "quic iv", "", iv_length)
 *   hp  = HKDF-Expand-Label(secret, "quic hp", "", hp_key_length)
 */
fun derivePacketProtectionKeys(secret: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
    val key = hkdfExpandLabel(secret, "quic key".encodeToByteArray(), ByteArray(0), 16)
    val iv = hkdfExpandLabel(secret, "quic iv".encodeToByteArray(), ByteArray(0), 12)
    val hpKey = hkdfExpandLabel(secret, "quic hp".encodeToByteArray(), ByteArray(0), 16)
    return Triple(key, iv, hpKey)
}

/** Convert List<UByte> to ByteArray */
private fun List<UByte>.toByteArray(): ByteArray = this.map { it.toByte() }.toByteArray()

/**
 * Test vector from RFC 9001 Appendix A.1
 * DCID = 0x8394c8f03e515708
 */
fun verifyRfc9001TestVector(): Boolean {
    val cid = hexToBytes("8394c8f03e515708")
    val (clientSecret, serverSecret) = deriveInitialSecrets(cid)
    val (clientKey, clientIv, clientHp) = derivePacketProtectionKeys(clientSecret)
    val (serverKey, serverIv, serverHp) = derivePacketProtectionKeys(serverSecret)

    val expectedClientKey = hexToBytes("1f369613dd76d5467730efcbe3b1a22d")
    val expectedClientIv = hexToBytes("fa044b2f42a3fd3b46fb255c")
    val expectedClientHp = hexToBytes("9f50449e04a0e810283a1e9933adedd2")
    val expectedServerKey = hexToBytes("cf3a5331653c364c88f0f379b6067e37")
    val expectedServerIv = hexToBytes("0ac1493ca1905853b0bba03e")
    val expectedServerHp = hexToBytes("c206b8d9b9f0f37644430b490eeaa314")

    return clientKey.contentEquals(expectedClientKey) &&
           clientIv.contentEquals(expectedClientIv) &&
           clientHp.contentEquals(expectedClientHp) &&
           serverKey.contentEquals(expectedServerKey) &&
           serverIv.contentEquals(expectedServerIv) &&
           serverHp.contentEquals(expectedServerHp)
}

/** Hex string to ByteArray */
private fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "Hex string must have even length" }
    return ByteArray(hex.length / 2) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}
