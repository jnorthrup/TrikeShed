package borg.trikeshed.userspace.nio.spi.hash

import borg.trikeshed.lib.*

/**
 * Pure-Kotlin MD4 implementation — ported from columnar/cursors/hash/MD4.kt
 * No external dependencies. Outdated but functional.
 */
object MD4 {
    private const val S11 = 3
    private const val S12 = 7
    private const val S13 = 11
    private const val S14 = 19
    private const val S21 = 3
    private const val S22 = 5
    private const val S23 = 9
    private const val S24 = 13
    private const val S31 = 3
    private const val S32 = 9
    private const val S33 = 11
    private const val S34 = 15

    // Round 1 constants
    private const val C11 = 0xd76aa478.toInt()
    private const val C12 = 0xe8c7b756.toInt()
    private const val C13 = 0x242070db.toInt()
    private const val C14 = 0xc1bdceee.toInt()
    private const val C15 = 0xf57c0faf.toInt()
    private const val C16 = 0x4787c62a.toInt()
    private const val C17 = 0xa8304613.toInt()
    private const val C18 = 0xfd469501.toInt()
    private const val C19 = 0x698098d8.toInt()
    private const val C110 = 0x8b44f7af.toInt()
    private const val C111 = 0xffff5bb1.toInt()
    private const val C112 = 0x895cd7be.toInt()
    private const val C113 = 0x6b901122.toInt()
    private const val C114 = 0xfd987193.toInt()
    private const val C115 = 0xa679438e.toInt()
    private const val C116 = 0x49b40821.toInt()

    // Round 2 constants
    private const val C21 = 0xf61e2562.toInt()
    private const val C22 = 0xc040b340.toInt()
    private const val C23 = 0x265e5a51.toInt()
    private const val C24 = 0xe9b6c7aa.toInt()
    private const val C25 = 0xd62f105d.toInt()
    private const val C26 = 0x2441453.toInt()
    private const val C27 = 0xd8a1e681.toInt()
    private const val C28 = 0xe7d3fbc8.toInt()
    private const val C29 = 0x021e7e34.toInt()
    private const val C210 = 0xc33707d6.toInt()
    private const val C211 = 0xf4d50d87.toInt()
    private const val C212 = 0x455a14ed.toInt()
    private const val C213 = 0xa9e3e905.toInt()
    private const val C214 = 0xfcefa3f8.toInt()
    private const val C215 = 0x676f02d9.toInt()
    private const val C216 = 0x8d2a4c8a.toInt()

    // Round 3 constants
    private const val C31 = 0xfffa3942.toInt()
    private const val C32 = 0x8771f681.toInt()
    private const val C33 = 0x6d9d6122.toInt()
    private const val C34 = 0xfde5380c.toInt()
    private const val C35 = 0xa4beea44.toInt()
    private const val C36 = 0x4bdecfa9.toInt()
    private const val C37 = 0xf6bb4b60.toInt()
    private const val C38 = 0xbebfbc70.toInt()
    private const val C39 = 0x289b7ec6.toInt()
    private const val C310 = 0xeaa127fa.toInt()
    private const val C311 = 0xd4ef3085.toInt()
    private const val C312 = 0x04881d05.toInt()
    private const val C313 = 0xd9d4d039.toInt()
    private const val C314 = 0xe6db99e5.toInt()
    private const val C315 = 0x1fa27cf8.toInt()
    private const val C316 = 0xc4ac5665.toInt()

