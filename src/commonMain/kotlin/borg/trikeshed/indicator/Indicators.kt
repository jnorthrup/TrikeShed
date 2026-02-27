/**
 * Technical indicator families — pandas replacement in pure Kotlin.
 *
 * Each object is a stateless compute function taking Series<Double> inputs
 * and returning Series<Double> outputs.  All 15 families match the
 * Python InstrumentPanel nomenclature exactly.
 */
package borg.trikeshed.indicator

import borg.trikeshed.lib.*
import kotlin.math.*

// ── 1. Returns & Momentum ──────────────────────────────────────────────

object ReturnsMomentum {
    fun compute(close: Series<Double>): Map<String, Series<Double>> {
        val logRet = close.size j { i: Int ->
            if (i == 0) 0.0 else ln(close[i] / close[i - 1])
        }
        val ret1d = close.size j { i: Int ->
            if (i == 0) 0.0 else (close[i] - close[i - 1]) / close[i - 1]
        }
        return mapOf("log_return" to logRet, "return_1d" to ret1d)
    }
}

// ── 2. EMA & MACD ──────────────────────────────────────────────────────

object EmaMacd {
    fun compute(close: Series<Double>): Map<String, Series<Double>> {
        val ema5 = close.ema(5)
        val ema10 = close.ema(10)
        val ema12 = close.ema(12)
        val ema20 = close.ema(20)
        val ema26 = close.ema(26)
        val ema50 = close.ema(50)
        val macdLine = ema12 sub ema26
        val macdSignal = macdLine.ema(9)
        val macdHist = macdLine sub macdSignal
        return mapOf(
            "ema_5" to ema5, "ema_10" to ema10, "ema_12" to ema12,
            "ema_20" to ema20, "ema_26" to ema26, "ema_50" to ema50,
            "macd_line" to macdLine, "macd_signal" to macdSignal, "macd_hist" to macdHist
        )
    }
}

// ── 3. RSI ──────────────────────────────────────────────────────────────

object RSI {
    fun compute(close: Series<Double>, period: Int = 14): Series<Double> {
        val n = close.size
        val gains = DoubleArray(n)
        val losses = DoubleArray(n)
        for (i in 1 until n) {
            val d = close[i] - close[i - 1]
            gains[i] = maxOf(d, 0.0)
            losses[i] = maxOf(-d, 0.0)
        }
        val avgGain = gains.toSeries().wilderSmooth(period)
        val avgLoss = losses.toSeries().wilderSmooth(period)
        return n j { i: Int ->
            if (avgLoss[i] == 0.0) 100.0
            else 100.0 - (100.0 / (1.0 + avgGain[i] / avgLoss[i]))
        }
    }
}

// ── 4. Bollinger Bands ──────────────────────────────────────────────────

object Bollinger {
    data class Result(val upper: Series<Double>, val middle: Series<Double>, val lower: Series<Double>)

    fun compute(close: Series<Double>, period: Int = 20, stdDev: Double = 2.0): Result {
        val middle = close.sma(period)
        val std = close.rollingStd(period)
        val upper = middle.size j { i: Int -> middle[i] + std[i] * stdDev }
        val lower = middle.size j { i: Int -> middle[i] - std[i] * stdDev }
        return Result(upper, middle, lower)
    }
}

// ── 5. ATR ──────────────────────────────────────────────────────────────

object ATR {
    fun compute(high: Series<Double>, low: Series<Double>, close: Series<Double>, period: Int = 14): Series<Double> {
        val n = high.size
        val tr = DoubleArray(n)
        tr[0] = high[0] - low[0]
        for (i in 1 until n) {
            val hl = high[i] - low[i]
            val hc = abs(high[i] - close[i - 1])
            val lc = abs(low[i] - close[i - 1])
            tr[i] = maxOf(hl, hc, lc)
        }
        return tr.toSeries().wilderSmooth(period)
    }
}

// ── 6. Stochastic ───────────────────────────────────────────────────────

