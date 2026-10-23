/**
 * FeatureExtractor — main entry point for computing all technical indicators.
 *
 * Takes close, high, low and volume as Series<Double> columns, or as a Cursor,
 * and returns named indicator Series or a feature Cursor. This is the pandas DataFrame replacement:
 * instead of mutating a frame, we return lazy Series that can be
 * composed, indexed, or materialized on demand.
 */
package borg.trikeshed.indicator

import borg.trikeshed.lib.*
import borg.trikeshed.lib.get
import borg.trikeshed.cursor.*
import borg.trikeshed.cursor.CursorTensorReifier.toTensorDouble
import borg.trikeshed.isam.meta.IOMemento

object FeatureExtractor {

    /**
     * Project named OHLCV columns into the indicator algebra; output columns carry IoDouble metadata.
     * Numeric cells follow the Cursor tensor conversion, including null as NaN. Empty input has no schema row.
     */
    fun compute(cursor: Cursor): Cursor {
        if (cursor.size == 0) return cursor
        val source = cursor.select("close", "high", "low", "volume")
        fun column(index: Int): Series<Double> {
            val name = source.b(0).b(index).b().name
            return source.size j { row: Int -> source.b(row).b(index).a.toTensorDouble(name, row, index) }
        }
        val features = compute(column(0), column(1), column(2), column(3)).entries.toSeries()
        val metadata = features α { ColumnMeta(it.key, IOMemento.IoDouble).`↺` }
        return cursor.size j { row: Int ->
            features.size j { column: Int -> features[column].value[row] j metadata[column] }
        }
    }

    /**
     * Compute all 22 indicator families from OHLCV data.
     *
     * Families 1-15: Technical analysis foundation.
     * Families 16-22: Institutional quant layer — risk-adjusted returns,
     * drawdown, autocorrelation, adaptive filtering, entropy,
     * efficient volatility, and market microstructure.
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

        // ── Institutional Quant Layer ──────────────────────────────────

        // 16. Sharpe & Sortino
        RiskAdjusted.compute(close).let { (sharpe, sortino) ->
            put("sharpe_20", sharpe); put("sortino_20", sortino)
        }

        // 17. Drawdown
        Drawdown.compute(close).let { (dd, maxDd) ->
            put("drawdown", dd); put("max_drawdown", maxDd)
        }

        // 18. Autocorrelation
        put("autocorr_1", Autocorrelation.compute(close, 20, 1))
        put("autocorr_5", Autocorrelation.compute(close, 20, 5))

        // 19. KAMA
        put("kama", KAMA.compute(close))

        // 20. Entropy
        put("entropy", Entropy.compute(close))

        // 21. Parkinson Volatility
        put("parkinson_vol", ParkinsonVol.compute(high, low))

        // 22. Market Microstructure
        Microstructure.compute(close, volume).let { (amihud, kyle) ->
            put("amihud_illiquidity", amihud); put("kyle_lambda", kyle)
        }
    }
}
