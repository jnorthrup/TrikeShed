package borg.trikeshed.dreamer

import borg.trikeshed.couch.kline.KlineBlock
import borg.trikeshed.couch.kline.TimeSpan
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.miniduck.MiniCursor

typealias TradingPair = Join<String, String>
typealias KlineSeriesKey = Join<TradingPair, TimeSpan>

data class ArchiveMonth(val year: Int, val month: Int) {
    init {
        require(month in 1..12) { "month must be in 1..12, got $month" }
    }

    val label: String get() = "$year-${month.twoDigits()}"
}

data class ArchiveDay(val year: Int, val month: Int, val day: Int) {
    init {
        require(month in 1..12) { "month must be in 1..12, got $month" }
        require(day in 1..31) { "day must be in 1..31, got $day" }
    }

    val label: String get() = "$year-${month.twoDigits()}-${day.twoDigits()}"
}

data class BinanceVisionArchiveRef(
    val url: String,
    val checksumUrl: String,
    val cachePath: String,
    val checksumCachePath: String,
)

data class BinanceVisionArchivePlan(
    val key: KlineSeriesKey,
    val monthly: List<BinanceVisionArchiveRef>,
    val daily: List<BinanceVisionArchiveRef>,
) {
    val refs: List<BinanceVisionArchiveRef> get() = monthly + daily
}

data class KlineFeedResult(
    val key: KlineSeriesKey,
    val block: KlineBlock,
) {
    val miniCursor: MiniCursor get() = block.asCursor()
    val cursor: Cursor get() = block.asColumnarCursor()
}

interface KlineFeed {
    fun plan(key: KlineSeriesKey, months: List<ArchiveMonth>, days: List<ArchiveDay> = emptyList()): BinanceVisionArchivePlan
    fun parseCachedCsv(key: KlineSeriesKey, csvText: String): KlineFeedResult
}

class BinanceVisionKlineFeed(
    private val cacheRoot: String = "mpdata/import",
    private val baseUrl: String = "https://data.binance.vision/data/spot",
) : KlineFeed {

    override fun plan(
        key: KlineSeriesKey,
        months: List<ArchiveMonth>,
        days: List<ArchiveDay>,
    ): BinanceVisionArchivePlan {
        val symbol = key.symbol
        val interval = key.b.binanceInterval
        return BinanceVisionArchivePlan(
            key = key,
            monthly = months.map { month ->
                archiveRef(
                    pathKind = "monthly",
                    symbol = symbol,
                    interval = interval,
                    label = month.label,
                )
            },
            daily = days.map { day ->
                archiveRef(
                    pathKind = "daily",
                    symbol = symbol,
                    interval = interval,
                    label = day.label,
                )
            },
        )
    }

    override fun parseCachedCsv(key: KlineSeriesKey, csvText: String): KlineFeedResult {
        val chars: Series<Char> = csvText.length j { index: Int -> csvText[index] }
        val klines = klinesFromCsv(chars, key.symbol, key.b)
        val block = KlineBlock.mutable(key.b)
        for (i in 0 until klines.size) {
            block.append(klines.b(i).toKline())
        }
        return KlineFeedResult(key, block.seal())
    }

    private fun archiveRef(pathKind: String, symbol: String, interval: String, label: String): BinanceVisionArchiveRef {
        val relative = "$pathKind/klines/$symbol/$interval/$symbol-$interval-$label.zip"
        val url = "$baseUrl/$relative"
        val cachePath = "$cacheRoot/$relative"
        return BinanceVisionArchiveRef(
            url = url,
            checksumUrl = "$url.CHECKSUM",
            cachePath = cachePath,
            checksumCachePath = "$cachePath.CHECKSUM",
        )
    }
}

val KlineSeriesKey.symbol: String get() = "${a.a}${a.b}"

fun tradingPair(base: String, quote: String): TradingPair = base j quote

fun klineSeriesKey(base: String, quote: String, timespan: TimeSpan): KlineSeriesKey =
    tradingPair(base, quote) j timespan

private fun Int.twoDigits(): String = if (this < 10) "0$this" else toString()
