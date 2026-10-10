package borg.trikeshed.job

import borg.trikeshed.userspace.nio.platform.spi.*

/** RFC 8439 2.3-2.4 ChaCha20: 32-bit words, little endian as the RFC defines, 20 rounds, 96-bit nonce. */
object ChaCha20 {
    /** The input state: "expand 32-byte k", the key, the block counter, the nonce. */
    fun state(key: ByteArray, counter: Int, nonce: ByteArray): IntArray {
        require(key.size == 32 && nonce.size == 12) { "ChaCha20 needs a 32-byte key and a 12-byte nonce" }
        return intArrayOf(
            0x61707865, 0x3320646e, 0x79622d32, 0x6b206574,
            le32(key, 0), le32(key, 4), le32(key, 8), le32(key, 12),
            le32(key, 16), le32(key, 20), le32(key, 24), le32(key, 28),
            counter, le32(nonce, 0), le32(nonce, 4), le32(nonce, 8),
        )
    }

    /** RFC 8439 2.3 block function: x = 20 rounds of [state] added to [state]. */
    fun block(state: IntArray, x: IntArray) {
        var x0 = state[0]; var x1 = state[1]; var x2 = state[2]; var x3 = state[3]
        var x4 = state[4]; var x5 = state[5]; var x6 = state[6]; var x7 = state[7]
        var x8 = state[8]; var x9 = state[9]; var x10 = state[10]; var x11 = state[11]
        var x12 = state[12]; var x13 = state[13]; var x14 = state[14]; var x15 = state[15]
        repeat(10) {
            x0 += x4; x12 = (x12 xor x0).rotateLeft(16); x8 += x12; x4 = (x4 xor x8).rotateLeft(12)
            x0 += x4; x12 = (x12 xor x0).rotateLeft(8); x8 += x12; x4 = (x4 xor x8).rotateLeft(7)
            x1 += x5; x13 = (x13 xor x1).rotateLeft(16); x9 += x13; x5 = (x5 xor x9).rotateLeft(12)
            x1 += x5; x13 = (x13 xor x1).rotateLeft(8); x9 += x13; x5 = (x5 xor x9).rotateLeft(7)
            x2 += x6; x14 = (x14 xor x2).rotateLeft(16); x10 += x14; x6 = (x6 xor x10).rotateLeft(12)
            x2 += x6; x14 = (x14 xor x2).rotateLeft(8); x10 += x14; x6 = (x6 xor x10).rotateLeft(7)
            x3 += x7; x15 = (x15 xor x3).rotateLeft(16); x11 += x15; x7 = (x7 xor x11).rotateLeft(12)
            x3 += x7; x15 = (x15 xor x3).rotateLeft(8); x11 += x15; x7 = (x7 xor x11).rotateLeft(7)
            x0 += x5; x15 = (x15 xor x0).rotateLeft(16); x10 += x15; x5 = (x5 xor x10).rotateLeft(12)
            x0 += x5; x15 = (x15 xor x0).rotateLeft(8); x10 += x15; x5 = (x5 xor x10).rotateLeft(7)
            x1 += x6; x12 = (x12 xor x1).rotateLeft(16); x11 += x12; x6 = (x6 xor x11).rotateLeft(12)
            x1 += x6; x12 = (x12 xor x1).rotateLeft(8); x11 += x12; x6 = (x6 xor x11).rotateLeft(7)
            x2 += x7; x13 = (x13 xor x2).rotateLeft(16); x8 += x13; x7 = (x7 xor x8).rotateLeft(12)
            x2 += x7; x13 = (x13 xor x2).rotateLeft(8); x8 += x13; x7 = (x7 xor x8).rotateLeft(7)
            x3 += x4; x14 = (x14 xor x3).rotateLeft(16); x9 += x14; x4 = (x4 xor x9).rotateLeft(12)
            x3 += x4; x14 = (x14 xor x3).rotateLeft(8); x9 += x14; x4 = (x4 xor x9).rotateLeft(7)
        }
        x[0] = x0 + state[0]; x[1] = x1 + state[1]; x[2] = x2 + state[2]; x[3] = x3 + state[3]
        x[4] = x4 + state[4]; x[5] = x5 + state[5]; x[6] = x6 + state[6]; x[7] = x7 + state[7]
        x[8] = x8 + state[8]; x[9] = x9 + state[9]; x[10] = x10 + state[10]; x[11] = x11 + state[11]
        x[12] = x12 + state[12]; x[13] = x13 + state[13]; x[14] = x14 + state[14]; x[15] = x15 + state[15]
    }

