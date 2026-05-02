package borg.trikeshed.dreamer

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.cellsToRowVec
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries

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
    ;

    /** Number of bars in a 252 trading-day year, based on this timespan's duration. */
    val barsPerYear: Double get() = (252.0 * 24.0 * 3600.0) / seconds.toDouble()

    /** Annualization factor for Sharpe/Sortino: sqrt(barsPerYear). */
    val annualizationFactor: Double get() = kotlin.math.sqrt(barsPerYear)
}

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
        private val schemaList = listOf("symbol", "timespan", "openTime", "open", "high", "low", "close", "volume")
        val schemaKeys: Series<String> = schemaList.toSeries()
    }

    fun toRowVec(): RowVec = cellsToRowVec(
        cells = listOf(symbol, timespan.toString(), openTime, open, high, low, close, volume).toSeries(),
        keys = Kline.schemaKeys,
    )
}

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
}

class KlineBlock(
    val mutableRows: MutableList<Kline>,
    var mutableState: State,
    val timespan: TimeSpan?,
) {
    enum class State { MUTABLE, SEALED }

    val state: State get() = mutableState
    val rowCount: Int get() = mutableRows.size
    val rows: List<Kline> get() = mutableRows.toList()

    companion object {
        fun mutable(timespan: TimeSpan? = null): KlineBlock =
            KlineBlock(mutableListOf(), State.MUTABLE, timespan)
    }

    fun append(kline: Kline) {
        check(mutableState == State.MUTABLE) { "Cannot append to sealed block" }
        if (timespan != null && kline.timespan != timespan) {
            throw IllegalArgumentException("Mixed timespan: block expects $timespan but got ${kline.timespan}")
        }
        mutableRows += kline
    }

    fun seal(): KlineBlock {
        check(mutableState == State.MUTABLE) { "Already sealed" }
        mutableState = State.SEALED
        return this
    }

    fun asCursor(): Cursor {
        check(mutableState == State.SEALED) { "Block must be sealed before presenting as cursor" }
        val snapshot = rows.toTypedArray()
        return snapshot.size j { index: Int -> snapshot[index].toRowVec() }
    }

    fun asColumnarCursor(): Cursor {
        check(mutableState == State.SEALED) { "Block must be sealed before presenting as cursor" }
        val snapshot = rows
        return snapshot.size j { rowIndex: Int ->
            val kline: Kline = snapshot[rowIndex]
            cellsToRowVec(
                cells = listOf(
                    kline.symbol,
                    kline.timespan.toString(),
                    kline.openTime,
                    kline.open,
                    kline.high,
                    kline.low,
                    kline.close,
                    kline.volume,
                ).toSeries(),
                keys = Kline.schemaKeys,
            )
        }
    }
}
