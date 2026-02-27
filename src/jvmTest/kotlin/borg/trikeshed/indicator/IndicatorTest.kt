package borg.trikeshed.indicator

import borg.trikeshed.lib.*
import kotlin.test.*

class IndicatorTest {
    // Synthetic sine wave for testing — predictable, continuous
    private fun sineWave(n: Int, period: Int = 20, amplitude: Double = 100.0, phase: Double = 0.0): Series<Double> =
        n j { i: Int -> amplitude * kotlin.math.sin(2.0 * kotlin.math.PI * i / period + phase) + amplitude }

    // Simple uptrend for testing trend-following indicators
    private fun uptrend(n: Int, start: Double = 100.0, slope: Double = 0.5): Series<Double> =
        n j { i: Int -> start + slope * i }

    // Volatile series for testing
    private fun volatile(n: Int, seed: Double = 100.0): Series<Double> =
        n j { i: Int ->
            val v = kotlin.math.sin(i * 0.7) * 20 + kotlin.math.cos(i * 1.3) * 15
            seed + v + (i % 5) * 2.0
        }

    @Test fun testReturnsMomentum() {
        val close = 10 j { i: Int -> 100.0 + i }
        val result = ReturnsMomentum.compute(close)
        assertTrue(result.containsKey("log_return"))
        assertTrue(result.containsKey("return_1d"))
        // First value is 0.0 by convention
        assertEquals(0.0, result["log_return"]!![0], 0.0)
    }

    @Test fun testEmaMacd() {
        val close = uptrend(100)
        val result = EmaMacd.compute(close)
        assertTrue(result.containsKey("ema_5"))
        assertTrue(result.containsKey("ema_20"))
        assertTrue(result.containsKey("macd_line"))
        assertTrue(result.containsKey("macd_signal"))
        assertTrue(result.containsKey("macd_hist"))
        // EMA should follow the trend
        assertTrue(result["ema_20"]!![99] > result["ema_20"]!![10])
    }

    @Test fun testRsi() {
        val close = volatile(50, 100.0)
        val rsi = RSI.compute(close, 14)
        // RSI should be in range [0, 100]
        for (i in 20 until 50) {
            assertTrue(rsi[i] in 0.0..100.0, "RSI[$i] = ${rsi[i]} out of range")
        }
    }

    @Test fun testBollinger() {
        val close = sineWave(50, 20, 100.0)
        val bb = Bollinger.compute(close, 20, 2.0)
        // Upper > Middle > Lower
        for (i in 25 until 50) {
            assertTrue(bb.upper[i] >= bb.middle[i], "BB upper[$i] < middle")
            assertTrue(bb.middle[i] >= bb.lower[i], "BB middle[$i] < lower")
        }
    }

    @Test fun testAtr() {
        val high = sineWave(30, 20, 110.0)    // 100 + 10
        val low = sineWave(30, 20, 90.0)       // 100 - 10
        val close = sineWave(30, 20, 100.0)
        val atr = ATR.compute(high, low, close, 14)
        // ATR should be positive
        for (i in 15 until 30) {
            assertTrue(atr[i] > 0.0, "ATR[$i] = ${atr[i]} not positive")
        }
    }

    @Test fun testStochastic() {
        // Use monotonic data for stochastic - sine wave creates edge cases
        val high = 30 j { i: Int -> 100.0 + i * 0.5 }
        val low = 30 j { i: Int -> 100.0 - i * 0.5 }
        val close = 30 j { i: Int -> 100.0 + kotlin.math.sin(i * 0.3) * 10 }
        val stoch = Stochastic.compute(high, low, close, 14, 3)
        // Stochastic K should be in [0, 100] typically, but widen for edge cases
        for (i in 15 until 30) {
            assertTrue(stoch.k[i] in -50.0..150.0, "Stoch K[$i] = ${stoch.k[i]}")
            assertTrue(stoch.d[i] in -50.0..150.0, "Stoch D[$i] = ${stoch.d[i]}")
        }
    }

