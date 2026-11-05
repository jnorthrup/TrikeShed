package borg.trikeshed.ssh

/**
 * Inline SSH Symmetric Ciphers and MAC alignment favoring Int over Long.
 *
 * Vectorization alignment rationale:
 * 32-bit words (Int) map directly to SIMD vector registers on ARM NEON (4x32 or 2x32),
 * x86 AVX2 (8x32), WASM SIMD128 (4x32), and RISC-V Vector extensions without requiring
 * 64-bit emulations or wide lane penalties.
 *
 * Provides inline ChaCha20 / Poly1305 / CTR stream processing using 32-bit Int state.
 */
object SshCipher {

    /**
     * ChaCha20 quarter round on 32-bit Int state.
     * Compiler autovectorizes or inlines without boxing.
     */
    inline fun quarterRound(state: IntArray, a: Int, b: Int, c: Int, d: Int) {
        state[a] = state[a] + state[b]
        state[d] = (state[d] xor state[a]).rotateLeft(16)
        state[c] = state[c] + state[d]
        state[b] = (state[b] xor state[c]).rotateLeft(12)
        state[a] = state[a] + state[b]
        state[d] = (state[d] xor state[a]).rotateLeft(8)
        state[c] = state[c] + state[d]
        state[b] = (state[b] xor state[c]).rotateLeft(7)
    }

    /**
     * Inline 20-round transform on 16 Ints.
     */
    fun chacha20Block(state: IntArray, out: IntArray) {
        val working = IntArray(16)
        for (i in 0..15) {
            working[i] = state[i]
        }
        for (i in 0 until 10) {
            // Column round
            quarterRound(working, 0, 4, 8, 12)
            quarterRound(working, 1, 5, 9, 13)
            quarterRound(working, 2, 6, 10, 14)
            quarterRound(working, 3, 7, 11, 15)
            // Diagonal round
            quarterRound(working, 0, 5, 10, 15)
            quarterRound(working, 1, 6, 11, 12)
            quarterRound(working, 2, 7, 8, 13)
            quarterRound(working, 3, 4, 9, 14)
        }
        for (i in 0..15) {
            out[i] = working[i] + state[i]
        }
    }

    /**
     * Autovectorized XOR keystream into bytes using Int arithmetic.
     */
    fun cryptChaCha20(
        key: IntArray,
        nonce: IntArray,
        counter: Int,
        src: ByteArray,
        srcOffset: Int,
        dst: ByteArray,
        dstOffset: Int,
        len: Int
    ) {
        require(key.size == 8) { "Key must be 8 Ints (256-bit)" }
        require(nonce.size == 3) { "Nonce must be 3 Ints (96-bit)" }

        val state = IntArray(16)
        state[0] = 0x61707865
        state[1] = 0x3320646e
        state[2] = 0x79622d32
        state[3] = 0x6b206574
        for (i in 0..7) state[4 + i] = key[i]
        state[12] = counter
        state[13] = nonce[0]
        state[14] = nonce[1]
        state[15] = nonce[2]

        val block = IntArray(16)
        var offset = 0
        var currentCounter = counter

        while (offset < len) {
            state[12] = currentCounter
            chacha20Block(state, block)
            currentCounter++

            val remaining = len - offset
            val chunkSize = if (remaining < 64) remaining else 64

            // Process full 32-bit Int words where aligned
            val fullWords = chunkSize ushr 2
            for (w in 0 until fullWords) {
                val bytePosSrc = srcOffset + offset + (w shl 2)
                val bytePosDst = dstOffset + offset + (w shl 2)
                val srcWord = (src[bytePosSrc].toInt() and 0xFF) or
                        ((src[bytePosSrc + 1].toInt() and 0xFF) shl 8) or
                        ((src[bytePosSrc + 2].toInt() and 0xFF) shl 16) or
                        ((src[bytePosSrc + 3].toInt() and 0xFF) shl 24)
                val cipherWord = srcWord xor block[w]
                dst[bytePosDst] = (cipherWord and 0xFF).toByte()
                dst[bytePosDst + 1] = ((cipherWord ushr 8) and 0xFF).toByte()
                dst[bytePosDst + 2] = ((cipherWord ushr 16) and 0xFF).toByte()
                dst[bytePosDst + 3] = ((cipherWord ushr 24) and 0xFF).toByte()
            }

            // Tail bytes
            val processedBytes = fullWords shl 2
            if (processedBytes < chunkSize) {
                val lastWord = block[fullWords]
                for (b in 0 until (chunkSize - processedBytes)) {
                    val s = src[srcOffset + offset + processedBytes + b].toInt() and 0xFF
                    val k = (lastWord ushr (b shl 3)) and 0xFF
                    dst[dstOffset + offset + processedBytes + b] = (s xor k).toByte()
                }
            }

            offset += chunkSize
        }
    }

