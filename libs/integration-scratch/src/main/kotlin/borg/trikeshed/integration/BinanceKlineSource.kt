package borg.trikeshed.integration

import borg.trikeshed.couch.kline.Kline
import borg.trikeshed.couch.kline.KlineBlock
import borg.trikeshed.couch.kline.KlineCollector
import borg.trikeshed.couch.kline.TimeSpan
import borg.trikeshed.miniduck.MiniCursor
import borg.trikeshed.miniduck.at
import borg.trikeshed.miniduck.emptyMiniCursor
import borg.trikeshed.lib.j
import borg.trikeshed.userspace.concurrency.Channel
import borg.trikeshed.userspace.concurrency.ChannelCapacity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream

/**
 * Fetches Binance OHLCV Kline data and presents it as a [MiniCursor] of DocRowVec.
 *
 * Draw-through pipeline:
 *   Binance CSV ZIP
 *     → fetchCsv / ZipInputStream → CSV string
 *     → parseCsv() → List<Kline>
 *     → KlineCollector (Channel + blockCapacity) → sealed KlineBlocks
 *     → blocksToCursor() → MiniCursor
 *
 * The resulting MiniCursor is a lazy Series<MiniRowVec> where each row
 * is a DocRowVec with keys: symbol, timespan, openTime, open, high, low, close, volume
 */
class BinanceKlineSource(
   val symbol: String,
   val interval: String = "1h",
   val startDate: LocalDate,
   val endDate: LocalDate,
   val blockCapacity: Int = 500,
) {
    init {
        require(symbol.isNotBlank()) { "symbol must not be blank" }
        require(!startDate.isAfter(endDate)) { "startDate must not be after endDate" }
    }

    /**
     * Fetch all klines synchronously and return a flat MiniCursor.
     * Each row is a DocRowVec with keys: symbol, timespan, openTime, open, high, low, close, volume
     */
    fun fetchCursor(): MiniCursor {
        val blocks = mutableListOf<KlineBlock>()
        runBlocking {
            fetchAll { block -> blocks.add(block) }
        }
        return blocksToCursor(blocks)
    }

    /**
     * Alias for [fetchCursor] — returns a MiniCursor ready for projection / SQL.
     */
    fun open(): MiniCursor = fetchCursor()

   suspend fun fetchAll(onBlock: (KlineBlock) -> Unit) {
        val channel: Channel<Kline> = Channel(ChannelCapacity.Unbounded)
        val collector = KlineCollector(blockCapacity)

        val fetcher = CoroutineScope(Dispatchers.IO).launch {
            try {
                fetchKlines(channel)
            } finally {
                channel.close()
            }
        }

        collector.collect(channel, onBlock)
        fetcher.join()
    }

   suspend fun fetchKlines(channel: Channel<Kline>) {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        var date = startDate
        while (!date.isAfter(endDate)) {
            val urlStr = buildUrl(date)
            try {
                val csv = fetchCsv(urlStr)
                parseCsv(csv).forEach { kline ->
                    channel.send(kline)
                }
            } catch (e: Exception) {
                System.err.println("Warning: failed to fetch $urlStr: ${e.message}")
            }
            date = date.plusDays(1)
        }
    }

   fun buildUrl(date: LocalDate): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        return "https://data.binance.vision/data/spot/daily/klines/$symbol/$interval/$symbol-$interval-${date.format(formatter)}.csv"
    }

   fun fetchCsv(urlStr: String): String {
        val conn = URI(urlStr).toURL().openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        return try {
            when (conn.responseCode) {
                200 -> {
                    val input = conn.inputStream
                    if (urlStr.endsWith(".zip")) {
                        ZipInputStream(input).use { zis ->
                            zis.nextEntry
                            zis.bufferedReader().readText()
                        }
                    } else {
                        input.bufferedReader().readText()
                    }
                }
                404 -> throw IllegalStateException("Not found: $urlStr")
                else -> throw IllegalStateException("HTTP ${conn.responseCode} for $urlStr")
            }
        } finally {
            conn.disconnect()
        }
    }

   fun parseCsv(csv: String): List<Kline> {
        val timespan = intervalToTimeSpan(interval)
        return csv.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val fields = line.split(',')
                if (fields.size < 6) return@mapNotNull null
                try {
                    Kline(
                        symbol = symbol,
                        timespan = timespan,
                        openTime = fields[0].toLong(),
                        open = fields[1].toDouble(),
                        high = fields[2].toDouble(),
                        low = fields[3].toDouble(),
                        close = fields[4].toDouble(),
                        volume = fields[5].toDouble(),
                    )
                } catch (e: NumberFormatException) {
                    null
                }
            }
            .toList()
    }

   fun intervalToTimeSpan(interval: String): TimeSpan = when (interval) {
        "1m"  -> TimeSpan.Minutes1
        "3m"  -> TimeSpan.Minutes3
        "5m"  -> TimeSpan.Minutes5
        "15m" -> TimeSpan.Minutes15
        "30m" -> TimeSpan.Minutes30
        "1h"  -> TimeSpan.Hours1
        "2h"  -> TimeSpan.Hours2
        "4h"  -> TimeSpan.Hours4
        "6h"  -> TimeSpan.Hours6
        "8h"  -> TimeSpan.Hours8
        "12h" -> TimeSpan.Hours12
        "1d"  -> TimeSpan.Days1
        "3d"  -> TimeSpan.Days3
        "1w"  -> TimeSpan.Weeks1
        "1M"  -> TimeSpan.Months1
        else  -> TimeSpan.Hours1
    }

    /**
     * Convert a list of sealed KlineBlocks into a single flat MiniCursor.
     * Uses the `j` infix constructor (Join factory) for lazy indexed access.
     *
     * `j` here is the `borg.trikeshed.lib.j` infix extension:
     *   infix fun <A, B> A.j(b: B): Join<A, B>
     * So `totalRows j { rowIdx -> ... }` produces Join<Int, (Int) -> MiniRowVec>
     * which is Series<MiniRowVec> = MiniCursor
     */
   fun blocksToCursor(blocks: List<KlineBlock>): MiniCursor {
        val totalRows = blocks.sumOf { it.rowCount }
        if (totalRows == 0) return emptyMiniCursor()

        // totalRows j { ... } = Series<MiniRowVec> = MiniCursor
        // The lambda returns MiniRowVec (DocRowVec from kline.toDocRowVec())
        return totalRows j { rowIdx: Int ->
            var remaining = rowIdx
            for (block in blocks) {
                if (remaining < block.rowCount) {
                    val cur = block.asCursor()
                    // cur at remaining = MiniRowVec (DocRowVec)
                    return@j (cur at remaining)
                }
                remaining -= block.rowCount
            }
            throw IndexOutOfBoundsException("row $rowIdx out of range $totalRows")
        }
    }

    companion object {
        /** OHLCV schema keys for Binance klines (DocRowVec projection). */
        val OHLCV_KEYS = listOf("openTime", "open", "high", "low", "close", "volume")
    }
}
