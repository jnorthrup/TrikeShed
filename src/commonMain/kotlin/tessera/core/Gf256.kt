/*
 * Ported from Tessera — https://github.com/3x3xX3N0N/tessera (via jnorthrup/tessera @78bc042).
 * Copyright 2026 3x3xX3N0N. Licensed under the Apache License, Version 2.0; see tessera/NOTICE.md.
 *
 * Changes in TrikeShed: the Panama/Rust `OffHeapKernel` is gone (commonMain has no FFM); the
 * `Swar` kernel below is the autovectorizable replacement — eight GF(256) lanes per Long, no
 * table gathers in the hot loop, so the JIT's superword pass can widen it and every other
 * target gets the same eight-lane arithmetic for free.
 */
package tessera.core

/**
 * GF(256) with the AES/RLNC polynomial `x^8 + x^4 + x^3 + x^2 + 1` (0x11D).
 *
 * [mul] and [inv] are the table forms (one lookup each way). The multiply-accumulate that the
 * coder actually spends its time in — `dst[i] ^= src[i] * c` — goes through [kernel], and every
 * kernel must produce bytes identical to [Scalar], which is the oracle.
 */
object GF256 {
    private val exp = IntArray(512)
    private val log = IntArray(256)
    init {
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x; log[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) exp[i] = exp[i - 255]
    }
    fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]
    fun inv(a: Int): Int = exp[255 - log[a]]

    /**
     * The multiply-accumulate hot kernel: `dst[i] ^= src[i] * c` for `i in dst.indices` (`src.size >= dst.size`,
     * `c` in 0..255, `c == 0` is a no-op). Every implementation must produce bytes identical to [Scalar].
     */
    fun interface Kernel {
        fun mulAddInto(dst: ByteArray, src: ByteArray, c: Int)
    }

    /** Portable reference kernel (two table lookups per byte). The oracle for every other kernel. */
    object Scalar : Kernel {
        override fun mulAddInto(dst: ByteArray, src: ByteArray, c: Int) {
            if (c == 0) return
            for (i in dst.indices) dst[i] = ((dst[i].toInt() and 0xFF) xor mul(src[i].toInt() and 0xFF, c)).toByte()
        }
    }

    /**
     * SWAR kernel: eight bytes ride one `Long`, and `x * c` is the shift-and-add ("Russian peasant")
     * product over all eight lanes at once — for each set bit of `c`, xor the running `x` into the
     * accumulator and advance `x` by one `xtime` (multiply by the polynomial's `x`). `xtime` on packed
     * lanes: the low seven bits of every lane shift left; the lanes whose high bit was set get the
     * reduction constant 0x1D xored in. No lane ever carries into its neighbour, so the whole thing
     * is plain Long arithmetic the JIT can widen across consecutive words; the trip count of the
     * inner loop depends only on `c`, never on the data.
     *
     * The tail (`dst.size % 8`) goes through [Scalar]. Identical output to [Scalar] at every length
     * and coefficient (`Gf256KernelTest`).
     */
    object Swar : Kernel {
        private const val LOW7 = 0x7F7F7F7F7F7F7F7FL
        private const val HIGH = -0x7F7F7F7F7F7F7F80L // 0x8080808080808080

        /** Lanes whose high bit was set take the reduction 0x1D = 1 + 4 + 8 + 16, spelled as shifts so no 64-bit multiply is needed. */
        private fun xtime(x: Long): Long {
            val h = (x and HIGH) ushr 7 // 0x01 in every lane that overflowed
            return ((x and LOW7) shl 1) xor h xor (h shl 2) xor (h shl 3) xor (h shl 4)
        }

        private fun load(a: ByteArray, o: Int): Long =
            (a[o].toLong() and 0xFF) or ((a[o + 1].toLong() and 0xFF) shl 8) or
                ((a[o + 2].toLong() and 0xFF) shl 16) or ((a[o + 3].toLong() and 0xFF) shl 24) or
                ((a[o + 4].toLong() and 0xFF) shl 32) or ((a[o + 5].toLong() and 0xFF) shl 40) or
                ((a[o + 6].toLong() and 0xFF) shl 48) or ((a[o + 7].toLong() and 0xFF) shl 56)

        private fun store(a: ByteArray, o: Int, v: Long) {
            a[o] = v.toByte(); a[o + 1] = (v ushr 8).toByte(); a[o + 2] = (v ushr 16).toByte(); a[o + 3] = (v ushr 24).toByte()
            a[o + 4] = (v ushr 32).toByte(); a[o + 5] = (v ushr 40).toByte(); a[o + 6] = (v ushr 48).toByte(); a[o + 7] = (v ushr 56).toByte()
        }

        /**
         * `x * c` across eight lanes, branch-free and fully unrolled: bit `k` of `c` becomes an all-ones or
         * all-zeros Long mask (`-(bit)`), so every lane takes the same straight-line path whatever the data
         * and whatever `c` is — the shape a superword pass can widen across consecutive words.
         */
        private fun mul8(x0: Long, c: Int): Long {
            val m0 = -((c) and 1).toLong(); val m1 = -((c ushr 1) and 1).toLong()
            val m2 = -((c ushr 2) and 1).toLong(); val m3 = -((c ushr 3) and 1).toLong()
            val m4 = -((c ushr 4) and 1).toLong(); val m5 = -((c ushr 5) and 1).toLong()
            val m6 = -((c ushr 6) and 1).toLong(); val m7 = -((c ushr 7) and 1).toLong()
            var x = x0
            var acc = x and m0
            x = xtime(x); acc = acc xor (x and m1)
            x = xtime(x); acc = acc xor (x and m2)
            x = xtime(x); acc = acc xor (x and m3)
            x = xtime(x); acc = acc xor (x and m4)
            x = xtime(x); acc = acc xor (x and m5)
            x = xtime(x); acc = acc xor (x and m6)
            x = xtime(x); acc = acc xor (x and m7)
            return acc
        }

        override fun mulAddInto(dst: ByteArray, src: ByteArray, c: Int) {
            if (c == 0) return
            val n = dst.size
            val words = n ushr 3
            var o = 0
            for (w in 0 until words) {
                store(dst, o, load(dst, o) xor mul8(load(src, o), c))
                o += 8
            }
            for (i in o until n) dst[i] = ((dst[i].toInt() and 0xFF) xor mul(src[i].toInt() and 0xFF, c)).toByte()
        }
    }

    /**
     * The kernel [mulAddInto] dispatches to. [Scalar] by default: measured on JDK 25 / Apple M-series
     * (1200 B symbols, 20k calls) the table kernel ran in 16.8 ms and [Swar] in 37.9 ms — in commonMain
     * the eight-byte lanes have to be packed and unpacked byte by byte, which costs more than the two
     * gathers it removes, and C2 did not widen the word loop. [Swar] stays as the exact, tested,
     * autovec-shaped kernel; a platform kernel with real 8-byte loads (a JVM `VarHandle` view, the
     * native SIMD upstream used) installs through [use] the same way. Switching while coders are live
     * is safe: only the next call sees it.
     */
    var kernel: Kernel = Scalar

    fun use(k: Kernel) { kernel = k }
    fun useScalar() { kernel = Scalar }

    fun mulAddInto(dst: ByteArray, src: ByteArray, c: Int) {
        if (c == 0) return
        kernel.mulAddInto(dst, src, c)
    }
}
