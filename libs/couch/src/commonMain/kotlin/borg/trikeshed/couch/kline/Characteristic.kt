package borg.trikeshed.couch.kline

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/**
 * KlineCharacteristic — a named, typed, observable property of a kline.
 *
 * Characteristics flow through two spaces:
 *   [BLACKBOARD]  — shared pub/sub field; all characteristics live here simultaneously
 *   [FUNNEL]      — narrowing pipe; each stage can inspect, tag, branch, or buffer
 *
 * A characteristic is NOT the kline itself — it is a derived observation about the kline.
 * Examples: "rsi_14 > 70", "volume > 20d_avg", "spread > 0.001", "body_ratio > 0.6"
 *
 * The blackboard is a dark-matter field — characteristics float independently of their kline.
 * Subscribers observe specific characteristic types and react when thresholds trigger.
 *
 * A funnel stage can:
 *   - PASS   → kline proceeds unchanged
 *   - MAP    → kline gets a derived characteristic attached
 *   - BRANCH → kline (and its context) divert to a side-channel
 *   - BUFFER → klines accumulate for batch decision (e.g. volume spike detection)
 */
interface KlineCharacteristic {
    val name: String
    val value: Any?
    val unit: String
    val timestamp: Long  // openTime of the kline this char was derived from
    val origin: String   // which funnel stage produced it
}

/** A characteristic that computed from a single kline. */
data class AtomicChar(
    override val name: String,
    override val value: Any?,
    override val unit: String = "",
    override val timestamp: Long,
    override val origin: String,
) : KlineCharacteristic

/** A characteristic computed from a window of klines. */
data class WindowChar(
    override val name: String,
    override val value: Any?,
    override val unit: String = "",
    override val timestamp: Long,
    override val origin: String,
    val windowSize: Int,
    val windowStart: Long,
    val windowEnd: Long,
) : KlineCharacteristic

/** A comparative characteristic — ratio or spread relative to something else. */
data class RatioChar(
    override val name: String,
    override val value: Double,
    override val unit: String = "ratio",
    override val timestamp: Long,
    override val origin: String,
    val numerator: KlineCharacteristic?,
    val denominator: KlineCharacteristic?,
) : KlineCharacteristic

// ─── Characteristic taxonomy ───────────────────────────────────────────────────

/** All known characteristic names (shared vocabulary). */
object CharName {
    // Price action
    val BodyRatio = "body_ratio"           // |close - open| / (high - low)
    val UpperWick = "upper_wick"            // high - max(open, close)
    val LowerWick = "lower_wick"            // min(open, close) - low
    val WickToBody = "wick_to_body"          // max(wick) / |body|
    val Direction = "direction"              // +1 bullish, -1 bearish, 0 neutral

    // Volatility
    val Range = "range"                      // high - low (absolute)
    val RangePct = "range_pct"               // (high - low) / open (normalized)
    val ATR = "atr"                           // average true range (windowed)
    val BollingerPosition = "bb_position"    // where close sits in Bollinger band (0-1)

    // Volume
    val VolumeRatio = "volume_ratio"         // volume / volume_sma (dimensionless)
    val VolumeProfile = "volume_profile"     // buyVolume / (buyVolume + sellVolume)
    val VWAP = "vwap"                        // volume-weighted average price

    // Momentum
    val RSI = "rsi"                          // relative strength index (14)
    val MACD = "macd"                        // MACD line value
    val MACDSignal = "macd_signal"           // MACD signal line
    val MACDHistogram = "macd_histogram"     // MACD - signal
    val StochasticK = "stoch_k"             // stochastic %K
    val StochasticD = "stoch_d"             // stochastic %D

    // Trend
    val SMA20 = "sma_20"                      // 20-period simple moving average
    val SMA50 = "sma_50"
    val EMA12 = "ema_12"
    val EMA26 = "ema_26"
    val PriceVsSMA = "price_vs_sma"          // (close - sma) / sma
    val TrendStrength = "trend_strength"     // ADX or equivalent

    // Spread
    val Spread = "spread"                     // ask - bid (absolute)
    val SpreadPct = "spread_pct"             // spread / mid-price

    // Pattern flags
    val Doji = "doji"                        // body < 10% of range
    val Hammer = "hammer"                    // lower wick > 2x body, upper wick < 20%
    val Engulfing = "engulfing"              // bullish engulfing pattern
    val GapUp = "gap_up"                     // open > prev high
    val GapDown = "gap_down"                 // open < prev low
}

/** Standard characteristic units. */
object CharUnit {
    val Ratio = "ratio"
    val Pct = "%"
    val Currency = "$"
    val Bps = "bps"     // basis points
    val Count = "ct"
    val Bool = "y/n"
    val Time = "ms"
}