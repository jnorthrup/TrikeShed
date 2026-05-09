package borg.trikeshed.tls.codec.kdf

import borg.trikeshed.tls.codec.hash.Sha256

/**
 * Pure-Kotlin HKDF-SHA-256 (RFC 5869) — commonMain default.
 *
 * Builds on a [Sha256] instance (injected, not expect/actual).
 * Use `DefaultHkdfSha256(DefaultSha256())` for a self-contained default.
 */
class DefaultHkdfSha256(
    private val sha256: Sha256,
) : HkdfSha256 {
    override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = HkdfSha256.Key

    override fun extract(salt: ByteArray?, ikm: ByteArray): ByteArray {
        val s = salt ?: ByteArray(hashLen)
        return sha256.hmac(s, ikm)
    }

    override fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val n = (length + hashLen - 1) / hashLen
        val result = ByteArray(length)
        var tPrev = ByteArray(0)
        var offset = 0
        for (i in 1..n) {
            tPrev = sha256.hmac(prk, tPrev + info + byteArrayOf(i.toByte()))
            val copyLen = minOf(tPrev.size, length - offset)
            tPrev.copyInto(result, offset, 0, copyLen)
            offset += copyLen
        }
        return result
    }

    override fun expandLabel(secret: ByteArray, label: String, context: ByteArray, length: Int): ByteArray {
        val labelBytes = "tls13 $label".encodeToByteArray()
        // struct HkdfLabel { uint16 length; opaque label<7..255>; opaque context<0..255>; }
        val hkdfLabel = ByteArray(2 + 1 + labelBytes.size + 1 + context.size)
        var p = 0
        hkdfLabel[p++] = ((length ushr 8) and 0xFF).toByte(); hkdfLabel[p++] = (length and 0xFF).toByte()
        hkdfLabel[p++] = labelBytes.size.toByte(); labelBytes.copyInto(hkdfLabel, p); p += labelBytes.size
        hkdfLabel[p++] = context.size.toByte(); context.copyInto(hkdfLabel, p)
        return expand(secret, hkdfLabel, length)
    }
}
