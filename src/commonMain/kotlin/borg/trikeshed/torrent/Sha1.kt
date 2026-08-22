package borg.trikeshed.torrent

/**
 * Pure Kotlin implementation of SHA-1 (FIPS 180-1).
 */
internal object Sha1 {
    fun digest(bytes: ByteArray): ByteArray {
        var h0 = 0x67452301
        var h1 = -0x10325477 // 0xEFCDAB89
        var h2 = -0x67452302 // 0x98BADCFE
        var h3 = 0x10325476
        var h4 = -0x3C2D1E10 // 0xC3D2E1F0

        val ml = (bytes.size.toLong() * 8L)
        
        val paddingLength = if (bytes.size % 64 < 56) 56 - (bytes.size % 64) else 120 - (bytes.size % 64)
        val paddedBytes = ByteArray(bytes.size + paddingLength + 8)
        bytes.copyInto(paddedBytes)
        paddedBytes[bytes.size] = 0x80.toByte()
        
        for (i in 0..7) {
            paddedBytes[paddedBytes.size - 1 - i] = (ml ushr (i * 8)).toByte()
        }
        
        for (i in paddedBytes.indices step 64) {
            val w = IntArray(80)
            for (j in 0..15) {
                w[j] = ((paddedBytes[i + j * 4].toInt() and 0xFF) shl 24) or
                       ((paddedBytes[i + j * 4 + 1].toInt() and 0xFF) shl 16) or
                       ((paddedBytes[i + j * 4 + 2].toInt() and 0xFF) shl 8) or
                       (paddedBytes[i + j * 4 + 3].toInt() and 0xFF)
            }
            for (j in 16..79) {
                w[j] = (w[j - 3] xor w[j - 8] xor w[j - 14] xor w[j - 16]) rotateLeft 1
            }
            
            var a = h0
            var b = h1
            var c = h2
            var d = h3
            var e = h4
            
            for (j in 0..79) {
                val f: Int
                val k: Int
                when (j) {
                    in 0..19 -> {
                        f = (b and c) or (b.inv() and d)
                        k = 0x5A827999
                    }
                    in 20..39 -> {
                        f = b xor c xor d
                        k = 0x6ED9EBA1
                    }
                    in 40..59 -> {
                        f = (b and c) or (b and d) or (c and d)
                        k = -0x70E44324 // 0x8F1BBCDC
                    }
                    else -> {
                        f = b xor c xor d
                        k = -0x359D3E2A // 0xCA62C1D6
                    }
                }
                val temp = (a rotateLeft 5) + f + e + k + w[j]
                e = d
                d = c
                c = b rotateLeft 30
                b = a
                a = temp
            }
            h0 += a
            h1 += b
            h2 += c
            h3 += d
            h4 += e
        }
        
        return byteArrayOf(
            (h0 ushr 24).toByte(), (h0 ushr 16).toByte(), (h0 ushr 8).toByte(), h0.toByte(),
            (h1 ushr 24).toByte(), (h1 ushr 16).toByte(), (h1 ushr 8).toByte(), h1.toByte(),
            (h2 ushr 24).toByte(), (h2 ushr 16).toByte(), (h2 ushr 8).toByte(), h2.toByte(),
            (h3 ushr 24).toByte(), (h3 ushr 16).toByte(), (h3 ushr 8).toByte(), h3.toByte(),
            (h4 ushr 24).toByte(), (h4 ushr 16).toByte(), (h4 ushr 8).toByte(), h4.toByte()
        )
    }
    
    private infix fun Int.rotateLeft(bits: Int): Int = (this shl bits) or (this ushr (32 - bits))
}