object Stochastic {
    data class Result(val k: Series<Double>, val d: Series<Double>)

    fun compute(
        high: Series<Double>, low: Series<Double>, close: Series<Double>,
        kPeriod: Int = 14, dPeriod: Int = 3
    ): Result {
        val lowN = low.rollingMin(kPeriod)
        val highN = high.rollingMax(kPeriod)
        val stochK = close.size j { i: Int ->
            val range = highN[i] - lowN[i]
            if (range == 0.0) 50.0 else ((close[i] - lowN[i]) / range) * 100.0
        }
        val stochD = stochK.sma(dPeriod)
        return Result(stochK, stochD)
    }
}

// ── 7. ADX ──────────────────────────────────────────────────────────────

object ADX {
    data class Result(val adx: Series<Double>, val plusDi: Series<Double>, val minusDi: Series<Double>)

    fun compute(high: Series<Double>, low: Series<Double>, close: Series<Double>, period: Int = 14): Result {
        val n = high.size
        val plusDM = DoubleArray(n)
        val minusDM = DoubleArray(n)
        for (i in 1 until n) {
            val upMove = high[i] - high[i - 1]
            val downMove = low[i - 1] - low[i]
            plusDM[i] = if (upMove > downMove && upMove > 0.0) upMove else 0.0
            minusDM[i] = if (downMove > upMove && downMove > 0.0) downMove else 0.0
        }
        val atr = ATR.compute(high, low, close, period)
        val smoothPlus = plusDM.toSeries().wilderSmooth(period)
        val smoothMinus = minusDM.toSeries().wilderSmooth(period)
        val plusDi = n j { i: Int -> if (atr[i] == 0.0) 0.0 else 100.0 * smoothPlus[i] / atr[i] }
        val minusDi = n j { i: Int -> if (atr[i] == 0.0) 0.0 else 100.0 * smoothMinus[i] / atr[i] }
        val dx = n j { i: Int ->
            val sum = plusDi[i] + minusDi[i]
            if (sum == 0.0) 0.0 else 100.0 * abs(plusDi[i] - minusDi[i]) / sum
        }
        val adx = dx.wilderSmooth(period)
        return Result(adx, plusDi, minusDi)
    }
}

// ── 8. VWAP ─────────────────────────────────────────────────────────────

object VWAP {
    fun compute(high: Series<Double>, low: Series<Double>, close: Series<Double>, volume: Series<Double>): Series<Double> {
        val n = high.size
        val buf = DoubleArray(n)
        var cumPv = 0.0; var cumVol = 0.0
        for (i in 0 until n) {
            val tp = (high[i] + low[i] + close[i]) / 3.0
            cumPv += tp * volume[i]; cumVol += volume[i]
            buf[i] = if (cumVol == 0.0) tp else cumPv / cumVol
        }
        return buf.toSeries()
    }
}

// ── 9. Z-Score ──────────────────────────────────────────────────────────

object ZScore {
    fun compute(close: Series<Double>, period: Int = 20): Series<Double> {
        val mean = close.rollingMean(period)
        val std = close.rollingStd(period)
        return close.size j { i: Int ->
            if (std[i] == 0.0) 0.0 else (close[i] - mean[i]) / std[i]
        }
    }
}

// ── 10. Volatility ──────────────────────────────────────────────────────

object Volatility {
    fun compute(close: Series<Double>, period: Int = 20): Series<Double> {
        val n = close.size
        val ret = DoubleArray(n)
        for (i in 1 until n) ret[i] = (close[i] - close[i - 1]) / close[i - 1]
        return ret.toSeries().rollingStd(period)
    }
}

// ── 11. Donchian Channels ───────────────────────────────────────────────

object Donchian {
    data class Result(val upper: Series<Double>, val middle: Series<Double>, val lower: Series<Double>)

    fun compute(high: Series<Double>, low: Series<Double>, period: Int = 20): Result {
        val upper = high.rollingMax(period)
        val lower = low.rollingMin(period)
        val middle = upper.size j { i: Int -> (upper[i] + lower[i]) / 2.0 }
        return Result(upper, middle, lower)
    }
}

