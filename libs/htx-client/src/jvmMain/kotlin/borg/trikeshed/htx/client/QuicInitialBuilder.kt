package borg.trikeshed.htx.client

import borg.trikeshed.tls.codec.aead.Aes128Gcm
import borg.trikeshed.tls.codec.hash.Sha256

/**
 * Slim QUIC Initial packet builder (RFC 9000 §17.2.2).
 *
 * Builds a complete QUIC Initial packet: long header + token length 0 +
 * length varint → AEAD_AES_128_GCM encrypted { CRYPTO frame + PADDING }.
 */
internal class QuicInitialBuilder(
    private val aes: Aes128Gcm,
    private val sha256: Sha256,
) {
    companion object {
        val INITIAL_SALT = hexDecode("38762cf7f55934b34d179ae6a4c80cadccbb7f0a") // 20 bytes, RFC 9001 §5.2
        const val V1 = 0x00000001
        const val MAX_DGRAM = 1200

        fun hexDecode(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    fun buildInitial(dcid: ByteArray, scid: ByteArray, cryptoPayload: ByteArray): ByteArray {
        val cryptoFrame = buildCryptoFrame(0, cryptoPayload)

        // ── Sizes ──
        val pnLen = 4
        val hdrSize = 1 + 4 + 1 + dcid.size + 1 + scid.size + vlen(0).size + pnLen  // long hdr + token + PN
        val overhead = hdrSize + vlenSize(cryptoFrame.size.toLong()) + 16  // len field + auth tag
        val padLen = (MAX_DGRAM - overhead - cryptoFrame.size).coerceAtLeast(0)
        val plainPayload = cryptoFrame + ByteArray(padLen) { 0 }
        val payloadLen = plainPayload.size.toLong() + 16 // with auth tag

        // ── QUIC Initial AEAD keys (RFC 9001 §5.2) ──
        // initial_secret = HKDF-Extract(initial_salt, dcid)
        val initialSecret = hkdfExtract(INITIAL_SALT, dcid)
        // QUIC uses its own HKDF-Expand-Label with "quic " prefix (not "tls13 ")
        val key = quicHkdfExpandLabel(initialSecret, "quic key", ByteArray(0), 16)
        val iv  = quicHkdfExpandLabel(initialSecret, "quic iv",  ByteArray(0), 12)
        val hpKey = quicHkdfExpandLabel(initialSecret, "quic hp", ByteArray(0), 16)

        // ── Header (unprotected) + length field = AAD ──
        val hdr = buildInitialHeader(dcid, scid, payloadLen.toInt(), pnLen)
        val aad = hdr

        // ── Encrypt payload ──
        val ciphertext = aes.seal(key, iv, aad, plainPayload)

        // ── Header protection (RFC 9001 §5.4.3) ──
        // Sample: 16 bytes starting at the PN field in the ciphertext
        val sampleStart = pnLen  // PN bytes are at offset 0 of ciphertext, sample starts right after PN
        val sample = ciphertext.copyOfRange(sampleStart, sampleStart + 16)
        val mask = aesEcbEncryptBlock(hpKey, sample)

        // Mask first byte (bits 0-3) + PN field
        val finalHdr = hdr.copyOf()
        finalHdr[0] = (finalHdr[0].toInt() xor (mask[0].toInt() and 0x0F)).toByte()
        for (i in 0 until pnLen) {
            val hi = hdr.size - pnLen + i
            finalHdr[hi] = (finalHdr[hi].toInt() xor mask[1 + i].toInt()).toByte()
        }

        return finalHdr + ciphertext
    }

    // ── Initial header with length field ──

    private fun buildInitialHeader(dcid: ByteArray, scid: ByteArray, payloadLen: Int, pnLen: Int): ByteArray {
        val first = 0xC0.toByte() // Long header, Initial type (0x00 | 0xC0 mask)
        val vl = vlen(payloadLen.toLong())
        val size = 1 + 4 + 1 + dcid.size + 1 + scid.size + vlen(0).size + vl.size + pnLen
        val h = ByteArray(size)
        var p = 0
        h[p++] = first
        writeI32(h, p, V1); p += 4
        h[p++] = dcid.size.toByte(); dcid.copyInto(h, p); p += dcid.size
        h[p++] = scid.size.toByte(); scid.copyInto(h, p); p += scid.size
        val tok = vlen(0); tok.copyInto(h, p); p += tok.size
        vl.copyInto(h, p); p += vl.size
        // PN bytes left as zeros (they'll be masked)
        return h
    }

    // ── CRYPTO frame ──

    private fun buildCryptoFrame(offset: Long, data: ByteArray): ByteArray {
        val typeAndOff = if (offset == 0L) byteArrayOf(0x06) else byteArrayOf(0x06) + vlen(offset)
        return typeAndOff + vlen(data.size.toLong()) + data
    }

    // ── Varint ──

    private fun vlenSize(value: Long): Int = when {
        value <= 63 -> 1; value <= 16383 -> 2; value <= 1073741823 -> 4; else -> 8
    }

    private fun vlen(value: Long): ByteArray = when {
        value <= 63          -> byteArrayOf(value.toByte())
        value <= 16383       -> byteArrayOf(((value ushr 8) or 0x40).toByte(), value.toByte())
        value <= 1073741823  -> byteArrayOf(
            ((value ushr 24) or 0x80).toByte(), (value ushr 16).toByte(),
            (value ushr 8).toByte(), value.toByte())
        else -> byteArrayOf(
            ((value ushr 56) or 0xC0).toByte(), (value ushr 48).toByte(),
            (value ushr 40).toByte(), (value ushr 32).toByte(),
            (value ushr 24).toByte(), (value ushr 16).toByte(),
            (value ushr 8).toByte(), value.toByte())
    }

    // ── AES block encrypt ────────────────────────────── using the full AES-128 implementation from
    // libs/tls DefaultAes128Gcm.

    private fun aesEcbEncryptBlock(key: ByteArray, block: ByteArray): ByteArray {
        val s = intArrayOf(
            0x63,0x7c,0x77,0x7b,0xf2,0x6b,0x6f,0xc5,0x30,0x01,0x67,0x2b,0xfe,0xd7,0xab,0x76,
            0xca,0x82,0xc9,0x7d,0xfa,0x59,0x47,0xf0,0xad,0xd4,0xa2,0xaf,0x9c,0xa4,0x72,0xc0,
            0xb7,0xfd,0x93,0x26,0x36,0x3f,0xf7,0xcc,0x34,0xa5,0xe5,0xf1,0x71,0xd8,0x31,0x15,
            0x04,0xc7,0x23,0xc3,0x18,0x96,0x05,0x9a,0x07,0x12,0x80,0xe2,0xeb,0x27,0xb2,0x75,
            0x09,0x83,0x2c,0x1a,0x1b,0x6e,0x5a,0xa0,0x52,0x3b,0xd6,0xb3,0x29,0xe3,0x2f,0x84,
            0x53,0xd1,0x00,0xed,0x20,0xfc,0xb1,0x5b,0x6a,0xcb,0xbe,0x39,0x4a,0x4c,0x58,0xcf,
            0xd0,0xef,0xaa,0xfb,0x43,0x4d,0x33,0x85,0x45,0xf9,0x02,0x7f,0x50,0x3c,0x9f,0xa8,
            0x51,0xa3,0x40,0x8f,0x92,0x9d,0x38,0xf5,0xbc,0xb6,0xda,0x21,0x10,0xff,0xf3,0xd2,
            0xcd,0x0c,0x13,0xec,0x5f,0x97,0x44,0x17,0xc4,0xa7,0x7e,0x3d,0x64,0x5d,0x19,0x73,
            0x60,0x81,0x4f,0xdc,0x22,0x2a,0x90,0x88,0x46,0xee,0xb8,0x14,0xde,0x5e,0x0b,0xdb,
            0xe0,0x32,0x3a,0x0a,0x49,0x06,0x24,0x5c,0xc2,0xd3,0xac,0x62,0x91,0x95,0xe4,0x79,
            0xe7,0xc8,0x37,0x6d,0x8d,0xd5,0x4e,0xa9,0x6c,0x56,0xf4,0xea,0x65,0x7a,0xae,0x08,
            0xba,0x78,0x25,0x2e,0x1c,0xa6,0xb4,0xc6,0xe8,0xdd,0x74,0x1f,0x4b,0xbd,0x8b,0x8a,
            0x70,0x3e,0xb5,0x66,0x48,0x03,0xf6,0x0e,0x61,0x35,0x57,0xb9,0x86,0xc1,0x1d,0x9e,
            0xe1,0xf8,0x98,0x11,0x69,0xd9,0x8e,0x94,0x9b,0x1e,0x87,0xe9,0xce,0x55,0x28,0xdf,
            0x8c,0xa1,0x89,0x0d,0xbf,0xe6,0x42,0x68,0x41,0x99,0x2d,0x0f,0xb0,0x54,0xbb,0x16)
        fun subWord(w: Int): Int =
            ((s[(w ushr 24) and 0xFF]) shl 24) or ((s[(w ushr 16) and 0xFF]) shl 16) or
            ((s[(w ushr 8) and 0xFF]) shl 8) or (s[w and 0xFF])
        fun rotWord(w: Int) = (w shl 8) or (w ushr 24)
        val rcon = intArrayOf(0x01,0x02,0x04,0x08,0x10,0x20,0x40,0x80,0x1b,0x36)

        val nk = 4; val nr = 10; val w = IntArray(4 * (nr + 1))
        for (i in 0 until nk) w[i] = i32(key, i * 4)
        for (i in nk until w.size) {
            var t = w[i - 1]
            if (i % nk == 0) t = subWord(rotWord(t)) xor rcon[i / nk - 1]
            w[i] = w[i - nk] xor t
        }

        var s0 = i32(block, 0); var s1 = i32(block, 4)
        var s2 = i32(block, 8); var s3 = i32(block, 12)
        s0 = s0 xor w[0]; s1 = s1 xor w[1]; s2 = s2 xor w[2]; s3 = s3 xor w[3]

        for (r in 1..nr) {
            s0 = subWord(s0); s1 = subWord(s1); s2 = subWord(s2); s3 = subWord(s3)
            val t0 = (s0 and 0xFF000000.toInt()) or (s1 and 0x00FF0000) or (s2 and 0x0000FF00) or (s3 and 0x000000FF)
            val t1 = (s1 and 0xFF000000.toInt()) or (s2 and 0x00FF0000) or (s3 and 0x0000FF00) or (s0 and 0x000000FF)
            val t2 = (s2 and 0xFF000000.toInt()) or (s3 and 0x00FF0000) or (s0 and 0x0000FF00) or (s1 and 0x000000FF)
            val t3 = (s3 and 0xFF000000.toInt()) or (s0 and 0x00FF0000) or (s1 and 0x0000FF00) or (s2 and 0x000000FF)
            s0 = t0; s1 = t1; s2 = t2; s3 = t3
            if (r < nr) {
                fun gm(a: Int, b: Int): Int { var p = 0; var aa = a; var bb = b; for (k in 0..7) { if (bb and 1 != 0) p = p xor aa; val h = aa and 0x80; aa = (aa shl 1) and 0xFF; if (h != 0) aa = aa xor 0x1b; bb = bb shr 1 }; return p }
                fun mc(c: Int): Int {
                    val a0 = (c ushr 24) and 0xFF; val a1 = (c ushr 16) and 0xFF
                    val a2 = (c ushr 8) and 0xFF; val a3 = c and 0xFF
                    return (gm(2, a0) xor gm(3, a1) xor a2 xor a3 shl 24) or
                        (a0 xor gm(2, a1) xor gm(3, a2) xor a3 shl 16) or
                        (a0 xor a1 xor gm(2, a2) xor gm(3, a3) shl 8) or
                        (gm(3, a0) xor a1 xor a2 xor gm(2, a3))
                }
                s0 = mc(s0); s1 = mc(s1); s2 = mc(s2); s3 = mc(s3)
            }
            s0 = s0 xor w[r * 4]; s1 = s1 xor w[r * 4 + 1]
            s2 = s2 xor w[r * 4 + 2]; s3 = s3 xor w[r * 4 + 3]
        }

        return byteArrayOf(
            ((s0 ushr 24) and 0xFF).toByte(), ((s0 ushr 16) and 0xFF).toByte(),
            ((s0 ushr 8) and 0xFF).toByte(), (s0 and 0xFF).toByte(),
            ((s1 ushr 24) and 0xFF).toByte(), ((s1 ushr 16) and 0xFF).toByte(),
            ((s1 ushr 8) and 0xFF).toByte(), (s1 and 0xFF).toByte(),
            ((s2 ushr 24) and 0xFF).toByte(), ((s2 ushr 16) and 0xFF).toByte(),
            ((s2 ushr 8) and 0xFF).toByte(), (s2 and 0xFF).toByte(),
            ((s3 ushr 24) and 0xFF).toByte(), ((s3 ushr 16) and 0xFF).toByte(),
            ((s3 ushr 8) and 0xFF).toByte(), (s3 and 0xFF).toByte())
    }

    private fun i32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o+1].toInt() and 0xFF) shl 16) or
        ((b[o+2].toInt() and 0xFF) shl 8) or (b[o+3].toInt() and 0xFF)

    private fun writeI32(dst: ByteArray, off: Int, v: Int) {
        dst[off+3] = (v and 0xFF).toByte(); dst[off+2] = ((v ushr 8) and 0xFF).toByte()
        dst[off+1] = ((v ushr 16) and 0xFF).toByte(); dst[off] = ((v ushr 24) and 0xFF).toByte()
    }

    // ── HKDF with QUIC labels (RFC 9001 §5.1) ──
    // QUIC uses "quic " prefix for labels, not "tls13 "

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray =
        sha256.hmac(salt, ikm)

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val hl = 32
        val n = (length + hl - 1) / hl
        val r = ByteArray(length)
        var tp = ByteArray(0)
        var off = 0
        for (i in 1..n) {
            tp = sha256.hmac(prk, tp + info + byteArrayOf(i.toByte()))
            val cl = minOf(tp.size, length - off)
            tp.copyInto(r, off, 0, cl); off += cl
        }
        return r
    }

    private fun quicHkdfExpandLabel(secret: ByteArray, label: String, context: ByteArray, length: Int): ByteArray {
        val lb = "tls13 $label".encodeToByteArray()  // QUIC HKDF-Expand-Label uses "tls13 " prefix (RFC 8446 §7.1)
        val l = ByteArray(2 + 1 + lb.size + 1 + context.size)
        var p = 0
        l[p++] = ((length ushr 8) and 0xFF).toByte(); l[p++] = (length and 0xFF).toByte()
        l[p++] = lb.size.toByte(); lb.copyInto(l, p); p += lb.size
        l[p++] = context.size.toByte(); context.copyInto(l, p)
        return hkdfExpand(secret, l, length)
    }
}