    /**
     * Fast inline 32-bit polynomial MAC accumulator (Poly1305 limb representation using IntArray).
     */
    class SshPoly1305Int(keyBytes: ByteArray) {
        val r0: Int
        val r1: Int
        val r2: Int
        val r3: Int
        val r4: Int
        val s0: Int
        val s1: Int
        val s2: Int
        val s3: Int

        var h0: Int = 0
        var h1: Int = 0
        var h2: Int = 0
        var h3: Int = 0
        var h4: Int = 0

        init {
            require(keyBytes.size == 32)
            val t0 = le32(keyBytes, 0)
            val t1 = le32(keyBytes, 4)
            val t2 = le32(keyBytes, 8)
            val t3 = le32(keyBytes, 12)

            r0 = t0 and 0x3ffffff
            r1 = (t0 ushr 26 or (t1 shl 6)) and 0x3ffff03
            r2 = (t1 ushr 20 or (t2 shl 12)) and 0x3ffc0ff
            r3 = (t2 ushr 14 or (t3 shl 18)) and 0x3f03fff
            r4 = (t3 ushr 8) and 0x00fffff

            s0 = le32(keyBytes, 16)
            s1 = le32(keyBytes, 20)
            s2 = le32(keyBytes, 24)
            s3 = le32(keyBytes, 28)
        }

        fun update(bytes: ByteArray, offset: Int, length: Int) {
            var at = offset
            val end = offset + length
            while (end - at >= 16) {
                val w0 = le32(bytes, at)
                val w1 = le32(bytes, at + 4)
                val w2 = le32(bytes, at + 8)
                val w3 = le32(bytes, at + 12)

                h0 += w0 and 0x3ffffff
                h1 += (w0 ushr 26 or (w1 shl 6)) and 0x3ffffff
                h2 += (w1 ushr 20 or (w2 shl 12)) and 0x3ffffff
                h3 += (w2 ushr 14 or (w3 shl 18)) and 0x3ffffff
                h4 += (w3 ushr 8) or (1 shl 24)

                multiply()
                at += 16
            }
            if (at < end) {
                val rem = ByteArray(16)
                bytes.copyInto(rem, 0, at, end)
                rem[end - at] = 1

                val w0 = le32(rem, 0)
                val w1 = le32(rem, 4)
                val w2 = le32(rem, 8)
                val w3 = le32(rem, 12)

                h0 += w0 and 0x3ffffff
                h1 += (w0 ushr 26 or (w1 shl 6)) and 0x3ffffff
                h2 += (w1 ushr 20 or (w2 shl 12)) and 0x3ffffff
                h3 += (w2 ushr 14 or (w3 shl 18)) and 0x3ffffff
                h4 += (w3 ushr 8)

                multiply()
            }
        }

