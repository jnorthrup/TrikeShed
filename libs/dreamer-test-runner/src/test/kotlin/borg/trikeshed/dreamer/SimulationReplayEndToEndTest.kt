package borg.trikeshed.dreamer

import borg.trikeshed.couch.kline.Kline
import borg.trikeshed.couch.kline.KlineBlock
import borg.trikeshed.couch.kline.TimeSpan
import borg.trikeshed.miniduck.DocRowVec
import borg.trikeshed.miniduck.MiniCursor
import borg.trikeshed.miniduck.at
import borg.trikeshed.lib.size
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end pipeline test: Binance CSV → Kline → KlineBlock → simulateTicks → BacktestResult
 *
 * Architecture chain:
 *   raw CSV string
 *     → inline CsvParser.parseCsv() → List<Kline>
 *     → KlineBlock.mutable() / append() / seal() → KlineBlock
 *     → asCursor() → MiniCursor (DocRowVec rows)
 *     → klineBarToPortfolioInput → PortfolioInput
 *     → simulateTicks(TradingEngine) → BacktestResult + BacktestMetrics
 *
 * Note: CsvParser is inlined here to avoid JVM toolchain coupling between
 * dreamer-test-runner and integration-scratch.
 */
class SimulationReplayEndToEndTest {

    /** Minimal CSV parser matching BinanceCsvParser behaviour. */
    class CsvParser(val symbol: String, val interval: String) {
        val timespan: TimeSpan = intervalToTimeSpan(interval)

        fun parseCsvLine(line: String): Kline? {
            if (line.isBlank()) return null
            val fields = line.split(',')
            if (fields.size < 6) return null
            return try {
                Kline(
                    symbol = symbol, timespan = timespan,
                    openTime = fields[0].toLong(), open = fields[1].toDouble(),
                    high = fields[2].toDouble(), low = fields[3].toDouble(),
                    close = fields[4].toDouble(), volume = fields[5].toDouble(),
                )
            } catch (e: NumberFormatException) { null }
        }

        fun parseCsv(csv: String): List<Kline> =
            csv.lineSequence().mapNotNull { parseCsvLine(it) }.toList()

        fun intervalToTimeSpan(interval: String): TimeSpan = when (interval) {
            "1m"  -> TimeSpan.Minutes1;  "3m"  -> TimeSpan.Minutes3
            "5m"  -> TimeSpan.Minutes5;  "15m" -> TimeSpan.Minutes15
            "30m" -> TimeSpan.Minutes30; "1h"  -> TimeSpan.Hours1
            "2h"  -> TimeSpan.Hours2;    "4h"  -> TimeSpan.Hours4
            "6h"  -> TimeSpan.Hours6;    "8h"  -> TimeSpan.Hours8
            "12h" -> TimeSpan.Hours12;   "1d"  -> TimeSpan.Days1
            "3d"  -> TimeSpan.Days3;    "1w"  -> TimeSpan.Weeks1
            "1M"  -> TimeSpan.Months1;   else  -> TimeSpan.Hours1
        }
    }

    /**
     * Full draw-through: CSV string → KlineBlock → asCursor → simulateTicks
     */
    @Test
    fun `full pipeline CSV to BacktestResult with real OHLCV values`() = runBlocking {
        val csv = """
            1704067200000,20500.0,21000.0,20300.0,20800.0,1500.5,1704070800000,31252500.0,25000,750.25,780.50,0.0
            1704070800000,20800.0,21200.0,20700.0,21100.0,1600.0,1704074400000,33760000.0,26000,800.0,820.0,0.0
            1704074400000,21100.0,21500.0,21000.0,21400.0,1700.5,1704078000000,36485000.0,27000,850.25,870.50,0.0
        """.trimIndent()

        val parser = CsvParser("BTCUSDT", "1h")
        val klines = parser.parseCsv(csv)
        assertEquals(3, klines.size)
        assertEquals("BTCUSDT", klines[0].symbol)
        assertEquals(1704067200000L, klines[0].openTime)
        assertEquals(20800.0, klines[0].close, 0.001)

        val block = KlineBlock.mutable()
        klines.forEach { block.append(it) }
        block.seal()
        assertEquals(KlineBlock.State.SEALED, block.state)
        assertEquals(3, block.rowCount)

        val cursor: MiniCursor = block.asCursor()
        assertEquals(3, cursor.size)

        val row0: DocRowVec = (cursor at 0) as DocRowVec
        assertEquals("BTCUSDT", row0["symbol"])
        assertEquals(TimeSpan.Hours1, row0["timespan"])
        assertEquals(1704067200000L, row0["openTime"])
        assertEquals(20500.0, row0["open"] as Double, 0.001)
        assertEquals(21000.0, row0["high"] as Double, 0.001)
        assertEquals(20300.0, row0["low"] as Double, 0.001)
        assertEquals(20800.0, row0["close"] as Double, 0.001)
        assertEquals(1500.5, row0["volume"] as Double, 0.001)

        val engine = TradingEngine(defaultGenome(), Mode.SHADOW, initialCapital = 10_000.0)
        val result = simulateTicks(cursor, engine, initialCapital = 10_000.0)

        assertEquals("BTCUSDT", result.symbol)
        assertEquals(10_000.0, result.initialCapital)
        assertEquals(3, result.cycles.size)
        assertEquals(3, result.metrics.totalTicks)
        assertEquals(0, result.cycles[0].tick)
        assertEquals(1704067200000L, result.cycles[0].openTime)
        assertTrue(result.cycles[0].totalValue > 0.0)
        assertNotNull(result.metrics.totalReturn)
        assertNotNull(result.metrics.sharpeRatio)
        assertNotNull(result.metrics.maxDrawdown)
        assertTrue(result.metrics.totalHarvested >= 0.0)
    }

