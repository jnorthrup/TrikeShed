package narchy.spacegraph.util

// Port of upstream src/utils/math.ts at 125b369.
// Dependency-free math primitives. The LCG follows JS double arithmetic: the product is rounded to a double, then
// reduced modulo 2^32 (ToUint32) or taken mod 2^31 by the masked variant, so outputs match upstream bit for bit.

fun clamp(v: Double, min: Double, max: Double): Double = maxOf(min, minOf(v, max))

fun clamp01(v: Double): Double = clamp(v, 0.0, 1.0)

fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

private const val LCG_MULTIPLIER = 1103515245.0
private const val LCG_INCREMENT = 12345.0
private const val TWO_POW_32 = 4294967296.0

/** ToUint32 of a double, as an exact non-negative double below 2^32. */
private fun toUint32(x: Double): Double {
    val r = x % TWO_POW_32
    return if (r < 0.0) r + TWO_POW_32 else r
}

/** Deterministic 32-bit LCG used by reproducible samplers and generators. */
fun seededRandom(seed: Double): () -> Double {
    var value = toUint32(seed)
    return {
        value = toUint32(value * LCG_MULTIPLIER + LCG_INCREMENT)
        value / TWO_POW_32
    }
}

/** 31-bit LCG variant that keeps the historical output of the graph generators. */
fun seededRandom31(seed: Double): () -> Double {
    var value = seed
    return {
        val masked = toUint32(value * LCG_MULTIPLIER + LCG_INCREMENT).toLong() and 0x7fffffffL
        value = masked.toDouble()
        masked.toDouble() / 0x7fffffff.toDouble()
    }
}
