package borg.trikeshed.couch.kline

import borg.trikeshed.miniduck.DocRowVec

/**
 * Standard kline (OHLCV candle) timespans.
 *
 * Maps to Binance interval strings and provides canonical duration in seconds.
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
 *
 * Schema mirrors the Binance kline CSV columns from DataBinanceVision.klines:
 *   Open_time, Open, High, Low, Close, Volume
 * plus symbol and timespan metadata.
 *
 * The donor's CandlestickEvent carries these as String fields (open, high, etc.)
 * and converts via todub(). Here we store them as Double from the start.
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
        /** Standard OHLCV column names for DocRowVec projection. */
        val schemaKeys = listOf(
            "symbol", "timespan", "openTime", "open", "high", "low", "close", "volume"
        )
    }

    /** Project this kline as a DocRowVec with standard OHLCV keys. */
    fun toDocRowVec(): DocRowVec = DocRowVec(
        keys = schemaKeys,
        cells = listOf(symbol, timespan, openTime, open, high, low, close, volume),
    )
}

/**
 * Extended kline with all 12 fields from Binance CSV / REST API.
 *
 * Donor: dreamer-kmm exchange module — ExtendedKline was originally defined
 * there.  Moved here so the domain type lives alongside [Kline] in the
 * canonical [borg.trikeshed.couch.kline] package.
 *
 * Schema matches Binance kline CSV columns:
 *   Open_time, Open, High, Low, Close, Volume, Close_time,
 *   Quote_asset_volume, Number_of_trades,
 *   Taker_buy_base_asset_volume, Taker_buy_quote_asset_volume, Ignore
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
    companion object {
        /** Full 12-field schema keys for DocRowVec projection. */
        val schemaKeys = listOf(
            "symbol", "timespan", "openTime",
            "open", "high", "low", "close", "volume",
            "closeTime", "quoteAssetVolume", "trades",
            "takerBuyBaseVolume", "takerBuyQuoteVolume"
        )
    }

    /** Downcast to the base [Kline] (OHLCV subset). */
    fun toKline(): Kline = Kline(symbol, timespan, openTime, open, high, low, close, volume)

    /** Project as a DocRowVec with all 12 Binance fields. */
    fun toDocRowVec(): DocRowVec = DocRowVec(
        keys = schemaKeys,
        cells = listOf(
            symbol, timespan, openTime,
            open, high, low, close, volume,
            closeTime, quoteAssetVolume, trades,
            takerBuyBaseVolume, takerBuyQuoteVolume
        ),
    )
}