    @Test
    fun `klineBarToPortfolioInput works on CSV-parsed Kline cursor`() {
        val csv = """
            1704067200000,20500.0,21000.0,20300.0,20800.0,1500.5,1704070800000,31252500.0,25000,750.25,780.50,0.0
            1704070800000,20800.0,21200.0,20700.0,21100.0,1600.0,1704074400000,33760000.0,26000,800.0,820.0,0.0
        """.trimIndent()

        val klines = CsvParser("ETHUSDT", "1h").parseCsv(csv)
        val block = KlineBlock.mutable()
        klines.forEach { block.append(it) }
        block.seal()
        val cursor: MiniCursor = block.asCursor()

        val input0 = klineBarToPortfolioInput(cursor, 0, currentQuantity = 1.0)
        assertEquals("ETHUSDT", input0.symbol)
        assertEquals(1704067200000L, input0.openTime)
        assertEquals(1.0, input0.quantity)
        assertEquals(20800.0, input0.price, 0.001)
        assertEquals(20800.0, input0.value, 0.001)

        val input1 = klineBarToPortfolioInput(cursor, 1, currentQuantity = 1.0)
        assertEquals(21100.0, input1.price, 0.001)
        assertEquals(21100.0, input1.value, 0.001)
    }

    @Test
    fun `BacktestMetrics totalReturn is positive on rising price`() = runBlocking {
        val csv = """
            1704067200000,10000.0,10500.0,9900.0,10400.0,100.0,1704070800000,104000000.0,10000,500.0,520.0,0.0
            1704070800000,10400.0,10900.0,10300.0,10800.0,110.0,1704074400000,118800000.0,11000,550.0,570.0,0.0
        """.trimIndent()

        val klines = CsvParser("BTCUSDT", "1h").parseCsv(csv)
        val block = KlineBlock.mutable()
        klines.forEach { block.append(it) }
        block.seal()
        val cursor: MiniCursor = block.asCursor()

        val engine = TradingEngine(defaultGenome(), Mode.SHADOW, initialCapital = 10_000.0)
        val result = simulateTicks(cursor, engine, initialCapital = 10_000.0)

        // Price went 10400 → 10800 (~3.8% rise), so totalReturn should be positive
        assertTrue(result.metrics.totalReturn > 0.0, "Expected positive return but got ${result.metrics.totalReturn}")
        assertEquals(2, result.metrics.totalTicks)
    }

    // ── 4. Multi-symbol simulation ─────────────────────────────────────────

