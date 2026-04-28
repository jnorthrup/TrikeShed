package borg.trikeshed.integration

import borg.trikeshed.couch.kline.Kline
import borg.trikeshed.couch.kline.TimeSpan
import borg.trikeshed.miniduck.DocRowVec
import borg.trikeshed.miniduck.MiniCursor
import borg.trikeshed.miniduck.at
import borg.trikeshed.couch.kline.KlineBlock
import borg.trikeshed.couch.kline.KlineCollector
import borg.trikeshed.userspace.concurrency.Channel
import borg.trikeshed.userspace.concurrency.ChannelCapacity
import borg.trikeshed.lib.size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TDD tests for Binance CSV → Kline → KlineBlock → MiniCursor draw-through.
 *
 * Tests the compression handling and conversion to TrikeShed RowVec/DocRowVec.
 */
class BinanceKlineTddTest {

    // ---------------------------------------------------------------------------
    // 1. CSV line parsing
    // ---------------------------------------------------------------------------

    @Test
    fun `parseCsvLine parses standard Binance kline format`() {
        val parser = BinanceCsvParser("BTCUSDT", "1h")
        val line =
            "1704067200000,20500.0,21000.0,20300.0,20800.0,1500.5,1704070800000,31252500.0,25000,750.25,780.50,0.0"
        val kline = parser.parseCsvLine(line)

        assertNotNull(kline)
        assertEquals("BTCUSDT", kline.symbol)
        assertEquals(TimeSpan.Hours1, kline.timespan)
        assertEquals(1704067200000L, kline.openTime)
        assertEquals(20500.0, kline.open, 0.001)
        assertEquals(21000.0, kline.high, 0.001)
        assertEquals(20300.0, kline.low, 0.001)
        assertEquals(20800.0, kline.close, 0.001)
        assertEquals(1500.5, kline.volume, 0.001)
    }

    @Test
    fun `parseCsvLine returns null for blank lines`() {
        val parser = BinanceCsvParser("BTCUSDT", "1h")
        assertEquals(null, parser.parseCsvLine(""))
        assertEquals(null, parser.parseCsvLine("   "))
    }

    @Test
    fun `parseCsvLine returns null for lines with too few fields`() {
        val parser = BinanceCsvParser("BTCUSDT", "1h")
        assertEquals(null, parser.parseCsvLine("1704067200000,20500.0"))
        assertEquals(null, parser.parseCsvLine("1704067200000,20500.0,21000.0,20300.0,20800.0"))
    }

    @Test
    fun `parseCsvLine returns null for malformed numbers`() {
        val parser = BinanceCsvParser("BTCUSDT", "1h")
        val line = "1704067200000,not_a_double,21000.0,20300.0,20800.0,1500.5"
        assertEquals(null, parser.parseCsvLine(line))
    }

    @Test
    fun `parseCsv maps interval string to TimeSpan`() {
        val cases = listOf(
            "1m" to TimeSpan.Minutes1,
            "5m" to TimeSpan.Minutes5,
            "15m" to TimeSpan.Minutes15,
            "1h" to TimeSpan.Hours1,
            "4h" to TimeSpan.Hours4,
            "1d" to TimeSpan.Days1,
            "1w" to TimeSpan.Weeks1,
        )
        for ((interval, expected) in cases) {
            val parser = BinanceCsvParser("BTCUSDT", interval)
            val kline =
                parser.parseCsvLine("1704067200000,20500.0,21000.0,20300.0,20800.0,1500.5,1704070800000,31252500.0,25000,750.25,780.50,0.0")
            assertEquals(expected, kline?.timespan)
        }
    }

    // ---------------------------------------------------------------------------
    // 2. Multi-line CSV parsing
    // ---------------------------------------------------------------------------

    @Test
    fun `parseCsv parses multiple lines into Kline list`() {
        val csv = """
         |1704067200000,20500.0,21000.0,20300.0,20800.0,1500.5,1704070800000,31252500.0,25000,750.25,780.50,0.0
         |1704070800000,20800.0,21200.0,20700.0,21100.0,1600.0,1704074400000,33760000.0,26000,800.0,820.0,0.0
         |1704074400000,21100.0,21500.0,21000.0,21400.0,1700.5,1704078000000,36485000.0,27000,850.25,870.50,0.0
        """.trimMargin()

        val parser = BinanceCsvParser("ETHUSDT", "1h")
        val klines = parser.parseCsv(csv)

        assertEquals(3, klines.size)
        assertEquals("ETHUSDT", klines[0].symbol)
        assertEquals(1704067200000L, klines[0].openTime)
        assertEquals(20800.0, klines[1].open, 0.001)
        assertEquals(21100.0, klines[2].open, 0.001)
    }