    fun hash(data: ByteArray): ByteArray {
        val ml = data.size.toLong() * 8
        val padded = pad(data)
        val x = IntArray(16)

        // Initialize hash values
        var a = 0x67452301
        var b = 0xefcdab89.toInt()
        var c = 0x98badcfe.toInt()
        var d = 0x10325476

        var offset = 0
        while (offset < padded.size) {
            for (i in 0..15) {
                x[i] = intFrom(padded, offset + i * 4)
            }

            val aa = a; val bb = b; val cc = c; val dd = d

            // Round 1
            a = ff(a, b, c, d, x[0], S11, C11); d = ff(d, a, b, c, x[1], S12, C12)
            c = ff(c, d, a, b, x[2], S13, C13); b = ff(b, c, d, a, x[3], S14, C14)
            a = ff(a, b, c, d, x[4], S11, C15); d = ff(d, a, b, c, x[5], S12, C16)
            c = ff(c, d, a, b, x[6], S13, C17); b = ff(b, c, d, a, x[7], S14, C18)
            a = ff(a, b, c, d, x[8], S11, C19); d = ff(d, a, b, c, x[9], S12, C110)
            c = ff(c, d, a, b, x[10], S13, C111); b = ff(b, c, d, a, x[11], S14, C112)
            a = ff(a, b, c, d, x[12], S11, C113); d = ff(d, a, b, c, x[13], S12, C114)
            c = ff(c, d, a, b, x[14], S13, C115); b = ff(b, c, d, a, x[15], S14, C116)

            // Round 2
            a = gg(a, b, c, d, x[0], S21, C21); d = gg(d, a, b, c, x[4], S22, C22)
            c = gg(c, d, a, b, x[8], S23, C23); b = gg(b, c, d, a, x[12], S24, C24)
            a = gg(a, b, c, d, x[1], S21, C25); d = gg(d, a, b, c, x[5], S22, C26)
            c = gg(c, d, a, b, x[9], S23, C27); b = gg(b, c, d, a, x[13], S24, C28)
            a = gg(a, b, c, d, x[2], S21, C29); d = gg(d, a, b, c, x[6], S22, C210)
            c = gg(c, d, a, b, x[10], S23, C211); b = gg(b, c, d, a, x[14], S24, C212)
            a = gg(a, b, c, d, x[3], S21, C213); d = gg(d, a, b, c, x[7], S22, C214)
            c = gg(c, d, a, b, x[11], S23, C215); b = gg(b, c, d, a, x[15], S24, C216)

            // Round 3
            a = hh(a, b, c, d, x[0], S31, C31); d = hh(d, a, b, c, x[8], S32, C32)
            c = hh(c, d, a, b, x[4], S33, C33); b = hh(b, c, d, a, x[12], S34, C34)
            a = hh(a, b, c, d, x[1], S31, C35); d = hh(d, a, b, c, x[9], S32, C36)
            c = hh(c, d, a, b, x[6], S33, C37); b = hh(b, c, d, a, x[14], S34, C38)
            a = hh(a, b, c, d, x[2], S31, C39); d = hh(d, a, b, c, x[10], S32, C310)
            c = hh(c, d, a, b, x[7], S33, C311); b = hh(b, c, d, a, x[15], S34, C312)
            a = hh(a, b, c, d, x[3], S31, C313); d = hh(d, a, b, c, x[11], S32, C314)
            c = hh(c, d, a, b, x[5], S33, C315); b = hh(b, c, d, a, x[13], S34, C316)

            a = a + aa; b = b + bb; c = c + cc; d = d + dd
            offset += 64
        }

        return byteArrayOf(
            (a ushr 0).toByte(), (a ushr 8).toByte(), (a ushr 16).toByte(), (a ushr 24).toByte(),
            (b ushr 0).toByte(), (b ushr 8).toByte(), (b ushr 16).toByte(), (b ushr 24).toByte(),
            (c ushr 0).toByte(), (c ushr 8).toByte(), (c ushr 16).toByte(), (c ushr 24).toByte(),
            (d ushr 0).toByte(), (d ushr 8).toByte(), (d ushr 16).toByte(), (d ushr 24).toByte()
        )
    }

    private fun ff(a: Int, b: Int, c: Int, d: Int, x: Int, s: Int, ac: Int): Int {
        return rotateLeft((a + ((b and c) or ((b.inv()) and d)) + x + ac).toUInt().toInt(), s)
    }

    private fun gg(a: Int, b: Int, c: Int, d: Int, x: Int, s: Int, ac: Int): Int {
        return rotateLeft((a + ((b and (c or d)) xor (c and d)) + x + ac + 0x5a827999).toUInt().toInt(), s)
    }

    private fun hh(a: Int, b: Int, c: Int, d: Int, x: Int, s: Int, ac: Int): Int {
        return rotateLeft((a + (b xor c xor d) + x + ac + 0x6ed9eba1).toUInt().toInt(), s)
    }

    private fun rotateLeft(v: Int, s: Int): Int =
        ((v shl s) or ((v ushr (32 - s))))

    private fun pad(data: ByteArray): ByteArray {
        val ml = data.size.toLong() * 8
        val padLen = (if ((data.size + 9) % 64 == 0) 0 else 64 - (data.size + 9) % 64) + 9
        val out = ByteArray(data.size + padLen)
        data.copyInto(out, 0)
        out[data.size] = 0x80.toByte()
        var i = 1
        while (i < padLen - 8) {
            out[data.size + i] = 0
            i++
        }
        var j = 0
        while (j < 8) {
            out[out.size - 8 + j] = ((ml ushr (56 - j * 8)) and 0xFF).toByte()
            j++
        }
        return out
    }

    private fun intFrom(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or
        ((b[o + 1].toInt() and 0xFF) shl 16) or
        ((b[o + 2].toInt() and 0xFF) shl 8) or
        (b[o + 3].toInt() and 0xFF)
}

/** MD4 of a CharSequence (encodes to UTF-8), returned as hex CharSeries */
fun CharSequence.md4(): CharSequence = MD4.hash(toString().encodeToByteArray()).hex

/** MD4 of a ByteArray, returned as hex CharSequence */
val ByteArray.md4Hex: CharSequence get() = MD4.hash(this).hex

/** Convert ByteArray to lowercase hex CharSeries */
val ByteArray.hex: CharSequence
    get() {
        val res = CharArray(size shl 1)
        for (ix in indices) get(ix).toInt().also {
            res[ix shl 1] = hexChar((it shr 4) and 0xf)
            res[(ix shl 1) + 1] = hexChar(it and 0xf)
        }
        return CharSeries(res)
    }

private fun hexChar(v: Int): Char = if (v < 10) ('0'.code + v).toChar() else ('a'.code + v - 10).toChar()