        private fun multiply() {
            val r1_5 = r1 * 5
            val r2_5 = r2 * 5
            val r3_5 = r3 * 5
            val r4_5 = r4 * 5

            val d0 = h0.toLong() * r0 + h1.toLong() * r4_5 + h2.toLong() * r3_5 + h3.toLong() * r2_5 + h4.toLong() * r1_5
            val d1 = h0.toLong() * r1 + h1.toLong() * r0 + h2.toLong() * r4_5 + h3.toLong() * r3_5 + h4.toLong() * r2_5
            val d2 = h0.toLong() * r2 + h1.toLong() * r1 + h2.toLong() * r0 + h3.toLong() * r4_5 + h4.toLong() * r3_5
            val d3 = h0.toLong() * r3 + h1.toLong() * r2 + h2.toLong() * r1 + h3.toLong() * r0 + h4.toLong() * r4_5
            val d4 = h0.toLong() * r4 + h1.toLong() * r3 + h2.toLong() * r2 + h3.toLong() * r1 + h4.toLong() * r0

            var c: Long
            c = d0 ushr 26; h0 = (d0 and 0x3ffffff).toInt()
            val t1 = d1 + c; c = t1 ushr 26; h1 = (t1 and 0x3ffffff).toInt()
            val t2 = d2 + c; c = t2 ushr 26; h2 = (t2 and 0x3ffffff).toInt()
            val t3 = d3 + c; c = t3 ushr 26; h3 = (t3 and 0x3ffffff).toInt()
            val t4 = d4 + c; c = t4 ushr 26; h4 = (t4 and 0x3ffffff).toInt()
            h0 += (c * 5).toInt()
            h1 += h0 ushr 26
            h0 = h0 and 0x3ffffff
        }

        fun finish(out: ByteArray, outOffset: Int) {
            var c = h1 ushr 26; h1 = h1 and 0x3ffffff
            h2 += c; c = h2 ushr 26; h2 = h2 and 0x3ffffff
            h3 += c; c = h3 ushr 26; h3 = h3 and 0x3ffffff
            h4 += c; c = h4 ushr 26; h4 = h4 and 0x3ffffff
            h0 += c * 5
            h1 += h0 ushr 26
            h0 = h0 and 0x3ffffff

            val f0 = (h0 or (h1 shl 26)).toLong() and 0xFFFFFFFFL
            val f1 = ((h1 ushr 6) or (h2 shl 20)).toLong() and 0xFFFFFFFFL
            val f2 = ((h2 ushr 12) or (h3 shl 14)).toLong() and 0xFFFFFFFFL
            val f3 = ((h3 ushr 18) or (h4 shl 8)).toLong() and 0xFFFFFFFFL

            var acc = f0 + (s0.toLong() and 0xFFFFFFFFL)
            out[outOffset] = (acc and 0xFF).toByte()
            out[outOffset + 1] = ((acc ushr 8) and 0xFF).toByte()
            out[outOffset + 2] = ((acc ushr 16) and 0xFF).toByte()
            out[outOffset + 3] = ((acc ushr 24) and 0xFF).toByte()

            acc = f1 + (s1.toLong() and 0xFFFFFFFFL) + (acc ushr 32)
            out[outOffset + 4] = (acc and 0xFF).toByte()
            out[outOffset + 5] = ((acc ushr 8) and 0xFF).toByte()
            out[outOffset + 6] = ((acc ushr 16) and 0xFF).toByte()
            out[outOffset + 7] = ((acc ushr 24) and 0xFF).toByte()

            acc = f2 + (s2.toLong() and 0xFFFFFFFFL) + (acc ushr 32)
            out[outOffset + 8] = (acc and 0xFF).toByte()
            out[outOffset + 9] = ((acc ushr 8) and 0xFF).toByte()
            out[outOffset + 10] = ((acc ushr 16) and 0xFF).toByte()
            out[outOffset + 11] = ((acc ushr 24) and 0xFF).toByte()

            acc = f3 + (s3.toLong() and 0xFFFFFFFFL) + (acc ushr 32)
            out[outOffset + 12] = (acc and 0xFF).toByte()
            out[outOffset + 13] = ((acc ushr 8) and 0xFF).toByte()
            out[outOffset + 14] = ((acc ushr 16) and 0xFF).toByte()
            out[outOffset + 15] = ((acc ushr 24) and 0xFF).toByte()
        }

        private fun le32(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or
                    ((b[off + 1].toInt() and 0xFF) shl 8) or
                    ((b[off + 2].toInt() and 0xFF) shl 16) or
                    ((b[off + 3].toInt() and 0xFF) shl 24)
    }

    fun bytesToIntArray(bytes: ByteArray): IntArray {
        require(bytes.size % 4 == 0)
        val ints = IntArray(bytes.size / 4)
        for (i in ints.indices) {
            val off = i * 4
            ints[i] = (bytes[off].toInt() and 0xFF) or
                    ((bytes[off + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[off + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[off + 3].toInt() and 0xFF) shl 24)
        }
        return ints
    }
}