    @Test
    fun `parseCsv skips blank lines`() {
        val csv = """
            |1704067200000,20500.0,21000.0,20300.0,20800.0,1500.5,1704070800000,31252500.0,25000,750.25,780.50,0.0
            |
            |1704070800000,20800.0,21200.0,20700.0,21100.0,1600.0,1704074400000,33760000.0,26000,800.0,820.0,0.0
            |
        """.trimMargin()

        val parser = BinanceCsvParser("BTCUSDT", "1h")
        val klines = parser.parseCsv(csv)
        assertEquals(2, klines.size)
    }

    @Test
    fun `parseCsv returns empty list for empty input`() {
        val parser = BinanceCsvParser("BTCUSDT", "1h")
        assertEquals(0, parser.parseCsv("").size)
        assertEquals(0, parser.parseCsv("   \n   \n").size)
    }

    // ---------------------------------------------------------------------------
    // 3. ZIP compression / decompression
    // ---------------------------------------------------------------------------

    @Test
    fun `readZipEntry extracts CSV from ZIP and parses it`() {
        // Build a ZIP containing one CSV entry
        val csv =
            "1704067200000,20500.0,21000.0,20300.0,20800.0,1500.5,1704070800000,31252500.0,25000,750.25,780.50,0.0"
        val zipBytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("BTCUSDT-1h-2024-01-01.csv"))
                zos.write(csv.toByteArray())
                zos.closeEntry()
            }
            baos.toByteArray()
        }

        val zipStream = ByteArrayInputStream(zipBytes)
        val extracted = ZipUtils.readZipEntry(zipStream, "BTCUSDT-1h-2024-01-01.csv")

        assertEquals(csv, extracted)
    }

    @Test
    fun `readZipEntry returns null when entry not found`() {
        val zipBytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("other-file.csv"))
                zos.write("hello".toByteArray())
                zos.closeEntry()
            }
            baos.toByteArray()
        }

        val zipStream = ByteArrayInputStream(zipBytes)
        assertEquals(null, ZipUtils.readZipEntry(zipStream, "missing.csv"))
    }

    @Test
    fun `readZipEntry returns null for empty ZIP`() {
        val emptyZip = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos -> }
            baos.toByteArray()
        }
        val zipStream = ByteArrayInputStream(emptyZip)
        assertEquals(null, ZipUtils.readZipEntry(zipStream, "anything.csv"))
    }

    // ---------------------------------------------------------------------------
    // 4. KlineBlock → MiniCursor → DocRowVec
    // ---------------------------------------------------------------------------

    @Test
    fun `KlineBlock asCursor returns MiniCursor with correct row count`() {
        val block = KlineBlock.mutable()
        block.append(Kline("BTCUSDT", TimeSpan.Hours1, 1704067200000L, 20500.0, 21000.0, 20300.0, 20800.0, 1500.5))
        block.append(Kline("BTCUSDT", TimeSpan.Hours1, 1704070800000L, 20800.0, 21200.0, 20700.0, 21100.0, 1600.0))
        block.seal()

        val cursor: MiniCursor = block.asCursor()

        assertEquals(2, cursor.size)
    }

    @Test
    fun `KlineBlock asCursor returns DocRowVec with correct schema keys`() {
        val block = KlineBlock.mutable()
        block.append(Kline("ETHUSDT", TimeSpan.Days1, 1704067200000L, 3000.0, 3100.0, 2950.0, 3050.0, 50000.0))
        block.seal()

        val cursor: MiniCursor = block.asCursor()
        val row: DocRowVec = (cursor at 0) as DocRowVec

        assertEquals(
            listOf("symbol", "timespan", "openTime", "open", "high", "low", "close", "volume"),
            row.keys,
        )
        assertEquals("ETHUSDT", row.cells[0])
        assertEquals(TimeSpan.Days1, row.cells[1])
        assertEquals(3000.0, row.cells[3] as Double, 0.001)
    }

    @Test
    fun `KlineBlock asCursor throws on mutable block`() {
        val block = KlineBlock.mutable()
        block.append(Kline("BTCUSDT", TimeSpan.Hours1, 1704067200000L, 20500.0, 21000.0, 20300.0, 20800.0, 1500.5))

        var threw = false
        try {
            block.asCursor()
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }

    // ---------------------------------------------------------------------------
    // 5. KlineCollector drains Channel into sealed KlineBlocks
    // ---------------------------------------------------------------------------

    @Test
    fun `KlineCollector seals block at capacity and delivers partial on close`() = runBlocking {
        val channel: Channel<Kline> = Channel(ChannelCapacity.Unbounded)
        val collector = KlineCollector(blockCapacity = 3)
        val received = mutableListOf<KlineBlock>()

        // Launch a coroutine to send klines and close
        CoroutineScope(Dispatchers.Default).launch {
            channel.send(Kline("BTCUSDT", TimeSpan.Hours1, 1000L, 1.0, 2.0, 0.5, 1.5, 100.0))
            channel.send(Kline("BTCUSDT", TimeSpan.Hours1, 2000L, 1.5, 2.5, 1.0, 2.0, 110.0))
            channel.send(Kline("BTCUSDT", TimeSpan.Hours1, 3000L, 2.0, 3.0, 1.5, 2.5, 120.0))
            channel.send(Kline("BTCUSDT", TimeSpan.Hours1, 4000L, 2.5, 3.5, 2.0, 3.0, 130.0))
            channel.close()
        }

        collector.collect(channel) { block ->
            received.add(block)
        }

        assertEquals(2, received.size)
        assertEquals(3, received[0].rowCount)
        assertEquals(1, received[1].rowCount)
        assertEquals(KlineBlock.State.SEALED, received[0].state)
        assertEquals(KlineBlock.State.SEALED, received[1].state)
    }

    // ---------------------------------------------------------------------------
    // 6. BinanceCursor end-to-end: KlineBlocks → Cursor
    // ---------------------------------------------------------------------------

    @Test
    fun `BinanceCursor implements Cursor and returns rows from KlineBlocks`() = runBlocking {
        val block = KlineBlock.mutable()
        block.append(Kline("BTCUSDT", TimeSpan.Hours1, 1704067200000L, 20500.0, 21000.0, 20300.0, 20800.0, 1500.5))
        block.append(Kline("BTCUSDT", TimeSpan.Hours1, 1704070800000L, 20800.0, 21200.0, 20700.0, 21100.0, 1600.0))
        block.seal()

        val cursor = BinanceCursor(listOf(block))
        val rows = mutableListOf<List<Any?>>()

        while (cursor.next()) {
            val row = cursor.row
            rows.add(
                listOf(
                    row.get("symbol"),
                    row.get("open"),
                    row.get("high"),
                    row.get("low"),
                    row.get("close"),
                ),
            )
        }
        cursor.close()

        assertEquals(2, rows.size)
        assertEquals("BTCUSDT", rows[0][0])
        assertEquals(20500.0, rows[0][1] as Double, 0.001)
        assertEquals(21000.0, rows[0][2] as Double, 0.001)
        assertEquals(20300.0, rows[0][3] as Double, 0.001)
        assertEquals(20800.0, rows[0][4] as Double, 0.001)
        assertEquals("BTCUSDT", rows[1][0])
        assertEquals(20800.0, rows[1][1] as Double, 0.001)
    }

    @Test
    fun `BinanceCursor returns false on next when empty`() = runBlocking {
        val cursor = BinanceCursor(emptyList())
        assertEquals(false, cursor.next())
        cursor.close()
    }

    // ---------------------------------------------------------------------------
    // 7. Integration: CSV bytes → Kline → KlineBlock → Cursor → values
    // ---------------------------------------------------------------------------

    @Test
    fun `full pipeline CSV bytes to KlineBlock to Cursor with real values`() {
        val csv = """
            1704067200000,20500.0,21000.0,20300.0,20800.0,1500.5,1704070800000,31252500.0,25000,750.25,780.50,0.0
            1704070800000,20800.0,21200.0,20700.0,21100.0,1600.0,1704074400000,33760000.0,26000,800.0,820.0,0.0
        """.trimIndent()

        // Parse CSV → List<Kline>
        val parser = BinanceCsvParser("BTCUSDT", "1h")
        val klines = parser.parseCsv(csv)
        assertEquals(2, klines.size)

        // Collect into KlineBlock
        val block = KlineBlock.mutable()
        klines.forEach { block.append(it) }
        block.seal()

        // Present as MiniCursor / DocRowVec
        val cursor: MiniCursor = block.asCursor()
        assertEquals(2, cursor.size)

        val row0: DocRowVec = (cursor at 0) as DocRowVec
        assertEquals("BTCUSDT", row0.cells[0])
        assertEquals(20500.0, row0.cells[3] as Double, 0.001)
        assertEquals(21000.0, row0.cells[4] as Double, 0.001)
        assertEquals(20300.0, row0.cells[5] as Double, 0.001)
        assertEquals(20800.0, row0.cells[6] as Double, 0.001)
        assertEquals(1500.5, row0.cells[7] as Double, 0.001)
        assertEquals(TimeSpan.Hours1, row0.cells[1])

        val row1: DocRowVec = (cursor at 1) as DocRowVec
        assertEquals(20800.0, row1.cells[3] as Double, 0.001)
    }
}
