package borg.trikeshed.loom

import borg.trikeshed.lib.*
import kotlin.test.*

/** Json.kt's float output against serde_json 1.0.151 ([JsonVectors]). */
class LoomJsonTest {
    @Test
    fun f64WritesAsSerdeJson() {
        for ((bits, text) in JsonVectors.f64.view) assertEquals(text, jsonText(Double.fromBits(bits.toULong(16).toLong())), bits)
    }

    @Test
    fun bitPatternsWriteAsSerdeJson() {
        val state = ulongArrayOf(0x6c6f6f6d2d663634uL)
        assertEquals(JsonVectors.RANDOM_FNV, fnv { splitmix(state).toLong() })
    }

    @Test
    fun decimalLikeValuesWriteAsSerdeJson() {
        val state = ulongArrayOf(0x6c6f6f6d2d663634uL)
        repeat(32768) { splitmix(state) }
        assertEquals(JsonVectors.DECIMAL_FNV, fnv { decimalLike(state) })
    }

    /** FNV-1a 64 of the texts of 32768 values from [bits], each followed by a newline. */
    fun fnv(bits: () -> Long): String {
        var hash = 0xcbf29ce484222325uL
        repeat(32768) {
            for (c in jsonText(Double.fromBits(bits()))) hash = (hash xor c.code.toULong()) * 0x100000001b3uL
            hash = (hash xor 10uL) * 0x100000001b3uL
        }
        return hash.toString(16).padStart(16, '0')
    }

    fun splitmix(state: ULongArray): ULong {
        state[0] += 0x9e3779b97f4a7c15uL
        var z = state[0]
        z = (z xor (z shr 30)) * 0xbf58476d1ce4e5b9uL
        z = (z xor (z shr 27)) * 0x94d049bb133111ebuL
        return z xor (z shr 31)
    }

    /** k below 10^15 divided or multiplied by 10^e (e <= 22), either sign: every operation exact or one IEEE rounding. */
    fun decimalLike(state: ULongArray): Long {
        var pow = 1uL
        repeat(1 + (splitmix(state) % 15uL).toInt()) { pow *= 10uL }
        val k = (splitmix(state) % pow).toLong().toDouble()
        val e = (splitmix(state) % 23uL).toInt()
        val r = splitmix(state)
        var p = 1.0
        repeat(e) { p *= 10.0 }
        val x = if (r and 1uL == 0uL) k / p else k * p
        return (if (r and 2uL == 0uL) x else -x).toRawBits()
    }
}
