package borg.trikeshed.integration

import borg.trikeshed.couch.kline.Kline
import borg.trikeshed.couch.kline.KlineBlock
import borg.trikeshed.couch.kline.KlineCollector
import borg.trikeshed.couch.kline.TimeSpan
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.lib.j
import borg.trikeshed.cursor.at
import borg.trikeshed.miniduck.emptyCursor
import borg.trikeshed.userspace.concurrency.Channel
import borg.trikeshed.userspace.concurrency.ChannelCapacity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream

class BinanceKlineFetchException(
    message: String,
    val symbol: String,
    val interval: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val failedUrls: List<String>,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

private data class BinanceKlineFetchOutcome(
    val date: LocalDate,
    val url: String,
    val rowCount: Int,
    val failure: Throwable? = null,
)

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
   val maxConcurrentFetches: Int = 4,
   private val csvFetcher: suspend (String) -> String = { url -> defaultFetchCsv(url) },
) {
    init {
        require(symbol.isNotBlank()) { "symbol must not be blank" }
        require(!startDate.isAfter(endDate)) { "startDate must not be after endDate" }
        require(maxConcurrentFetches > 0) { "maxConcurrentFetches must be positive" }
    }

    /**
     * Fetch all klines synchronously and return a flat MiniCursor.
     * Each row is a DocRowVec with keys: symbol, timespan, openTime, open, high, low, close, volume
     */
    fun fetchCursor(): Cursor {
        val blocks = mutableListOf<KlineBlock>()
        runBlocking {
            fetchAll { block -> blocks.add(block) }
        }
        return blocksToCursor(blocks)
    }

    /**
     * Alias for [fetchCursor] — returns a MiniCursor ready for projection / SQL.
     */
    fun open(): Cursor = fetchCursor()

   suspend fun fetchAll(onBlock: (KlineBlock) -> Unit) = coroutineScope {
        val channel: Channel<Kline> = Channel(ChannelCapacity.Unbounded)
        val collector = KlineCollector(blockCapacity)
        val collected = mutableListOf<KlineBlock>()

        val collectorJob = launch {
            collector.collect(channel) { block ->
                collected.add(block)
                onBlock(block)
            }
        }

        val semaphore = Semaphore(maxConcurrentFetches)
        val dates = requestedDates()
        val outcomes = dates.map { date ->
            async {
                semaphore.withPermit {
                    fetchDay(date, channel)
                }
            }
        }.map { it.await() }

        channel.close()
        collectorJob.join()

        val emittedRows = collected.sumOf { it.rowCount }
        if (emittedRows == 0 && outcomes.isNotEmpty()) {
            val missed = outcomes.filter { it.rowCount == 0 }.map { it.url }
            if (missed.size == outcomes.size) {
                val firstFailure = outcomes.firstNotNullOfOrNull { it.failure }
                throw BinanceKlineFetchException(
                    message = "no Binance kline data for $symbol $interval $startDate..$endDate; failed URLs=${missed.size}",
                    symbol = symbol,
                    interval = interval,
                    startDate = startDate,
                    endDate = endDate,
                    failedUrls = missed,
                    cause = firstFailure,
                )
            }
        }
    }

   suspend fun fetchKlines(channel: Channel<Kline>) {
        requestedDates().forEach { date -> fetchDay(date, channel) }
    }

    private fun requestedDates(): List<LocalDate> =
        generateSequence(startDate) { it.plusDays(1) }
            .takeWhile { !it.isAfter(endDate) }
            .toList()

   private suspend fun fetchDay(date: LocalDate, channel: Channel<Kline>): BinanceKlineFetchOutcome {
        val urlStr = buildUrl(date)
        return try {
            val csv = csvFetcher(urlStr)
            val klines = parseCsv(csv)
            klines.forEach { kline -> channel.send(kline) }
            BinanceKlineFetchOutcome(date = date, url = urlStr, rowCount = klines.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("Warning: failed to fetch $urlStr: ${e.message}")
            BinanceKlineFetchOutcome(date = date, url = urlStr, rowCount = 0, failure = e)
        }
    }

   fun buildUrl(date: LocalDate): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        return "https://data.binance.vision/data/spot/daily/klines/$symbol/$interval/$symbol-$interval-${date.format(formatter)}.zip"
    }

   fun fetchCsv(urlStr: String): String = runBlocking { csvFetcher(urlStr) }

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
     */
   fun blocksToCursor(blocks: List<KlineBlock>): Cursor {
        val totalRows = blocks.sumOf { it.rowCount }
        if (totalRows == 0) return emptyCursor()

        return totalRows j { rowIdx: Int ->
            var remaining = rowIdx
            for (block in blocks) {
                if (remaining < block.rowCount) {
                    val cur = block.asCursor()
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

        suspend fun defaultFetchCsv(urlStr: String): String = withContext(Dispatchers.IO) {
            val conn = URI(urlStr).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            try {
                when (conn.responseCode) {
                    200 -> {
                        val input = conn.inputStream
                        if (urlStr.endsWith(".zip")) {
                            ZipInputStream(input).use { zis ->
                                checkNotNull(zis.nextEntry) { "ZIP has no entries: $urlStr" }
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
    }
}