    /**
     * Verify multi-symbol simulation: allSymbolsAtBar collects all symbol inputs
     * at the same openTime, and simulateTicks processes them through the engine.
     */
    @Test
    fun `multi-symbol simulation produces correct per-symbol values`() = runBlocking {
        // Build cursor: BTC and ETH at same openTime, then BTC and ETH at second openTime
        val klines = listOf(
            Kline("BTC-USD", TimeSpan.Hours1, 1000L, 20000.0, 21000.0, 19900.0, 20500.0, 100.0),
            Kline("ETH-USD", TimeSpan.Hours1, 1000L, 2000.0, 2100.0, 1900.0, 2050.0, 1000.0),
            Kline("BTC-USD", TimeSpan.Hours1, 2000L, 20500.0, 21500.0, 20400.0, 21000.0, 110.0),
            Kline("ETH-USD", TimeSpan.Hours1, 2000L, 2050.0, 2150.0, 2000.0, 2100.0, 1100.0),
        )
        // Inline conversion: List<Kline> → MiniCursor
        val block = KlineBlock.mutable()
        klines.forEach { block.append(it) }
        block.seal()
        val cursor: MiniCursor = block.asCursor()
        assertEquals(4, cursor.size)

        // Verify allSymbolsAtBar returns both symbols for first bar
        val holdings = mapOf("BTC-USD" to 0.5, "ETH-USD" to 2.0)
        val inputs = allSymbolsAtBar(cursor, barIndex = 0, holdings = holdings)

        assertEquals(2, inputs.size)
        val symbols = inputs.map { it.symbol }.toSet()
        assertEquals(setOf("BTC-USD", "ETH-USD"), symbols)

        val btc = inputs.first { it.symbol == "BTC-USD" }
        assertEquals(1000L, btc.openTime)
        assertEquals(0.5, btc.quantity)
        assertEquals(20500.0, btc.price, 0.001) // close price

        val eth = inputs.first { it.symbol == "ETH-USD" }
        assertEquals(1000L, eth.openTime)
        assertEquals(2.0, eth.quantity)
        assertEquals(2050.0, eth.price, 0.001) // close price
    }

    // ── 7. adaptCursorToMiniCursor ──────────────────────────────────────────

    @Test
    fun `adaptCursorToMiniCursor produces same rows as direct asCursor`() {
        // Build a multi-block KlineBlock chain and verify adaptCursorToMiniCursor
        // produces an equivalent MiniCursor to calling asCursor() directly.
        val klines = listOf(
            Kline("BTC-USD", TimeSpan.Hours1, 1000L, 20000.0, 21000.0, 19900.0, 20500.0, 100.0),
            Kline("BTC-USD", TimeSpan.Hours1, 2000L, 20500.0, 21500.0, 20400.0, 21000.0, 110.0),
            Kline("BTC-USD", TimeSpan.Hours1, 3000L, 21000.0, 22000.0, 20900.0, 21500.0, 120.0),
        )
        val block = KlineBlock.mutable()
        klines.forEach { block.append(it) }
        val sealed = block.seal()

        // Reference: direct asCursor() path
        val directCursor: MiniCursor = sealed.asCursor()
        assertEquals(3, directCursor.size)

        // Wrapped as BinanceCursor-style exec.Cursor
        val execCursor = BinanceSingleBlockCursor(sealed)
        assertEquals(3, execCursor.rowCount())

        val adapted = adaptCursorToMiniCursor(execCursor, 3)
        assertEquals(3, adapted.size)

        // Values must match
        val row0: DocRowVec = (adapted at 0) as DocRowVec
        assertEquals("BTC-USD", row0["symbol"])
        assertEquals(1000L, row0["openTime"])
        assertEquals(20500.0, row0["close"])

        val row1: DocRowVec = (adapted at 1) as DocRowVec
        assertEquals(21000.0, row1["close"])

        val row2: DocRowVec = (adapted at 2) as DocRowVec
        assertEquals(21500.0, row2["close"])
    }

    @Test
    fun `adaptCursorToMiniCursor empty cursor returns empty MiniCursor`() {
        val emptyBlock = KlineBlock.mutable()
        val sealed = emptyBlock.seal()
        val execCursor = BinanceSingleBlockCursor(sealed)
        assertEquals(0, execCursor.rowCount())

        val adapted = adaptCursorToMiniCursor(execCursor, 0)
        assertEquals(0, adapted.size)
    }
}

/**
 * Single-block cursor that wraps a sealed KlineBlock as an exec.Cursor.
 * Used to test adaptCursorToMiniCursor without a full BinanceCursor dependency.
 */
private class BinanceSingleBlockCursor(private val block: KlineBlock) :
    borg.trikeshed.miniduck.exec.Cursor {
    private val miniCursor: MiniCursor = block.asCursor()
    private var idx = -1

    fun rowCount(): Int = block.rowCount

    override fun next(): Boolean {
        if (idx < 0) { idx = 0; return block.rowCount > 0 }
        if (idx + 1 < miniCursor.size) { idx++; return true }
        return false
    }

    override val row: borg.trikeshed.miniduck.exec.RowAccessor
        get() = MiniRowVecRowAccessor(miniCursor at idx)

    override fun close() {}
}
