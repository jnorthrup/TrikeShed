/**
 * DoubleSeries — element-wise arithmetic and rolling primitives for Series<Double>.
 *
 * These are the pandas-replacement building blocks.  Every operation returns
 * a lazy Series<Double> (size j accessor) unless noted otherwise.
 */
package borg.trikeshed.indicator

import borg.trikeshed.lib.*
import kotlin.math.*

// ── element-wise arithmetic ────────────────────────────────────────────

@JvmName("dplus") infix fun Series<Double>.add(o: Series<Double>): Series<Double> = size j { i: Int -> this[i] + o[i] }
@JvmName("dminus") infix fun Series<Double>.sub(o: Series<Double>): Series<Double> = size j { i: Int -> this[i] - o[i] }
@JvmName("dmul") infix fun Series<Double>.mul(o: Series<Double>): Series<Double> = size j { i: Int -> this[i] * o[i] }
@JvmName("ddiv") infix fun Series<Double>.dvd(o: Series<Double>): Series<Double> = size j { i: Int -> this[i] / o[i] }

@JvmName("dscaleup") infix fun Series<Double>.mul(s: Double): Series<Double> = size j { i: Int -> this[i] * s }
@JvmName("dscaledn") infix fun Series<Double>.dvd(s: Double): Series<Double> = size j { i: Int -> this[i] / s }
@JvmName("doffset") infix fun Series<Double>.add(s: Double): Series<Double> = size j { i: Int -> this[i] + s }

fun Series<Double>.negate(): Series<Double> = size j { i: Int -> -this[i] }
fun Series<Double>.dabs(): Series<Double> = size j { i: Int -> abs(this[i]) }

// ── lag / diff ─────────────────────────────────────────────────────────

/** Lag by n bars (fills index < n with value at 0) */
fun Series<Double>.lag(n: Int = 1): Series<Double> = size j { i: Int -> this[maxOf(0, i - n)] }

/** First difference: x[i] - x[i-1], index 0 = 0.0 */
fun Series<Double>.diff(): Series<Double> = size j { i: Int -> if (i == 0) 0.0 else this[i] - this[i - 1] }

// ── rolling window functions ───────────────────────────────────────────

fun Series<Double>.rollingMean(w: Int): Series<Double> {
    val buf = DoubleArray(size)
    var sum = 0.0
    for (i in 0 until size) {
        sum += this[i]
        if (i >= w) sum -= this[i - w]
        buf[i] = sum / minOf(i + 1, w)
    }
    return buf.toSeries()
}

fun Series<Double>.rollingStd(w: Int): Series<Double> {
    val buf = DoubleArray(size)
    var sum = 0.0; var sum2 = 0.0
    for (i in 0 until size) {
        val v = this[i]; sum += v; sum2 += v * v
        if (i >= w) { val old = this[i - w]; sum -= old; sum2 -= old * old }
        val n = minOf(i + 1, w)
        if (n < 2) { buf[i] = 0.0 } else {
            val mean = sum / n
            buf[i] = sqrt(maxOf(0.0, sum2 / n - mean * mean))
        }
    }
    return buf.toSeries()
}

fun Series<Double>.rollingMin(w: Int): Series<Double> {
    val buf = DoubleArray(size)
    for (i in 0 until size) {
        var m = Double.MAX_VALUE
        val start = maxOf(0, i - w + 1)
        for (k in start..i) m = minOf(m, this[k])
        buf[i] = m
    }
    return buf.toSeries()
}

fun Series<Double>.rollingMax(w: Int): Series<Double> {
    val buf = DoubleArray(size)
    for (i in 0 until size) {
        var m = -Double.MAX_VALUE
        val start = maxOf(0, i - w + 1)
        for (k in start..i) m = maxOf(m, this[k])
        buf[i] = m
    }
    return buf.toSeries()
}

/** Cumulative sum */
fun Series<Double>.cumSum(): Series<Double> {
    val buf = DoubleArray(size)
    var acc = 0.0
    for (i in 0 until size) { acc += this[i]; buf[i] = acc }
    return buf.toSeries()
}

// ── smoothing ──────────────────────────────────────────────────────────

/** Simple Moving Average */
fun Series<Double>.sma(period: Int): Series<Double> = rollingMean(period)

/** Exponential Moving Average */
fun Series<Double>.ema(period: Int): Series<Double> {
    val alpha = 2.0 / (period + 1)
    val buf = DoubleArray(size)
    buf[0] = this[0]
    for (i in 1 until size) buf[i] = alpha * this[i] + (1.0 - alpha) * buf[i - 1]
    return buf.toSeries()
}

/** Wilder's Smoothing (1/period alpha) */
fun Series<Double>.wilderSmooth(period: Int): Series<Double> {
    val alpha = 1.0 / period
    val buf = DoubleArray(size)
    buf[0] = this[0]
    for (i in 1 until size) buf[i] = alpha * this[i] + (1.0 - alpha) * buf[i - 1]
    return buf.toSeries()
}
