package borg.trikeshed.userspace.nio.spi.digest

import kotlin.coroutines.CoroutineContext

/**
 * Pure-Kotlin SM3 (GB/T 32907-2016) — commonMain default.
 * No platform dependencies.
 */
class DefaultSm3 : Sm3 {
    override val key: CoroutineContext.Key<*> get() = Sm3

    override fun hash(data: ByteArray): ByteArray {
        val padded = pad(data)
        var h0 = 0x7380166f
        var h1 = 0x4914b2b9
        var h2 = 0x172442d7
        var h3 = 0xda8a0600.toInt()
        var h4 = 0xa96f30bc.toInt()
        var h5 = 0x163138aa
        var h6 = 0xe38dee4d.toInt()
        var h7 = 0xb0fb0e4e.toInt()
        var a = h0; var b = h1; var c = h2; var d = h3
        var e = h4; var f = h5; var g = h6; var hv = h7

        var offset = 0
        while (offset < padded.size) {
            val w = IntArray(68)
            val w1 = IntArray(64)
            for (i in 0..15) w[i] = intFrom(padded, offset + i * 4)
            for (i in 16..67) {
                w[i] = p1(w[i - 16] xor w[i - 9] xor ror(w[i - 3], 15)) xor ror(w[i - 13], 7) xor w[i - 6]
            }
            for (i in 0..63) w1[i] = w[i] xor w[i + 4]

            for (i in 0..63) {
                val t = if (i < 16) 0x79cc4519 else 0x7a879d8a
                val ss1 = ror(((ror(a, 12).toLong() + e.toLong() + t) and 0xFFFFFFFFL).toInt(), 7) xor ror(a, 12)
                val ss2 = ss1 xor ror(a, 12)
                val tt1 = ((a.toLong() + d.toLong() + ss2.toLong() + w1[i].toLong()) and 0xFFFFFFFFL).toInt()
                val tt2 = ((e.toLong() + hv.toLong() + ss1.toLong() + w[i].toLong()) and 0xFFFFFFFFL).toInt()
                d = c
                c = ror(b, 9)
                b = a
                a = tt1
                hv = g
                g = ror(f, 13)
                f = e
                e = p0(tt2.toInt())
            }

            a = a xor h0
            b = b xor h1
            c = c xor h2
            d = d xor h3
            e = e xor h4
            f = f xor h5
            g = g xor h6
            hv = hv xor h7

            h0 = a; h1 = b; h2 = c; h3 = d
            h4 = e; h5 = f; h6 = g; h7 = hv

            offset += 64
        }

        val out = ByteArray(32)
        out[0] = ((h0 ushr 24) and 0xFF).toByte()
        out[1] = ((h0 ushr 16) and 0xFF).toByte()
        out[2] = ((h0 ushr 8) and 0xFF).toByte()
        out[3] = (h0 and 0xFF).toByte()
        out[4] = ((h1 ushr 24) and 0xFF).toByte()
        out[5] = ((h1 ushr 16) and 0xFF).toByte()
        out[6] = ((h1 ushr 8) and 0xFF).toByte()
        out[7] = (h1 and 0xFF).toByte()
        out[8] = ((h2 ushr 24) and 0xFF).toByte()
        out[9] = ((h2 ushr 16) and 0xFF).toByte()
        out[10] = ((h2 ushr 8) and 0xFF).toByte()
        out[11] = (h2 and 0xFF).toByte()
        out[12] = ((h3 ushr 24) and 0xFF).toByte()
        out[13] = ((h3 ushr 16) and 0xFF).toByte()
        out[14] = ((h3 ushr 8) and 0xFF).toByte()
        out[15] = (h3 and 0xFF).toByte()
        out[16] = ((h4 ushr 24) and 0xFF).toByte()
        out[17] = ((h4 ushr 16) and 0xFF).toByte()
        out[18] = ((h4 ushr 8) and 0xFF).toByte()
        out[19] = (h4 and 0xFF).toByte()
        out[20] = ((h5 ushr 24) and 0xFF).toByte()
        out[21] = ((h5 ushr 16) and 0xFF).toByte()
        out[22] = ((h5 ushr 8) and 0xFF).toByte()
        out[23] = (h5 and 0xFF).toByte()
        out[24] = ((h6 ushr 24) and 0xFF).toByte()
        out[25] = ((h6 ushr 16) and 0xFF).toByte()
        out[26] = ((h6 ushr 8) and 0xFF).toByte()
        out[27] = (h6 and 0xFF).toByte()
        out[28] = ((h7 ushr 24) and 0xFF).toByte()
        out[29] = ((h7 ushr 16) and 0xFF).toByte()
        out[30] = ((h7 ushr 8) and 0xFF).toByte()
        out[31] = (h7 and 0xFF).toByte()
        return out
    }

    override fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val blockSize = 64
        val key1 = if (key.size > blockSize) hash(key) else key
        val iKeyPad = ByteArray(blockSize) { if (it < key1.size) (key1[it].toInt() xor 0x36).toByte() else 0x36.toByte() }
        val oKeyPad = ByteArray(blockSize) { if (it < key1.size) (key1[it].toInt() xor 0x5c).toByte() else 0x5c.toByte() }
        return hash(oKeyPad + hash(iKeyPad + data))
    }

    private fun ff0(x: Int, y: Int, z: Int): Int = x xor y xor z
    private fun ff1(x: Int, y: Int, z: Int): Int = (x and y) or (x and z) or (y and z)
    private fun gg0(x: Int, y: Int, z: Int): Int = x xor y xor z
    private fun gg1(x: Int, y: Int, z: Int): Int = (x and y) or (x.inv() and z)

    private fun p0(x: Int): Int = x xor ror(x, 9) xor ror(x, 17)
    private fun p1(x: Int): Int = x xor ror(x, 15) xor ror(x, 23)

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

    private fun ror(v: Int, n: Int): Int {
        val s = n and 31
        return ((v ushr s) or (v shl (32 - s)))
    }
}