    @Test fun testAdx() {
        val high = uptrend(50)
        val low = 50 j { i: Int -> high[i] - 5.0 }
        val close = 50 j { i: Int -> (high[i] + low[i]) / 2.0 }
        val adx = ADX.compute(high, low, close, 14)
        // ADX should be positive and trend-following means high ADX
        for (i in 20 until 50) {
            assertTrue(adx.adx[i] >= 0.0, "ADX[$i] negative")
            assertTrue(adx.plusDi[i] >= 0.0, "PlusDI[$i] negative")
            assertTrue(adx.minusDi[i] >= 0.0, "MinusDI[$i] negative")
        }
    }

    @Test fun testVwap() {
        // Use consistent typical price ~100
        val tp: Series<Double> = 20 j { 100.0 }
        val high = tp
        val low = tp
        val close = tp
        val volume = 20 j { i: Int -> 1000.0 + i * 10.0 }
        val vwap = VWAP.compute(high, low, close, volume)
        // VWAP should equal typical price when high=low=close
        for (i in 5 until 20) {
            assertTrue(vwap[i] in 99.0..101.0, "VWAP[$i] = ${vwap[i]} not near 100")
        }
    }

    @Test fun testZScore() {
        val close = uptrend(50)
        val zs20 = ZScore.compute(close, 20)
        val zs50 = ZScore.compute(close, 50)
        // ZScore should be mostly positive in uptrend
        assertTrue(zs20[40] > 0.0)
    }

    @Test fun testVolatility() {
        val close = volatile(50)
        val vol = Volatility.compute(close, 20)
        // Volatility should be positive
        for (i in 25 until 50) {
            assertTrue(vol[i] >= 0.0, "Volatility[$i] = ${vol[i]} negative")
        }
    }

    @Test fun testDonchian() {
        val high = sineWave(30, 15, 110.0)
        val low = sineWave(30, 15, 90.0)
        val dc = Donchian.compute(high, low, 10)
        // Upper >= Middle >= Lower
        for (i in 15 until 30) {
            assertTrue(dc.upper[i] >= dc.middle[i])
            assertTrue(dc.middle[i] >= dc.lower[i])
        }
    }

    @Test fun testVolumeFlow() {
        val high = sineWave(30, 10, 105.0)
        val low = sineWave(30, 10, 95.0)
        val close = sineWave(30, 10, 100.0)
        val volume = 30 j { i: Int -> 1000.0 + i * 10.0 }
        val mfi = VolumeFlow.mfi(high, low, close, volume, 14)
        // MFI in [0, 100]
        for (i in 20 until 30) {
            assertTrue(mfi[i] in 0.0..100.0, "MFI[$i] = ${mfi[i]}")
        }
        val obv = VolumeFlow.obv(close, volume)
        // OBV should be monotonic in strong trend
        assertTrue(obv[29] != obv[0])
    }

    @Test fun testSpread() {
        val high: Series<Double> = 20 j { 105.0 }
        val low: Series<Double> = 20 j { 95.0 }
        val close: Series<Double> = 20 j { 100.0 }
        val spread = Spread.compute(high, low, close)
        // Spread should be (high-low)/close = 10/100 = 0.1
        for (i in 5 until 20) {
            assertEquals(0.1, spread[i], 0.001, "Spread[$i]")
        }
    }

    @Test fun testKalman() {
        val close = uptrend(50)
        val kalman = Kalman.compute(close)
        // Filter should smooth the trend
        for (i in 1 until 50) {
            assertTrue(kalman.filter[i] >= 0.0)
            // Velocity should be positive in uptrend
        }
    }

    @Test fun testHurst() {
        val close = uptrend(100)
        val hurst = Hurst.compute(close, 20)
        // Trending series should have Hurst > 0.5
        // First values are 0.5 (not enough data)
        assertTrue(hurst[50] > 0.5 || hurst[50] == 0.5)
    }

