package borg.trikeshed.couch.kline

import borg.trikeshed.collections.s_
import borg.trikeshed.miniduck.DocRowVec
import borg.trikeshed.lib.Series

/**
 * Standard kline (OHLCV candle) timespans.
 */
enum class TimeSpan(val seconds: Long, val binanceInterval: String) {
    Seconds30(30L, "30s"),
    Minutes1(60L, "1m"),
    Minutes3(180L, "3m"),
    Minutes5(300L, "5m"),
    Minutes15(900L, "15m"),
    Minutes30(1800L, "30m"),
    Hours1(3600L, "1h"),
    Hours2(7200L, "2h"),
    Hours4(14400L, "4h"),
    Hours6(21600L, "6h"),
    Hours8(28800L, "8h"),
    Hours12(43200L, "12h"),
    Days1(86400L, "1d"),
    Days3(259200L, "3d"),
    Weeks1(604800L, "1w"),
    Months1(2592000L, "1M"),
}

/**
 * A single kline (OHLCV candlestick).
 */
data class Kline(
    val symbol: String,
    val timespan: TimeSpan,
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
) {
    companion object {
        val schemaKeys: Series<String> = s_[
            "symbol", "timespan", "openTime", "open", "high", "low", "close", "volume"
        ]
    }

    fun toDocRowVec(): DocRowVec = DocRowVec(
        keys = schemaKeys,
        cells = s_[symbol, timespan, openTime, open, high, low, close, volume],
    )
}

/**
 * Extended kline with all 12 Binance fields.
 */
data class ExtendedKline(
    val symbol: String,
    val timespan: TimeSpan,
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val closeTime: Long,
    val quoteAssetVolume: Double,
    val trades: Int,
    val takerBuyBaseVolume: Double,
    val takerBuyQuoteVolume: Double,
) {
    fun toKline(): Kline = Kline(symbol, timespan, openTime, open, high, low, close, volume)

    fun toDocRowVec(): DocRowVec = DocRowVec(
        keys = s_[
            "symbol", "timespan", "openTime",
            "open", "high", "low", "close", "volume",
            "closeTime", "quoteAssetVolume", "trades",
            "takerBuyBaseVolume", "takerBuyQuoteVolume"
        ],
        cells = s_[
            symbol, timespan, openTime,
            open, high, low, close, volume,
            closeTime, quoteAssetVolume, trades,
            takerBuyBaseVolume, takerBuyQuoteVolume
        ],
    )
}

/** Re-export for type consistency. */
typealias Klines = List<Kline>