    /** The serialized 64-byte block [counter] (RFC 8439 2.3.2). */
    fun block(key: ByteArray, counter: Int, nonce: ByteArray): ByteArray = encrypt(key, counter, nonce, ByteArray(64))

    /** RFC 8439 2.4: [input] xor the key stream of blocks [counter], counter + 1, ... */
    fun encrypt(key: ByteArray, counter: Int, nonce: ByteArray, input: ByteArray): ByteArray {
        val state = state(key, counter, nonce)
        val x = IntArray(16)
        val output = ByteArray(input.size)
        var at = 0
        while (at < input.size) {
            block(state, x)
            if (input.size - at >= 64) {
                for (w in 0..7) {
                    val o = at + 8 * w
                    val k = (x[2 * w].toLong() and 0xffffffffL) or (x[2 * w + 1].toLong() shl 32)
                    output.littleEndianSetLongAt(o, input.littleEndianGetLongAt(o) xor k)
                }
            } else for (i in 0 until input.size - at) {
                output[at + i] = (input[at + i].toInt() xor (x[i shr 2] ushr (i and 3 shl 3))).toByte()
            }
            state[12]++
            at += 64
        }
        return output
    }

    fun le32(b: ByteArray, offset: Int): Int = (b[offset].toInt() and 0xff) or ((b[offset + 1].toInt() and 0xff) shl 8) or
        ((b[offset + 2].toInt() and 0xff) shl 16) or (b[offset + 3].toInt() shl 24)
}

/**
 * RFC 8439 2.5 Poly1305 one-time authenticator: the accumulator mod 2^130 - 5 in five 26-bit limbs
 * (poly1305-donna 32-bit), the clamped r and the s half of the 32-byte one-time [key].
 */
class Poly1305(key: ByteArray) {
    val r0: Long
    val r1: Long
    val r2: Long
    val r3: Long
    val r4: Long
    val s = key.copyOfRange(16, 32)
    var h0 = 0L
    var h1 = 0L
    var h2 = 0L
    var h3 = 0L
    var h4 = 0L

    init {
        require(key.size == 32) { "Poly1305 needs a 32-byte one-time key" }
        r0 = le32(key, 0) and 0x3ffffff
        r1 = (le32(key, 3) ushr 2) and 0x3ffff03
        r2 = (le32(key, 6) ushr 4) and 0x3ffc0ff
        r3 = (le32(key, 9) ushr 6) and 0x3f03fff
        r4 = (le32(key, 12) ushr 8) and 0x00fffff
    }

    /**
     * Absorbs [length] bytes of [m] from [offset] as 16-byte blocks. A short last block gets 0x01 and
     * zeros (RFC 8439 2.5.1), so it ends the message; with [pad16] it is zero padded to a full block,
     * the pad16 of the AEAD construction (2.8).
     */
    fun update(m: ByteArray, offset: Int, length: Int, pad16: Boolean = false) {
        var at = offset
        val end = offset + length
        while (end - at >= 16) {
            block(m, at, 1L shl 24)
            at += 16
        }
        if (at < end) {
            val last = ByteArray(16)
            m.copyInto(last, 0, at, end)
            if (pad16) block(last, 0, 1L shl 24)
            else {
                last[end - at] = 1
                block(last, 0, 0)
            }
        }
    }

    /** h = (h + block + hibit) r mod 2^130 - 5, partially reduced. */
    fun block(m: ByteArray, at: Int, hibit: Long) {
        val low = m.littleEndianGetLongAt(at)
        val high = m.littleEndianGetLongAt(at + 8)
        h0 += low and 0x3ffffff
        h1 += (low ushr 26) and 0x3ffffff
        h2 += ((low ushr 52) or (high shl 12)) and 0x3ffffff
        h3 += (high ushr 14) and 0x3ffffff
        h4 += (high ushr 40) or hibit
        val s1 = r1 * 5
        val s2 = r2 * 5
        val s3 = r3 * 5
        val s4 = r4 * 5
        val d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1
        var d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2
        var d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3
        var d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4
        var d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0
        var c = d0 ushr 26
        h0 = d0 and 0x3ffffff
        d1 += c; c = d1 ushr 26; h1 = d1 and 0x3ffffff
        d2 += c; c = d2 ushr 26; h2 = d2 and 0x3ffffff
        d3 += c; c = d3 ushr 26; h3 = d3 and 0x3ffffff
        d4 += c; c = d4 ushr 26; h4 = d4 and 0x3ffffff
        h0 += c * 5; c = h0 ushr 26; h0 = h0 and 0x3ffffff
        h1 += c
    }

