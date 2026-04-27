package borg.trikeshed.integration

import borg.trikeshed.couch.kline.Kline
import borg.trikeshed.couch.kline.KlineBlock
import borg.trikeshed.couch.kline.KlineCollector
import borg.trikeshed.couch.kline.TimeSpan
import borg.trikeshed.miniduck.DocRowVec
import borg.trikeshed.miniduck.MiniRowVec
import borg.trikeshed.miniduck.at
import borg.trikeshed.miniduck.exec.Cursor
import borg.trikeshed.miniduck.exec.ExecutionContext
import borg.trikeshed.miniduck.exec.RowAccessor
import borg.trikeshed.miniduck.exec.TableSource
import borg.trikeshed.miniduck.schema.ColumnSchema
import borg.trikeshed.miniduck.schema.SchemaManager
import borg.trikeshed.miniduck.schema.TableSchema
import borg.trikeshed.userspace.concurrency.Channel
import borg.trikeshed.userspace.concurrency.ChannelCapacity
import java.net.HttpURLConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream

/**
 * TableSource that fetches Binance OHLCV klines and serves them as MiniDuck rows.
 * The table name is the symbol, e.g. "BTCUSDT".
 *
 * Exposes columns: symbol, timespan, openTime, open, high, low, close, volume
 */
class BinanceTableSource(
   val symbol: String,
   val interval: String = "1h",
   val startDate: LocalDate,
   val endDate: LocalDate,
   val blockCapacity: Int = 500,
) : TableSource {

   val ohlcvSchema = TableSchema(
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
        )
    )

   val schemaManager: SchemaManager = object : SchemaManager {
        override suspend fun getTableSuspend(name: String): TableSchema? =
            if (name == symbol) ohlcvSchema else null

        override fun getTable(name: String): TableSchema? =
            if (name == symbol) ohlcvSchema else null

        override suspend fun createTableSuspend(schema: TableSchema) {}
        override suspend fun ensureColumnsSuspend(table: String, cols: List<String>): TableSchema = ohlcvSchema
        override fun ensureColumns(table: String, cols: List<String>): TableSchema = ohlcvSchema
    }

    // Lazily fetched blocks
   val blocks = mutableListOf<KlineBlock>()
    @Volatile var fetched = false

    init {
        require(symbol.isNotBlank()) { "symbol must not be blank" }
        require(!startDate.isAfter(endDate)) { "startDate must not be after endDate" }
    }

    override fun open(execCtx: ExecutionContext, tableName: String): Cursor {
        require(tableName == symbol) { "Unknown table: $tableName (expected $symbol)" }

        if (!fetched) {
            runBlocking {
                fetchAll { block ->
                    blocks.add(block)
                }
                fetched = true
            }
        }

        return BinanceCursor(blocks.toList())
    }

    fun getSchema(): TableSchema = ohlcvSchema
    fun getSchemaManager(): SchemaManager = schemaManager

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
        val conn = URI(urlStr).toURL().openConnection() as HttpURLConnection
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
}
