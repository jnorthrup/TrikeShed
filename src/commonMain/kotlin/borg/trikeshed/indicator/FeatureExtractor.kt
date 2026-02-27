/**
 * FeatureExtractor — main entry point for computing all technical indicators.
 *
 * Takes OHLCV as 5 Series<Double> columns, returns a named map of all
 * computed indicator Series.  This is the pandas DataFrame replacement:
 * instead of mutating a frame, we return lazy Series that can be
 * composed, indexed, or materialized on demand.
 */
package borg.trikeshed.indicator

import borg.trikeshed.lib.*

object FeatureExtractor {

    /**
     * Compute all 15 indicator families from OHLCV data.
     *
     * @return Map of indicator name to Series<Double>
     */
    fun compute(
        close: Series<Double>,
        high: Series<Double>,
        low: Series<Double>,
        volume: Series<Double>
    ): Map<String, Series<Double>> = buildMap {

        // 1. Returns & Momentum
        putAll(ReturnsMomentum.compute(close))

        // 2. EMA & MACD
        putAll(EmaMacd.compute(close))

        // 3. RSI
        put("rsi_14", RSI.compute(close, 14))

        // 4. Bollinger Bands
        Bollinger.compute(close, 20, 2.0).let { (u, m, l) ->
            put("bb_upper", u); put("bb_middle", m); put("bb_lower", l)
        }

        // 5. ATR
        put("atr_14", ATR.compute(high, low, close, 14))

        // 6. Stochastic
        Stochastic.compute(high, low, close).let { (k, d) ->
            put("stoch_k", k); put("stoch_d", d)
        }

        // 7. ADX
        ADX.compute(high, low, close).let { (adx, pdi, mdi) ->
            put("adx_14", adx); put("plus_di", pdi); put("minus_di", mdi)
        }

        // 8. VWAP
        put("vwap", VWAP.compute(high, low, close, volume))

        // 9. Z-Score
        put("zscore_20", ZScore.compute(close, 20))
        put("zscore_50", ZScore.compute(close, 50))

        // 10. Volatility
        put("volatility_20", Volatility.compute(close, 20))
        put("volatility_50", Volatility.compute(close, 50))

        // 11. Donchian Channels
        Donchian.compute(high, low).let { (u, m, l) ->
            put("donchian_upper", u); put("donchian_middle", m); put("donchian_lower", l)
        }

        // 12. Volume Flow
        put("mfi", VolumeFlow.mfi(high, low, close, volume))
        put("obv", VolumeFlow.obv(close, volume))

        // 13. Spread
        put("spread", Spread.compute(high, low, close))

        // 14. Kalman Filter
        Kalman.compute(close).let { (f, v) ->
            put("kalman_filter", f); put("kalman_velocity", v)
        }

        // 15. Hurst Exponent
        put("hurst_exponent", Hurst.compute(close))
    }
}
