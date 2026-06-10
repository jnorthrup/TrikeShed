package borg.trikeshed.integration

import borg.trikeshed.couch.kline.Kline
import borg.trikeshed.couch.kline.KlineBlock
import borg.trikeshed.couch.kline.KlineCollector
import borg.trikeshed.couch.kline.TimeSpan
import borg.trikeshed.miniduck.exec.Cursor
import borg.trikeshed.miniduck.exec.ExecutionContext
import borg.trikeshed.miniduck.exec.TableSource
import borg.trikeshed.miniduck.schema.ColumnSchema
import borg.trikeshed.miniduck.schema.SchemaManager
import borg.trikeshed.miniduck.schema.TableSchema
import borg.trikeshed.userspace.concurrency.Channel
import borg.trikeshed.userspace.concurrency.ChannelCapacity
import kotlinx.coroutines.Dispatchers
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

/**
 * TableSource that fetches Binance OHLCV klines and serves them as MiniDuck rows.
 * The table name is the symbol, e.g. "BTCUSDT".
 *
 * Columns: symbol, timespan, openTime, open, high, low, close, volume
 *
 * Fetch pipeline:
 *   date range → coroutineScope fan-out (Semaphore(4)) → HTTP GET per day
 *   → parse CSV → Channel<Kline> → KlineCollector → sealed KlineBlocks
 *   → BinanceCursor
 */
