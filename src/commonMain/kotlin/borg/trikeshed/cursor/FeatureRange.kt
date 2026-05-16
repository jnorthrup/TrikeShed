package borg.trikeshed.cursor

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j
import kotlin.jvm.JvmName
import kotlin.math.max
import kotlin.math.min

/**
 * Pure Kotlin normalize/featureRange utilities.
 * Ported from columnar/vec/ml/FeatureRange.kt
 */
typealias Tw1n<T> = Join<T, T>

/** Returns (min, max) range of an Iterable. */
@JvmName("featureRangeGeneric")
fun <T : Comparable<T>> featureRange(seq: Iterable<T>, maxMinTwin: Tw1n<T>): Tw1n<T> =
    featureRange_(seq, maxMinTwin)

/** Returns (min, max) range of an Iterable with given initial bounds. */
fun <T : Comparable<T>> featureRange_(seq: Iterable<T>, maxMinTwin: Tw1n<T>): Tw1n<T> =
    seq.fold(maxMinTwin) { incumbent, candidate ->
        val (incMin, incMax) = incumbent
        val cmin = minOf(incMin, candidate)
        val cmax = maxOf(candidate, incMax)
        when {
            incMax !== candidate && candidate === cmax || incMin !== candidate && candidate === cmin -> cmin `j` cmax
            else -> incumbent
        }
    }

/** featureRange for Int */
@JvmName("featureRangeInt")
fun featureRange(seq: Iterable<Int>, maxMinTwin: Tw1n<Int> = Int.MAX_VALUE `j` Int.MIN_VALUE): Tw1n<Int> =
    featureRange_(seq, maxMinTwin)

/** featureRange for Long */
@JvmName("featureRangeLong")
fun featureRange(seq: Iterable<Long>, maxMinTwin: Tw1n<Long> = Long.MAX_VALUE `j` Long.MIN_VALUE): Tw1n<Long> =
    featureRange_(seq, maxMinTwin)

/** featureRange for Double */
@JvmName("featureRangeDouble")
fun featureRange(seq: Iterable<Double>, maxMinTwin: Tw1n<Double> = Double.MAX_VALUE `j` Double.MIN_VALUE): Tw1n<Double> =
    featureRange_(seq, maxMinTwin)

/** featureRange for Float */
@JvmName("featureRangeFloat")
fun featureRange(seq: Iterable<Float>, maxMinTwin: Tw1n<Float> = Float.MAX_VALUE `j` Float.MIN_VALUE): Tw1n<Float> =
    featureRange_(seq, maxMinTwin)

/** Normalize value to [0,1] range given (min, max) twin. */
inline fun Tw1n<Double>.normalize(d: Double): Double {
    val (min, max) = this
    return if (max == min) 0.0 else ((d - min) / (max - min))
}

/** Normalize value to [0,1] range given (min, max) twin. */
inline fun Tw1n<Float>.normalize(d: Float): Double {
    val (min, max) = this
    return if (max == min) 0.0 else ((d - min) / (max - min)).toDouble()
}

/** Normalize value to [0,1] range given (min, max) twin. */
inline fun Tw1n<Int>.normalize(d: Int): Double {
    val (min, max) = this
    return if (max == min) 0.0 else ((d - min) / (max - min).toDouble())
}

/** Normalize value to [0,1] range given (min, max) twin. */
inline fun Tw1n<Long>.normalize(d: Long): Double {
    val (min, max) = this
    return if (max == min) 0.0 else ((d - min) / (max - min).toDouble())
}

/** Denormalize from [0,1] range back to original scale. */
inline fun Tw1n<Double>.deNormalize(d: Double): Double {
    val (min, max) = this
    return d * (max - min) + min
}

/** Denormalize from [0,1] range back to original scale. */
inline fun Tw1n<Float>.deNormalize(d: Double): Float {
    val (min, max) = this
    return (d * (max - min) + min).toFloat()
}

/** Denormalize from [0,1] range back to original scale. */
inline fun Tw1n<Int>.deNormalize(d: Double): Int {
    val (min, max) = this
    return ((d * (max - min) + min) + 0.5).toInt()
}

/** Denormalize from [0,1] range back to original scale. */
inline fun Tw1n<Long>.deNormalize(d: Double): Long {
    val (min, max) = this
    return ((d * (max - min) + min) + 0.5).toLong()
}

/** Normalize double with explicit range. */
@JvmName("normalizeDoubleRange")
fun normalize(range: Tw1n<Double>, value: Double): Double = range.normalize(value)

/** Normalize float with explicit range. */
@JvmName("normalizeFloatRange")
fun normalize(range: Tw1n<Float>, value: Float): Double = range.normalize(value)

/** Normalize int with explicit range. */
@JvmName("normalizeIntRange")
fun normalize(range: Tw1n<Int>, value: Int): Double = range.normalize(value)

/** Normalize long with explicit range. */
@JvmName("normalizeLongRange")
fun normalize(range: Tw1n<Long>, value: Long): Double = range.normalize(value)

/** Denormalize double with explicit range. */
@JvmName("deNormalizeDoubleRange")
fun deNormalize(range: Tw1n<Double>, value: Double): Double = range.deNormalize(value)

/** Denormalize float with explicit range. */
@JvmName("deNormalizeFloatRange")
fun deNormalize(range: Tw1n<Float>, value: Float): Float = range.deNormalize(value.toDouble()).toFloat()

/** Denormalize int with explicit range. */
@JvmName("deNormalizeIntRange")
fun deNormalize(range: Tw1n<Int>, value: Double): Int = range.deNormalize(value)

/** Denormalize long with explicit range. */
@JvmName("deNormalizeLongRange")
fun deNormalize(range: Tw1n<Long>, value: Double): Long = range.deNormalize(value)