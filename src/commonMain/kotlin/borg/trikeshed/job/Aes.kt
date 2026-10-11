package borg.trikeshed.job

import borg.trikeshed.userspace.nio.platform.spi.*

/**
 * FIPS 197 AES with a 16-, 24- or 32-byte key, in constant time: BearSSL's aes_ct64 bitsliced form,
 * four blocks spread over eight 64-bit words and the S-box as the Boyar-Peralta circuit, so no table
 * index or branch depends on the key or the data. Block bytes enter as little-endian 32-bit words,
 * the layout's own convention; the cipher is FIPS 197's.
 */
class Aes(key: ByteArray) {
    /** Nr: 10, 12 or 14. */
    val rounds: Int = when (key.size) {
        16 -> 10
        24 -> 12
        32 -> 14
        else -> throw IllegalArgumentException("AES key is 16, 24 or 32 bytes")
    }

    /** The round keys of FIPS 197 5.2 in bitsliced form, eight words per round (br_aes_ct64_skey_expand). */
    val roundKeys = LongArray(8 * (rounds + 1))

    init {
        val nk = key.size / 4
        val words = IntArray(4 * (rounds + 1))
        for (i in 0 until nk) words[i] = le32(key, 4 * i)
        var temp = words[nk - 1]
        var j = 0
        var k = 0
        for (i in nk until words.size) {
            if (j == 0) temp = subWord((temp shl 24) or (temp ushr 8)) xor RCON[k]
            else if (nk > 6 && j == 4) temp = subWord(temp)
            temp = temp xor words[i - nk]
            words[i] = temp
            if (++j == nk) {
                j = 0
                k++
            }
        }
        val q = LongArray(8)
        for (i in words.indices step 4) {
            interleaveIn(q, 0, 4, words, i)
            q[1] = q[0]; q[2] = q[0]; q[3] = q[0]
            q[5] = q[4]; q[6] = q[4]; q[7] = q[4]
            ortho(q)
            for (half in 0..1) {
                val x = (q[4 * half] and M1) or (q[4 * half + 1] and M2) or (q[4 * half + 2] and M4) or (q[4 * half + 3] and M8)
                val at = 4 * (i / 2 + half)
                for (b in 0..3) {
                    val bit = (x ushr b) and M1
                    roundKeys[at + b] = (bit shl 4) - bit
                }
            }
        }
    }

    /** FIPS 197 5.1 Cipher over the four blocks held in bitsliced form by [q]. */
    fun encrypt(q: LongArray) {
        addRoundKey(q, 0)
        for (round in 1 until rounds) {
            subBytes(q)
            shiftRows(q)
            mixColumns(q)
            addRoundKey(q, round)
        }
        subBytes(q)
        shiftRows(q)
        addRoundKey(q, rounds)
    }

    /** CIPH_K of the 16-byte [block]. */
    fun encryptBlock(block: ByteArray): ByteArray {
        val w = IntArray(16)
        for (i in 0..3) w[i] = le32(block, 4 * i)
        val q = LongArray(8)
        for (i in 0..3) interleaveIn(q, i, i + 4, w, 4 * i)
        ortho(q)
        encrypt(q)
        ortho(q)
        for (i in 0..3) interleaveOut(w, 4 * i, q[i], q[i + 4])
        return ByteArray(16) { (w[it shr 2] ushr ((it and 3) shl 3)).toByte() }
    }

