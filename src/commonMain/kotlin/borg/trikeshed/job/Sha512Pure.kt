package borg.trikeshed.job

/** FIPS 180-4 SHA-512; message words and the length field are big endian as the standard defines. */
object Sha512Pure {
    val K = longArrayOf(
        0x428a2f98d728ae22, 0x7137449123ef65cd, -0x4a3f043013b2c4d1, -0x164a245a7e762444,
        0x3956c25bf348b538, 0x59f111f1b605d019, -0x6dc07d5b50e6b065, -0x54e3a12a25927ee8,
        -0x27f855675cfcfdbe, 0x12835b0145706fbe, 0x243185be4ee4b28c, 0x550c7dc3d5ffb4e2,
        0x72be5d74f27b896f, -0x7f214e01c4e9694f, -0x6423f958da38edcb, -0x3e640e8b3096d96c,
        -0x1b64963e610eb52e, -0x1041b879c7b0da1d, 0x0fc19dc68b8cd5b5, 0x240ca1cc77ac9c65,
        0x2de92c6f592b0275, 0x4a7484aa6ea6e483, 0x5cb0a9dcbd41fbd4, 0x76f988da831153b5,
        -0x67c1aead11992055, -0x57ce3992d24bcdf0, -0x4ffcd8376704dec1, -0x40a680384110f11c,
        -0x391ff40cc257703e, -0x2a586eb86cf558db, 0x06ca6351e003826f, 0x142929670a0e6e70,
        0x27b70a8546d22ffc, 0x2e1b21385c26c926, 0x4d2c6dfc5ac42aed, 0x53380d139d95b3df,
        0x650a73548baf63de, 0x766a0abb3c77b2a8, -0x7e3d36d1b812511a, -0x6d8dd37aeb7dcac5,
        -0x5d40175eb30efc9c, -0x57e599b443bdcfff, -0x3db4748f2f07686f, -0x3893ae5cf9ab41d0,
        -0x2e6d17e62910ade8, -0x2966f9dbaa9a56f0, -0x0bf1ca7aa88edfd6, 0x106aa07032bbd1b8,
        0x19a4c116b8d2d0c8, 0x1e376c085141ab53, 0x2748774cdf8eeb99, 0x34b0bcb5e19b48a8,
        0x391c0cb3c5c95a63, 0x4ed8aa4ae3418acb, 0x5b9cca4f7763e373, 0x682e6ff3d6b2b8a3,
        0x748f82ee5defb2fc, 0x78a5636f43172f60, -0x7b3787eb5e0f548e, -0x7338fdf7e59bc614,
        -0x6f410005dc9ce1d8, -0x5baf9314217d4217, -0x41065c084d3986eb, -0x398e870d1c8dacd5,
        -0x35d8c13115d99e64, -0x2e794738de3f3df9, -0x15258229321f14e2, -0x0a82b08011912e88,
        0x06f067aa72176fba, 0x0a637dc5a2c898a6, 0x113f9804bef90dae, 0x1b710b35131c471b,
        0x28db77f523047d84, 0x32caab7b40c72493, 0x3c9ebe0a15c9bebc, 0x431d67c49c100d4c,
        0x4cc5d4becb3e42b6, 0x597f299cfc657e2a, 0x5fcb6fab3ad6faec, 0x6c44198c4a475817,
    )

    /** SHA-512 of the concatenation of [input], without copying it. */
    fun digest(vararg input: ByteArray): ByteArray {
        val h = longArrayOf(
            0x6a09e667f3bcc908, -0x4498517a7b3558c5, 0x3c6ef372fe94f82b, -0x5ab00ac5a0e2c90f,
            0x510e527fade682d1, -0x64fa9773d4c193e1, 0x1f83d9abfb41bd6b, 0x5be0cd19137e2179,
        )
        val w = LongArray(80)
        val block = ByteArray(128)
        var buffered = 0
        var length = 0L
        for (part in input) {
            length += part.size
            var at = 0
            if (buffered > 0) {
                at = minOf(128 - buffered, part.size)
                part.copyInto(block, buffered, 0, at)
                buffered += at
                if (buffered == 128) { compress(h, w, block, 0); buffered = 0 }
            }
            while (part.size - at >= 128) { compress(h, w, part, at); at += 128 }
            if (at < part.size) { part.copyInto(block, 0, at, part.size); buffered = part.size - at }
        }
        block.fill(0, buffered, 128)
        block[buffered] = 0x80.toByte()
        if (buffered >= 112) { compress(h, w, block, 0); block.fill(0) }
        putLong(block, 112, length ushr 61)
        putLong(block, 120, length shl 3)
        compress(h, w, block, 0)
        return ByteArray(64).also { for (i in 0..7) putLong(it, i * 8, h[i]) }
    }

    fun compress(h: LongArray, w: LongArray, bytes: ByteArray, offset: Int) {
        for (t in 0..15) {
            val o = offset + t * 8
            val high = (bytes[o].toInt() shl 24) or ((bytes[o + 1].toInt() and 0xff) shl 16) or
                ((bytes[o + 2].toInt() and 0xff) shl 8) or (bytes[o + 3].toInt() and 0xff)
            val low = (bytes[o + 4].toInt() shl 24) or ((bytes[o + 5].toInt() and 0xff) shl 16) or
                ((bytes[o + 6].toInt() and 0xff) shl 8) or (bytes[o + 7].toInt() and 0xff)
            w[t] = (high.toLong() shl 32) or (low.toLong() and 0xffffffffL)
        }
        for (t in 16..79) {
            val w15 = w[t - 15]
            val w2 = w[t - 2]
            val s0 = w15.rotateRight(1) xor w15.rotateRight(8) xor (w15 ushr 7)
            val s1 = w2.rotateRight(19) xor w2.rotateRight(61) xor (w2 ushr 6)
            w[t] = w[t - 16] + s0 + w[t - 7] + s1
        }
        var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
        var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
        for (t in 0..79) {
            val t1 = hh + (e.rotateRight(14) xor e.rotateRight(18) xor e.rotateRight(41)) +
                ((e and f) xor (e.inv() and g)) + K[t] + w[t]
            val t2 = (a.rotateRight(28) xor a.rotateRight(34) xor a.rotateRight(39)) +
                ((a and b) xor (a and c) xor (b and c))
            hh = g; g = f; f = e; e = d + t1
            d = c; c = b; b = a; a = t1 + t2
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d
        h[4] += e; h[5] += f; h[6] += g; h[7] += hh
    }

    fun putLong(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0..7) bytes[offset + i] = (value ushr (56 - 8 * i)).toByte()
    }
}
