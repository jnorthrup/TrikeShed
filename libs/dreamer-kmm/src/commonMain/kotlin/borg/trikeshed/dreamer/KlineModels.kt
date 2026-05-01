package borg.trikeshed.dreamer

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.joins
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries
import borg.trikeshed.miniduck.DocRowVec
import borg.trikeshed.miniduck.toRowVec
import borg.trikeshed.lib.toSeries
import borg.trikeshed.dreamer.adapter.*

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
        val schemaKeys = listOf("symbol", "timespan", "openTime", "open", "high", "low", "close", "volume")
    }

    fun toDocRowVec(): DocRowVec = DocRowVec(
        keys = schemaKeys,
        cells = listOf(symbol, timespan, openTime, open, high, low, close, volume),
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

class KlineBlock public constructor(
    public val mutableRows: MutableList<Kline>,
    public var mutableState: State,
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
        return snapshot.size j { index: Int -> snapshot[index].toTrikeRowVec() }
    }

    fun asColumnarCursor(): Cursor {
        check(mutableState == State.SEALED) { "Block must be sealed before presenting as cursor" }
        val snapshot = rows
        return snapshot.size j { rowIndex: Int ->
            val kline = snapshot[rowIndex]
            val cells: List<Any?> = listOf(
                kline.symbol,
                kline.timespan.toString(),
                kline.openTime,
                kline.open,
                kline.high,
                kline.low,
                kline.close,
                kline.volume,
            )
            val values: Series<Any?> = cells.toSeries()
            val meta = cells.size j { columnIndex: Int ->
                val type = when (cells[columnIndex]) {
                    is Double -> IOMemento.IoDouble
                    is Float -> IOMemento.IoFloat
                    is Long -> IOMemento.IoLong
                    is Int -> IOMemento.IoInt
                    is Boolean -> IOMemento.IoBoolean
                    else -> IOMemento.IoString
                }
                { ColumnMeta(Kline.schemaKeys[columnIndex], type) }
            }
            values.joins(meta)
        }
    }
}