    /**
     * SP 800-38D 6.5 GCTR from the counter block [iv] | [counter] (a 96-bit IV and a 32-bit big-endian
     * counter incremented mod 2^32): [length] bytes of [input] from [offset] xor the key stream, into [output] at [at].
     */
    fun gctr(iv: ByteArray, counter: Int, input: ByteArray, offset: Int, length: Int, output: ByteArray, at: Int) {
        val iv0 = le32(iv, 0)
        val iv1 = le32(iv, 4)
        val iv2 = le32(iv, 8)
        val w = IntArray(16)
        val q = LongArray(8)
        var cb = counter
        var done = 0
        while (done < length) {
            for (b in 0..3) {
                w[4 * b] = iv0
                w[4 * b + 1] = iv1
                w[4 * b + 2] = iv2
                w[4 * b + 3] = byteSwap(cb + b)
            }
            for (i in 0..3) interleaveIn(q, i, i + 4, w, 4 * i)
            ortho(q)
            encrypt(q)
            ortho(q)
            for (i in 0..3) interleaveOut(w, 4 * i, q[i], q[i + 4])
            val n = minOf(64, length - done)
            if (n == 64) for (j in 0..7) {
                val key = (w[2 * j].toLong() and 0xffffffffL) or (w[2 * j + 1].toLong() shl 32)
                output.littleEndianSetLongAt(at + done + 8 * j, input.littleEndianGetLongAt(offset + done + 8 * j) xor key)
            } else for (i in 0 until n) {
                output[at + done + i] = (input[offset + done + i].toInt() xor (w[i shr 2] ushr ((i and 3) shl 3))).toByte()
            }
            cb += 4
            done += 64
        }
    }

    fun addRoundKey(q: LongArray, round: Int) {
        for (i in 0..7) q[i] = q[i] xor roundKeys[8 * round + i]
    }

