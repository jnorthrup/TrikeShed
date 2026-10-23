/**
 * Trikeshed Feature Extractor - Indicator Library
 *
 * Technical analysis indicators using Trikeshed Cursor/Series abstractions.
 * Maintains exact Python/InstrumentPanel nomenclature.
 */
package borg.trikeshed.indicator

import borg.trikeshed.cursor.Cursor

/**
 * FeatureExtractor - Main entry point for computing technical indicators
 *
 * Input: OHLCV cursor
 * Output: Feature cursor with computed indicators
 */
object FeatureExtractor {
    /**
     * Compute all technical indicators from OHLCV data
     */
    fun compute(cursor: Cursor): Cursor {
        // TODO: Implement indicator computations using Trikeshed Series/Cursor
        // See TRIKESHED_FEATURE_EXTRACTOR_DESIGN.md for full specification
        return cursor
    }
}

/**
 * Indicator family names (matching Python InstrumentPanel)
 */
object IndicatorFamilies {
    const val RETURNS_MOMENTUM = "returns_momentum"
    const val EMA_MACD = "ema_macd"
    const val RSI = "rsi"
    const val BOLLINGER = "bollinger"
    const val ATR = "atr"
    const val STOCHASTIC = "stochastic"
    const val ADX = "adx"
    const val VWAP = "vwap"
    const val ZSCORE = "zscore"
    const val VOLATILITY = "volatility"
    const val DONCHIAN = "donchian"
    const val VOLUME_FLOW = "volume_flow"
    const val SPREAD = "spread"
    const val PERCENTILE_RANK = "percentile_rank"
    const val KALMAN = "kalman"
    const val HURST = "hurst"
}