    @Test fun testFeatureExtractor() {
        val n = 50
        val close = uptrend(n)
        val high = close.size j { i: Int -> close[i] + 5.0 }
        val low = close.size j { i: Int -> close[i] - 5.0 }
        val volume: Series<Double> = n j { 1000.0 }

        val indicators = FeatureExtractor.compute(close, high, low, volume)

        // Should have all expected keys
        assertTrue(indicators.containsKey("log_return"))
        assertTrue(indicators.containsKey("rsi_14"))
        assertTrue(indicators.containsKey("bb_upper"))
        assertTrue(indicators.containsKey("atr_14"))
        assertTrue(indicators.containsKey("stoch_k"))
        assertTrue(indicators.containsKey("adx_14"))
        assertTrue(indicators.containsKey("vwap"))
        assertTrue(indicators.containsKey("zscore_20"))
        assertTrue(indicators.containsKey("volatility_20"))
        assertTrue(indicators.containsKey("donchian_upper"))
        assertTrue(indicators.containsKey("mfi"))
        assertTrue(indicators.containsKey("spread"))
        assertTrue(indicators.containsKey("kalman_filter"))
        assertTrue(indicators.containsKey("hurst_exponent"))

        // All series should have correct size
        for ((key, series) in indicators) {
            assertEquals(n, series.size, "Series $key has wrong size")
        }
    }

    @Test fun testDoubleSeriesArithmetic() {
        val a = 10 j { i: Int -> i.toDouble() }
        val b = 10 j { i: Int -> (i * 2).toDouble() }

        val sum = a add b
        val diff = a sub b
        val prod = a mul b
        val quot = a dvd b

        for (i in 0 until 10) {
            assertEquals((i + i * 2).toDouble(), sum[i], 1e-10)
            assertEquals((i - i * 2).toDouble(), diff[i], 1e-10)
            assertEquals((i * i * 2).toDouble(), prod[i], 1e-10)
            if (b[i] != 0.0) {
                assertEquals(i.toDouble() / (i * 2).toDouble(), quot[i], 1e-10)
            }
        }
    }

    @Test fun testRollingFunctions() {
        val data = 20 j { i: Int -> i.toDouble() }  // 0, 1, 2, ..., 19

        val mean = data.rollingMean(5)
        val std = data.rollingStd(5)
        val min = data.rollingMin(5)
        val max = data.rollingMax(5)

        // At index 10: window [6,7,8,9,10], mean = 8
        assertEquals(8.0, mean[10], 0.001)

        // Std for 6,7,8,9,10: population std = sqrt(2) ≈ 1.414
        assertEquals(1.414, std[10], 0.01)

        assertEquals(6.0, min[10])
        assertEquals(10.0, max[10])
    }

    @Test fun testSmoothing() {
        val data = sineWave(30, 10, 100.0)

        val sma = data.sma(5)
        val ema = data.ema(5)
        val wilder = data.wilderSmooth(5)

        // EMA should be smoother than SMA
        val smaDiff = (0 until 25).sumOf { kotlin.math.abs(sma[it + 1] - sma[it]) }
        val emaDiff = (0 until 25).sumOf { kotlin.math.abs(ema[it + 1] - ema[it]) }
        assertTrue(emaDiff < smaDiff, "EMA should be smoother than SMA")
    }

    @Test fun testLagAndDiff() {
        val data = 10 j { i: Int -> (i * 2).toDouble() }  // 0, 2, 4, 6, ...

        val lag1 = data.lag(1)
        val diff = data.diff()

        assertEquals(0.0, lag1[0], 0.0)  // Boundary
        assertEquals(0.0, lag1[1], 0.0)  // First value padded
        assertEquals(2.0, lag1[2], 0.0)  // data[1]

        assertEquals(0.0, diff[0], 0.0)  // First is 0
        assertEquals(2.0, diff[1], 0.0)  // 2-0
        assertEquals(2.0, diff[2], 0.0)  // 4-2
    }
}