// ── 12. Volume Flow (MFI, OBV) ──────────────────────────────────────────

object VolumeFlow {
    fun mfi(high: Series<Double>, low: Series<Double>, close: Series<Double>,
            volume: Series<Double>, period: Int = 14): Series<Double> {
        val n = high.size
        val posFlow = DoubleArray(n)
        val negFlow = DoubleArray(n)
        var prevTp = (high[0] + low[0] + close[0]) / 3.0
        for (i in 1 until n) {
            val tp = (high[i] + low[i] + close[i]) / 3.0
            val mf = tp * volume[i]
            if (tp > prevTp) posFlow[i] = mf else if (tp < prevTp) negFlow[i] = mf
            prevTp = tp
        }
        val posSmooth = posFlow.toSeries().wilderSmooth(period)
        val negSmooth = negFlow.toSeries().wilderSmooth(period)
        return n j { i: Int ->
            if (negSmooth[i] == 0.0) 100.0
            else 100.0 - (100.0 / (1.0 + posSmooth[i] / negSmooth[i]))
        }
    }

    fun obv(close: Series<Double>, volume: Series<Double>): Series<Double> {
        val n = close.size
        val buf = DoubleArray(n)
        for (i in 1 until n) {
            val dir = when {
                close[i] > close[i - 1] -> 1.0
                close[i] < close[i - 1] -> -1.0
                else -> 0.0
            }
            buf[i] = buf[i - 1] + dir * volume[i]
        }
        return buf.toSeries()
    }
}

// ── 13. Spread ──────────────────────────────────────────────────────────

object Spread {
    fun compute(high: Series<Double>, low: Series<Double>, close: Series<Double>): Series<Double> =
        high.size j { i: Int -> (high[i] - low[i]) / close[i] }
}

// ── 14. Kalman Filter ───────────────────────────────────────────────────

object Kalman {
    data class Result(val filter: Series<Double>, val velocity: Series<Double>)

    fun compute(close: Series<Double>, r: Double = 0.001, q: Double = 0.0001): Result {
        val n = close.size
        val filters = DoubleArray(n)
        val velocities = DoubleArray(n)
        var estimate = close[0]; var error = 1.0; var vel = 0.0
        for (i in 0 until n) {
            val predicted = estimate + vel
            val predError = error + q
            val gain = predError / (predError + r)
            estimate = predicted + gain * (close[i] - predicted)
            error = (1.0 - gain) * predError
            vel = estimate - predicted
            filters[i] = estimate; velocities[i] = vel
        }
        return Result(filters.toSeries(), velocities.toSeries())
    }
}

// ── 15. Hurst Exponent ──────────────────────────────────────────────────

object Hurst {
    /** R/S analysis for Hurst exponent estimation */
    fun compute(close: Series<Double>, period: Int = 100): Series<Double> {
        val n = close.size
        val buf = DoubleArray(n)
        for (i in 0 until n) {
            if (i < period) { buf[i] = 0.5; continue }
            // R/S on window [i-period+1 .. i]
            var sum = 0.0
            for (k in (i - period + 2)..i) sum += ln(close[k] / close[k - 1])
            val mean = sum / (period - 1)
            var cumDev = 0.0; var maxCum = -Double.MAX_VALUE; var minCum = Double.MAX_VALUE
            var s2 = 0.0
            for (k in (i - period + 2)..i) {
                val ret = ln(close[k] / close[k - 1])
                cumDev += ret - mean
                maxCum = maxOf(maxCum, cumDev); minCum = minOf(minCum, cumDev)
                s2 += (ret - mean) * (ret - mean)
            }
            val std = sqrt(s2 / (period - 1))
            val rs = if (std == 0.0) 1.0 else (maxCum - minCum) / std
            buf[i] = if (rs <= 0.0) 0.5 else ln(rs) / ln(period.toDouble())
        }
        return buf.toSeries()
    }
}