    companion object {
        /** Rcon[i] of FIPS 197 5.2, the x^(i-1) byte in the low position of a little-endian word. */
        val RCON = intArrayOf(0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1B, 0x36)

        const val M1 = 0x1111111111111111L
        const val M2 = 0x2222222222222222L
        const val M4 = 0x4444444444444444L
        /** 0x8888888888888888. */
        const val M8 = -0x7777777777777778L

        fun le32(b: ByteArray, offset: Int): Int = (b[offset].toInt() and 0xff) or ((b[offset + 1].toInt() and 0xff) shl 8) or
            ((b[offset + 2].toInt() and 0xff) shl 16) or (b[offset + 3].toInt() shl 24)

        fun byteSwap(x: Int): Int = (x ushr 24) or ((x ushr 8) and 0xff00) or ((x shl 8) and 0xff0000) or (x shl 24)

        /** SubWord of FIPS 197 5.2 through the bitsliced S-box. */
        fun subWord(x: Int): Int {
            val q = LongArray(8)
            q[0] = x.toLong() and 0xffffffffL
            ortho(q)
            subBytes(q)
            ortho(q)
            return q[0].toInt()
        }

        /** Spreads four little-endian words of one block, w[at..at+3], over q[i0] and q[i1] (br_aes_ct64_interleave_in). */
        fun interleaveIn(q: LongArray, i0: Int, i1: Int, w: IntArray, at: Int) {
            var x0 = w[at].toLong() and 0xffffffffL
            var x1 = w[at + 1].toLong() and 0xffffffffL
            var x2 = w[at + 2].toLong() and 0xffffffffL
            var x3 = w[at + 3].toLong() and 0xffffffffL
            x0 = (x0 or (x0 shl 16)) and 0x0000FFFF0000FFFFL
            x1 = (x1 or (x1 shl 16)) and 0x0000FFFF0000FFFFL
            x2 = (x2 or (x2 shl 16)) and 0x0000FFFF0000FFFFL
            x3 = (x3 or (x3 shl 16)) and 0x0000FFFF0000FFFFL
            x0 = (x0 or (x0 shl 8)) and 0x00FF00FF00FF00FFL
            x1 = (x1 or (x1 shl 8)) and 0x00FF00FF00FF00FFL
            x2 = (x2 or (x2 shl 8)) and 0x00FF00FF00FF00FFL
            x3 = (x3 or (x3 shl 8)) and 0x00FF00FF00FF00FFL
            q[i0] = x0 or (x2 shl 8)
            q[i1] = x1 or (x3 shl 8)
        }

        /** The inverse of [interleaveIn] (br_aes_ct64_interleave_out). */
        fun interleaveOut(w: IntArray, at: Int, q0: Long, q1: Long) {
            var x0 = q0 and 0x00FF00FF00FF00FFL
            var x1 = q1 and 0x00FF00FF00FF00FFL
            var x2 = (q0 ushr 8) and 0x00FF00FF00FF00FFL
            var x3 = (q1 ushr 8) and 0x00FF00FF00FF00FFL
            x0 = (x0 or (x0 ushr 8)) and 0x0000FFFF0000FFFFL
            x1 = (x1 or (x1 ushr 8)) and 0x0000FFFF0000FFFFL
            x2 = (x2 or (x2 ushr 8)) and 0x0000FFFF0000FFFFL
            x3 = (x3 or (x3 ushr 8)) and 0x0000FFFF0000FFFFL
            w[at] = x0.toInt() or (x0 ushr 16).toInt()
            w[at + 1] = x1.toInt() or (x1 ushr 16).toInt()
            w[at + 2] = x2.toInt() or (x2 ushr 16).toInt()
            w[at + 3] = x3.toInt() or (x3 ushr 16).toInt()
        }

        fun swap(q: LongArray, a: Int, b: Int, low: Long, shift: Int) {
            val x = q[a]
            val y = q[b]
            q[a] = (x and low) or ((y and low) shl shift)
            q[b] = ((x and low.inv()) ushr shift) or (y and low.inv())
        }

        /** The 8x8 bit transposition between byte lanes and bit planes; its own inverse (br_aes_ct64_ortho). */
        fun ortho(q: LongArray) {
            swap(q, 0, 1, 0x5555555555555555L, 1); swap(q, 2, 3, 0x5555555555555555L, 1)
            swap(q, 4, 5, 0x5555555555555555L, 1); swap(q, 6, 7, 0x5555555555555555L, 1)
            swap(q, 0, 2, 0x3333333333333333L, 2); swap(q, 1, 3, 0x3333333333333333L, 2)
            swap(q, 4, 6, 0x3333333333333333L, 2); swap(q, 5, 7, 0x3333333333333333L, 2)
            swap(q, 0, 4, 0x0F0F0F0F0F0F0F0FL, 4); swap(q, 1, 5, 0x0F0F0F0F0F0F0F0FL, 4)
            swap(q, 2, 6, 0x0F0F0F0F0F0F0F0FL, 4); swap(q, 3, 7, 0x0F0F0F0F0F0F0F0FL, 4)
        }

        /** FIPS 197 5.1.1 SubBytes: Boyar and Peralta's 113-gate circuit (eprint 2009/191) on the bit planes, x0 the high bit. */
        fun subBytes(q: LongArray) {
            val x0 = q[7]; val x1 = q[6]; val x2 = q[5]; val x3 = q[4]
            val x4 = q[3]; val x5 = q[2]; val x6 = q[1]; val x7 = q[0]
            // Top linear transformation.
            val y14 = x3 xor x5
            val y13 = x0 xor x6
            val y9 = x0 xor x3
            val y8 = x0 xor x5
            val t0 = x1 xor x2
            val y1 = t0 xor x7
            val y4 = y1 xor x3
            val y12 = y13 xor y14
            val y2 = y1 xor x0
            val y5 = y1 xor x6
            val y3 = y5 xor y8
            val t1 = x4 xor y12
            val y15 = t1 xor x5
            val y20 = t1 xor x1
            val y6 = y15 xor x7
            val y10 = y15 xor t0
            val y11 = y20 xor y9
            val y7 = x7 xor y11
            val y17 = y10 xor y11
            val y19 = y10 xor y8
            val y16 = t0 xor y11
            val y21 = y13 xor y16
            val y18 = x0 xor y16
            // Non-linear section.
            val t2 = y12 and y15
            val t3 = y3 and y6
            val t4 = t3 xor t2
            val t5 = y4 and x7
            val t6 = t5 xor t2
            val t7 = y13 and y16
            val t8 = y5 and y1
            val t9 = t8 xor t7
            val t10 = y2 and y7
            val t11 = t10 xor t7
            val t12 = y9 and y11
            val t13 = y14 and y17
            val t14 = t13 xor t12
            val t15 = y8 and y10
            val t16 = t15 xor t12
            val t17 = t4 xor t14
            val t18 = t6 xor t16
            val t19 = t9 xor t14
            val t20 = t11 xor t16
            val t21 = t17 xor y20
            val t22 = t18 xor y19
            val t23 = t19 xor y21
            val t24 = t20 xor y18
            val t25 = t21 xor t22
            val t26 = t21 and t23
            val t27 = t24 xor t26
            val t28 = t25 and t27
            val t29 = t28 xor t22
            val t30 = t23 xor t24
            val t31 = t22 xor t26
            val t32 = t31 and t30
            val t33 = t32 xor t24
            val t34 = t23 xor t33
            val t35 = t27 xor t33
            val t36 = t24 and t35
            val t37 = t36 xor t34
            val t38 = t27 xor t36
            val t39 = t29 and t38
            val t40 = t25 xor t39
            val t41 = t40 xor t37
            val t42 = t29 xor t33
            val t43 = t29 xor t40
            val t44 = t33 xor t37
            val t45 = t42 xor t41
            val z0 = t44 and y15
            val z1 = t37 and y6
            val z2 = t33 and x7
            val z3 = t43 and y16
            val z4 = t40 and y1
            val z5 = t29 and y7
            val z6 = t42 and y11
            val z7 = t45 and y17
            val z8 = t41 and y10
            val z9 = t44 and y12
            val z10 = t37 and y3
            val z11 = t33 and y4
            val z12 = t43 and y13
            val z13 = t40 and y5
            val z14 = t29 and y2
            val z15 = t42 and y9
            val z16 = t45 and y14
            val z17 = t41 and y8
            // Bottom linear transformation.
            val t46 = z15 xor z16
            val t47 = z10 xor z11
            val t48 = z5 xor z13
            val t49 = z9 xor z10
            val t50 = z2 xor z12
            val t51 = z2 xor z5
            val t52 = z7 xor z8
            val t53 = z0 xor z3
            val t54 = z6 xor z7
            val t55 = z16 xor z17
            val t56 = z12 xor t48
            val t57 = t50 xor t53
            val t58 = z4 xor t46
            val t59 = z3 xor t54
            val t60 = t46 xor t57
            val t61 = z14 xor t57
            val t62 = t52 xor t58
            val t63 = t49 xor t58
            val t64 = z4 xor t59
            val t65 = t61 xor t62
            val t66 = z1 xor t63
            val s0 = t59 xor t63
            val s6 = t56 xor t62.inv()
            val s7 = t48 xor t60.inv()
            val t67 = t64 xor t65
            val s3 = t53 xor t66
            val s4 = t51 xor t66
            val s5 = t47 xor t65
            val s1 = t64 xor s3.inv()
            val s2 = t55 xor t67.inv()
            q[7] = s0; q[6] = s1; q[5] = s2; q[4] = s3
            q[3] = s4; q[2] = s5; q[1] = s6; q[0] = s7
        }

        /** FIPS 197 5.1.2 ShiftRows on the bit planes. */
        fun shiftRows(q: LongArray) {
            for (i in 0..7) {
                val x = q[i]
                q[i] = (x and 0x000000000000FFFFL) or
                    ((x ushr 4) and 0x000000000FFF0000L) or
                    ((x shl 12) and 0x00000000F0000000L) or
                    ((x ushr 8) and 0x000000FF00000000L) or
                    ((x shl 8) and 0x0000FF0000000000L) or
                    ((x ushr 12) and 0x000F000000000000L) or
                    ((x and 0x0FFF000000000000L) shl 4)
            }
        }

        /** FIPS 197 5.1.3 MixColumns on the bit planes. */
        fun mixColumns(q: LongArray) {
            val q0 = q[0]; val q1 = q[1]; val q2 = q[2]; val q3 = q[3]
            val q4 = q[4]; val q5 = q[5]; val q6 = q[6]; val q7 = q[7]
            val r0 = (q0 ushr 16) or (q0 shl 48)
            val r1 = (q1 ushr 16) or (q1 shl 48)
            val r2 = (q2 ushr 16) or (q2 shl 48)
            val r3 = (q3 ushr 16) or (q3 shl 48)
            val r4 = (q4 ushr 16) or (q4 shl 48)
            val r5 = (q5 ushr 16) or (q5 shl 48)
            val r6 = (q6 ushr 16) or (q6 shl 48)
            val r7 = (q7 ushr 16) or (q7 shl 48)
            q[0] = q7 xor r7 xor r0 xor (q0 xor r0).rotateLeft(32)
            q[1] = q0 xor r0 xor q7 xor r7 xor r1 xor (q1 xor r1).rotateLeft(32)
            q[2] = q1 xor r1 xor r2 xor (q2 xor r2).rotateLeft(32)
            q[3] = q2 xor r2 xor q7 xor r7 xor r3 xor (q3 xor r3).rotateLeft(32)
            q[4] = q3 xor r3 xor q7 xor r7 xor r4 xor (q4 xor r4).rotateLeft(32)
            q[5] = q4 xor r4 xor r5 xor (q5 xor r5).rotateLeft(32)
            q[6] = q5 xor r5 xor r6 xor (q6 xor r6).rotateLeft(32)
            q[7] = q6 xor r6 xor r7 xor (q7 xor r7).rotateLeft(32)
        }
    }
}