class BinanceTableSource(
    val symbol: String,
    val interval: String = "1h",
    val startDate: LocalDate,
    val endDate: LocalDate,
    val blockCapacity: Int = 500,
) : TableSource {

    private val ohlcvSchema = TableSchema(
        name = symbol,
        columns = listOf(
            ColumnSchema(0, "symbol"),
            ColumnSchema(1, "timespan"),
            ColumnSchema(2, "openTime"),
            ColumnSchema(3, "open"),
            ColumnSchema(4, "high"),
            ColumnSchema(5, "low"),
            ColumnSchema(6, "close"),
            ColumnSchema(7, "volume"),
        ),
    )

    private val schemaManager: SchemaManager = object : SchemaManager {
        override suspend fun getTableSuspend(name: String): TableSchema? =
            if (name == symbol) ohlcvSchema else null

        override fun getTable(name: String): TableSchema? =
            if (name == symbol) ohlcvSchema else null

        override suspend fun createTableSuspend(schema: TableSchema) {}
        override suspend fun ensureColumnsSuspend(table: String, cols: List<String>): TableSchema = ohlcvSchema
        override fun ensureColumns(table: String, cols: List<String>): TableSchema = ohlcvSchema
    }

    // Lazily fetched, populated by the first open() / openSuspend() call.
    @Volatile
    private var blocks: List<KlineBlock>? = null

    init {
        require(symbol.isNotBlank()) { "symbol must not be blank" }
        require(!startDate.isAfter(endDate)) { "startDate must not be after endDate" }
    }

    // ── TableSource ──────────────────────────────────────────────────────────

    /**
     * Synchronous open: delegates to [openSuspend] via the default bridge
     * ([runBlockingCommon]), which fetches on first call and caches blocks.
     */
    override fun open(execCtx: ExecutionContext, tableName: String): Cursor {
        require(tableName == symbol) { "Unknown table: $tableName (expected $symbol)" }
        // First call triggers the fetch via openSuspend bridging.
        // Subsequent calls reuse cached blocks.
        return BinanceCursor(resolvedBlocks(execCtx))
    }

    override suspend fun openSuspend(execCtx: ExecutionContext, tableName: String): Cursor {
        require(tableName == symbol) { "Unknown table: $tableName (expected $symbol)" }
        return BinanceCursor(resolvedBlocksSuspend())
    }

    fun getSchema(): TableSchema = ohlcvSchema
    fun getSchemaManager(): SchemaManager = schemaManager

    // ── fetch pipeline ───────────────────────────────────────────────────────

    /**
     * Returns cached blocks if already fetched; otherwise fetches synchronously
     * (called from [open] via the default bridge).
     */
    private fun resolvedBlocks(execCtx: ExecutionContext): List<KlineBlock> {
        blocks?.let { return it }
        val fetched = runBlocking { fetchAllBlocks() }
        synchronized(this) {
            if (blocks == null) blocks = fetched
        }
        return blocks!!
    }

    private suspend fun resolvedBlocksSuspend(): List<KlineBlock> {
        blocks?.let { return it }
        val fetched = fetchAllBlocks()
        synchronized(this) {
            if (blocks == null) blocks = fetched
        }
        return blocks!!
    }

    /**
     * Structured fetch: coroutineScope fan-out across dates with Semaphore(4) throttle.
     * Each date → HTTP GET → parse CSV → send klines into a buffered Channel.
     * KlineCollector drains the channel into sealed [KlineBlock]s.
     */
    private suspend fun fetchAllBlocks(): List<KlineBlock> = coroutineScope {
        val channel = Channel<Kline>(ChannelCapacity.Buffered, bufferSize = 2000)
        val collector = KlineCollector(blockCapacity)
        val collected = mutableListOf<KlineBlock>()

        // Drain channel into sealed blocks as data arrives.
        val collectorJob = launch {
            collector.collect(channel) { block -> collected.add(block) }
        }

        // Fan out date fetches with concurrency throttle.
        val semaphore = Semaphore(4)
        val dates = generateSequence(startDate) { it.plusDays(1) }
            .takeWhile { !it.isAfter(endDate) }
            .toList()

        dates.map { date ->
            launch {
                semaphore.withPermit {
                    fetchDay(date, channel)
                }
            }
        }.forEach { it.join() }

        channel.close()
        collectorJob.join()

        collected.toList()
    }

    /**
     * Fetch a single day's kline CSV, parse it, and send each [Kline] into [channel].
     * Called concurrently by multiple coroutines (throttled by Semaphore).
     */
    private suspend fun fetchDay(date: LocalDate, channel: Channel<Kline>) {
        val urlStr = buildUrl(date)
        try {
            val csv = fetchCsv(urlStr)
            val timespan = intervalToTimeSpan(interval)
            parseCsv(csv, timespan).forEach { kline ->
                channel.send(kline)
            }
        } catch (e: Exception) {
            System.err.println("Warning: failed to fetch $urlStr: ${e.message}")
        }
    }

    fun buildUrl(date: LocalDate): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        return "https://data.binance.vision/data/spot/daily/klines/$symbol/$interval/" +
            "$symbol-$interval-${date.format(formatter)}.csv"
    }

    /**
     * Fetch CSV from URL. Uses [withContext(Dispatchers.IO)] to avoid blocking
     * the coroutine dispatcher. Handles plain CSV and gzipped CSV.
     */
    private suspend fun fetchCsv(urlStr: String): String = withContext(Dispatchers.IO) {
        val conn = URI(urlStr).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        try {
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

    fun parseCsv(csv: String, timespan: TimeSpan = intervalToTimeSpan(interval)): List<Kline> {
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
        "1m" -> TimeSpan.Minutes1
        "3m" -> TimeSpan.Minutes3
        "5m" -> TimeSpan.Minutes5
        "15m" -> TimeSpan.Minutes15
        "30m" -> TimeSpan.Minutes30
        "1h" -> TimeSpan.Hours1
        "2h" -> TimeSpan.Hours2
        "4h" -> TimeSpan.Hours4
        "6h" -> TimeSpan.Hours6
        "8h" -> TimeSpan.Hours8
        "12h" -> TimeSpan.Hours12
        "1d" -> TimeSpan.Days1
        "3d" -> TimeSpan.Days3
        "1w" -> TimeSpan.Weeks1
        "1M" -> TimeSpan.Months1
        else -> TimeSpan.Hours1
    }
}
