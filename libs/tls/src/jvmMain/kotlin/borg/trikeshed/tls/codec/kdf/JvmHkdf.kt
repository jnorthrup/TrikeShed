package borg.trikeshed.tls.codec.kdf

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * JVM HKDF-SHA-256 via javax.crypto.Mac.
 *
 * Implements RFC 5869 HKDF-Extract, HKDF-Expand, and TLS 1.3
 * HKDF-Expand-Label (RFC 8446 §7.1).
 */
class JvmHkdfSha256 : HkdfSha256 {
    override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = HkdfSha256.Key

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    override fun extract(salt: ByteArray?, ikm: ByteArray): ByteArray {
        val s = salt ?: ByteArray(hashLen)  // zeros per RFC 5869 §2.2
        return hmacSha256(s, ikm)
    }

    override fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val n = (length + hashLen - 1) / hashLen
        val result = ByteArray(length)
        var tPrev = ByteArray(0)
        var offset = 0
        for (i in 1..n) {
            tPrev = hmacSha256(prk, tPrev + info + byteArrayOf(i.toByte()))
            val copyLen = minOf(tPrev.size, length - offset)
            tPrev.copyInto(result, offset, 0, copyLen)
            offset += copyLen
        }
        return result
    }

    override fun expandLabel(secret: ByteArray, label: CharSequence, context: ByteArray, length: Int): ByteArray {
        val labelBytes = "tls13 $label".encodeToByteArray()
        val hkdfLabel = ByteArray(2 + 1 + labelBytes.size + 1 + context.size + 2 + length)
        var p = 0
        // uint16 length
        hkdfLabel[p++] = ((length ushr 8) and 0xFF).toByte()
        hkdfLabel[p++] = (length and 0xFF).toByte()
        // opaque label<7..255>
        hkdfLabel[p++] = labelBytes.size.toByte()
        labelBytes.copyInto(hkdfLabel, p); p += labelBytes.size
        // opaque context<0..255>
        hkdfLabel[p++] = context.size.toByte()
        context.copyInto(hkdfLabel, p)
        return expand(secret, hkdfLabel, length)
    }
}