/**
 * NIST SP 800-38D GCM over [Aes] with 96-bit IVs and 128-bit tags; the tag follows the ciphertext.
 * GHASH multiplies in GF(2^128) by integer multiplications with holes (BearSSL ghash_ctmul64), in constant time.
 */
class AesGcm(key: ByteArray) {
    val aes = Aes(key)

    /** The hash subkey H = CIPH_K(0^128) as its big-endian halves. */
    val h1: Long
    val h0: Long

    init {
        val h = aes.encryptBlock(ByteArray(16))
        h1 = be64(h, 0)
        h0 = be64(h, 8)
    }

    /** GCM-AE_K(IV, P, A): C | T. */
    fun encrypt(nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        require(nonce.size == 12) { "GCM IV is 96 bits here" }
        val sealed = ByteArray(plaintext.size + 16)
        aes.gctr(nonce, 2, plaintext, 0, plaintext.size, sealed, 0)
        tag(nonce, aad, sealed, plaintext.size).copyInto(sealed, plaintext.size)
        return sealed
    }

    /** GCM-AD_K(IV, C, A, T): the plaintext, or null when the tag does not authenticate [aad] and the ciphertext. */
    fun decrypt(nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? {
        require(nonce.size == 12) { "GCM IV is 96 bits here" }
        if (sealed.size < 16) return null
        val length = sealed.size - 16
        val tag = tag(nonce, aad, sealed, length)
        var difference = 0
        for (i in 0..15) difference = difference or (tag[i].toInt() xor sealed[length + i].toInt())
        if (difference != 0) return null
        return ByteArray(length).also { aes.gctr(nonce, 2, sealed, 0, length, it, 0) }
    }

    /** T = GCTR_K(J0, GHASH_H(A | 0^v | C | 0^u | [len(A)]64 | [len(C)]64)), J0 = IV | 0^31 | 1. */
    fun tag(nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray, length: Int): ByteArray {
        val y = LongArray(2)
        ghash(y, aad, 0, aad.size)
        ghash(y, ciphertext, 0, length)
        val lengths = ByteArray(16)
        putBe64(lengths, 0, aad.size.toLong() shl 3)
        putBe64(lengths, 8, length.toLong() shl 3)
        ghash(y, lengths, 0, 16)
        val s = ByteArray(16)
        putBe64(s, 0, y[0])
        putBe64(s, 8, y[1])
        aes.gctr(nonce, 1, s, 0, 16, s, 0)
        return s
    }

    /** Y = (Y xor X_i) H over [length] bytes of [x] from [offset], the last block zero-padded; y[0] is the high half. */
    fun ghash(y: LongArray, x: ByteArray, offset: Int, length: Int) {
        var y1 = y[0]
        var y0 = y[1]
        val h0r = rev64(h0)
        val h1r = rev64(h1)
        val h2 = h0 xor h1
        val h2r = h0r xor h1r
        var at = offset
        val end = offset + length
        val last = ByteArray(16)
        while (at < end) {
            if (end - at >= 16) {
                y1 = y1 xor be64(x, at)
                y0 = y0 xor be64(x, at + 8)
            } else {
                last.fill(0)
                x.copyInto(last, 0, at, end)
                y1 = y1 xor be64(last, 0)
                y0 = y0 xor be64(last, 8)
            }
            at += 16
            val y0r = rev64(y0)
            val y1r = rev64(y1)
            val y2 = y0 xor y1
            val y2r = y0r xor y1r
            val z0 = bmul64(y0, h0)
            val z1 = bmul64(y1, h1)
            var z2 = bmul64(y2, h2)
            var z0h = bmul64(y0r, h0r)
            var z1h = bmul64(y1r, h1r)
            var z2h = bmul64(y2r, h2r)
            z2 = z2 xor z0 xor z1
            z2h = z2h xor z0h xor z1h
            z0h = rev64(z0h) ushr 1
            z1h = rev64(z1h) ushr 1
            z2h = rev64(z2h) ushr 1
            var v0 = z0
            var v1 = z0h xor z2
            var v2 = z1 xor z2h
            var v3 = z1h
            v3 = (v3 shl 1) or (v2 ushr 63)
            v2 = (v2 shl 1) or (v1 ushr 63)
            v1 = (v1 shl 1) or (v0 ushr 63)
            v0 = v0 shl 1
            v2 = v2 xor v0 xor (v0 ushr 1) xor (v0 ushr 2) xor (v0 ushr 7)
            v1 = v1 xor (v0 shl 63) xor (v0 shl 62) xor (v0 shl 57)
            v3 = v3 xor v1 xor (v1 ushr 1) xor (v1 ushr 2) xor (v1 ushr 7)
            v2 = v2 xor (v1 shl 63) xor (v1 shl 62) xor (v1 shl 57)
            y0 = v2
            y1 = v3
        }
        y[0] = y1
        y[1] = y0
    }

    companion object {
        /** The carry-less product of x and y mod 2^64, from four integer products whose carries fall in the masked holes. */
        fun bmul64(x: Long, y: Long): Long {
            val x0 = x and Aes.M1
            val x1 = x and Aes.M2
            val x2 = x and Aes.M4
            val x3 = x and Aes.M8
            val y0 = y and Aes.M1
            val y1 = y and Aes.M2
            val y2 = y and Aes.M4
            val y3 = y and Aes.M8
            val z0 = (x0 * y0) xor (x1 * y3) xor (x2 * y2) xor (x3 * y1)
            val z1 = (x0 * y1) xor (x1 * y0) xor (x2 * y3) xor (x3 * y2)
            val z2 = (x0 * y2) xor (x1 * y1) xor (x2 * y0) xor (x3 * y3)
            val z3 = (x0 * y3) xor (x1 * y2) xor (x2 * y1) xor (x3 * y0)
            return (z0 and Aes.M1) or (z1 and Aes.M2) or (z2 and Aes.M4) or (z3 and Aes.M8)
        }

        /** The 64 bits of x in reverse order. */
        fun rev64(value: Long): Long {
            var x = value
            x = ((x and 0x5555555555555555L) shl 1) or ((x ushr 1) and 0x5555555555555555L)
            x = ((x and 0x3333333333333333L) shl 2) or ((x ushr 2) and 0x3333333333333333L)
            x = ((x and 0x0F0F0F0F0F0F0F0FL) shl 4) or ((x ushr 4) and 0x0F0F0F0F0F0F0F0FL)
            x = ((x and 0x00FF00FF00FF00FFL) shl 8) or ((x ushr 8) and 0x00FF00FF00FF00FFL)
            x = ((x and 0x0000FFFF0000FFFFL) shl 16) or ((x ushr 16) and 0x0000FFFF0000FFFFL)
            return (x shl 32) or (x ushr 32)
        }

        fun be64(b: ByteArray, offset: Int): Long {
            var v = 0L
            for (i in 0..7) v = (v shl 8) or (b[offset + i].toLong() and 0xff)
            return v
        }

        fun putBe64(b: ByteArray, offset: Int, value: Long) {
            for (i in 0..7) b[offset + i] = (value ushr (56 - 8 * i)).toByte()
        }
    }
}