    /** The 16-byte tag: h fully reduced mod 2^130 - 5, plus s mod 2^128. */
    fun finish(): ByteArray {
        var c = h1 ushr 26; h1 = h1 and 0x3ffffff
        h2 += c; c = h2 ushr 26; h2 = h2 and 0x3ffffff
        h3 += c; c = h3 ushr 26; h3 = h3 and 0x3ffffff
        h4 += c; c = h4 ushr 26; h4 = h4 and 0x3ffffff
        h0 += c * 5; c = h0 ushr 26; h0 = h0 and 0x3ffffff
        h1 += c
        var g0 = h0 + 5; c = g0 ushr 26; g0 = g0 and 0x3ffffff
        var g1 = h1 + c; c = g1 ushr 26; g1 = g1 and 0x3ffffff
        var g2 = h2 + c; c = g2 ushr 26; g2 = g2 and 0x3ffffff
        var g3 = h3 + c; c = g3 ushr 26; g3 = g3 and 0x3ffffff
        val g4 = h4 + c - (1L shl 26)
        // h - p borrowed (g4 < 0): keep h; otherwise take g = h - p.
        val keep = g4 shr 63
        h0 = (h0 and keep) or (g0 and keep.inv())
        h1 = (h1 and keep) or (g1 and keep.inv())
        h2 = (h2 and keep) or (g2 and keep.inv())
        h3 = (h3 and keep) or (g3 and keep.inv())
        h4 = (h4 and keep) or (g4 and keep.inv())
        var f = ((h0 or (h1 shl 26)) and 0xffffffffL) + le32(s, 0)
        val tag = ByteArray(16)
        putLe32(tag, 0, f)
        f = (((h1 ushr 6) or (h2 shl 20)) and 0xffffffffL) + le32(s, 4) + (f ushr 32)
        putLe32(tag, 4, f)
        f = (((h2 ushr 12) or (h3 shl 14)) and 0xffffffffL) + le32(s, 8) + (f ushr 32)
        putLe32(tag, 8, f)
        f = (((h3 ushr 18) or (h4 shl 8)) and 0xffffffffL) + le32(s, 12) + (f ushr 32)
        putLe32(tag, 12, f)
        return tag
    }

    fun le32(b: ByteArray, offset: Int): Long = ChaCha20.le32(b, offset).toLong() and 0xffffffffL

    fun putLe32(b: ByteArray, offset: Int, v: Long) {
        for (i in 0..3) b[offset + i] = (v ushr (8 * i)).toByte()
    }
}

/** RFC 8439 2.8 AEAD_CHACHA20_POLY1305: 32-byte key, 12-byte nonce, 16-byte tag after the ciphertext. */
object ChaCha20Poly1305 {
    fun encrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val ciphertext = ChaCha20.encrypt(key, 1, nonce, plaintext)
        return ciphertext + tag(key, nonce, aad, ciphertext, ciphertext.size)
    }

    /** The plaintext, or null when the tag does not authenticate [aad] and the ciphertext. */
    fun decrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray? {
        if (ciphertext.size < 16) return null
        val length = ciphertext.size - 16
        val tag = tag(key, nonce, aad, ciphertext, length)
        var difference = 0
        for (i in 0..15) difference = difference or (tag[i].toInt() xor ciphertext[length + i].toInt())
        return if (difference == 0) ChaCha20.encrypt(key, 1, nonce, ciphertext.copyOf(length)) else null
    }

    /** Poly1305 keyed by block 0 (2.6) over aad | pad16 | ciphertext | pad16 | le64(aad length) | le64(ciphertext length). */
    fun tag(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray, length: Int): ByteArray {
        val mac = Poly1305(ChaCha20.block(key, 0, nonce).copyOf(32))
        mac.update(aad, 0, aad.size, pad16 = true)
        mac.update(ciphertext, 0, length, pad16 = true)
        val lengths = ByteArray(16)
        for (i in 0..3) {
            lengths[i] = (aad.size ushr (8 * i)).toByte()
            lengths[8 + i] = (length ushr (8 * i)).toByte()
        }
        mac.update(lengths, 0, 16)
        return mac.finish()
    }
}
