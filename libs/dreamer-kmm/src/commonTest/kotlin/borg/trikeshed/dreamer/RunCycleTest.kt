package borg.trikeshed.dreamer

import borg.trikeshed.collections.s_
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.lib.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the Cursor → PortfolioInput → runCycle → CycleResult back-test pipeline.
 *
 * These pin the algebra and catch regressions as the pipeline grows.
 *
 * Architecture chain under test:
 *   KlineBlock → asCursor() → MiniCursor (DocRowVec rows)
 *     → klineBarToPortfolioInput → PortfolioInput
 *     → simulateTicks(TradingEngine) → BacktestResult
 *     → BacktestMetrics (total return, Sharpe, max drawdown)
 */
class RunCycleTest {

    /** Build a sealed KlineBlock from a list of klines and return its MiniCursor. */
    private fun klinesToCursor(klines: List<Kline>): Cursor {
        val block = KlineBlock.mutable()
        klines.forEach { block.append(it) }
        return block.seal().asCursor()
    }

    /**
     * Helper to build a [Series] of [Double] from a [List].
     */
    private fun doubleSeriesOf(values: List<Double>): Series<Double> = values.toSeries()

    // ── 1. klineBarToPortfolioInput ─────────────────────────────────────

    @Test
    fun `klineBarToPortfolioInput extracts correct fields from cursor row`() {
        val klines = listOf(
            Kline("BTC-USD", TimeSpan.Hours1, 1704067200000L, 20500.0, 21000.0, 20300.0, 20800.0, 1500.5),
            Kline("BTC-USD", TimeSpan.Hours1, 1704070800000L, 20800.0, 21200.0, 20700.0, 21100.0, 1600.0),
        )
        val cursor = klinesToCursor(klines)

        val input = klineBarToPortfolioInput(cursor, 0, currentQuantity = 0.5)

        assertEquals("BTC-USD", input.symbol)
        assertEquals(1704067200000L, input.openTime)
        assertEquals(0.5, input.quantity)
        assertEquals(20800.0, input.price) // close price
        assertEquals(10400.0, input.value, 0.001)
    }

    @Test
    fun `portfolioInputToRows round-trips correctly`() {
        val input = PortfolioInput(
            symbol = "BTC-USD",
            openTime = 1704067200000L,
            quantity = 0.5,
            price = 20800.0,
            value = 10400.0,
        )
        val rows = portfolioInputToRows(input)

        assertEquals(1, rows.size)
        assertEquals("BTC-USD", rows[0].Symbol)
        assertEquals(0.5, rows[0].Quantity)
        assertEquals(20800.0, rows[0].Price)
        assertEquals(10400.0, rows[0].Value)
    }

    // ── 2. closesFromCursor ────────────────────────────────────────────

    @Test
    fun `closesFromCursor projects close prices as Series`() {
        val klines = listOf(
            Kline("BTC-USD", TimeSpan.Hours1, 1704067200000L, 20500.0, 21000.0, 20300.0, 20800.0, 1500.5),
            Kline("BTC-USD", TimeSpan.Hours1, 1704070800000L, 20800.0, 21200.0, 20700.0, 21100.0, 1600.0),
            Kline("BTC-USD", TimeSpan.Hours1, 1704074400000L, 21100.0, 21500.0, 21000.0, 21400.0, 1700.0),
        )
        val cursor = klinesToCursor(klines)

        val closes = closesFromCursor(cursor)

        assertEquals(3, closes.size)
        assertEquals(20800.0, closes[0])
        assertEquals(21100.0, closes[1])
        assertEquals(21400.0, closes[2])
    }

    // ── 3. computeBacktestMetrics ────────────────────────────────────────

    @Test
    fun `computeBacktestMetrics returns zero for empty cycles`() {
        val metrics = computeBacktestMetrics(emptySeries(), 10_000.0, doubleSeriesOf(emptyList()))

        assertEquals(0, metrics.totalTicks)
        assertEquals(0.0, metrics.totalReturn)
        assertEquals(0.0, metrics.sharpeRatio)
        assertEquals(0.0, metrics.maxDrawdown)
        assertEquals(0.0, metrics.totalHarvested)
    }

    @Test
    fun `computeBacktestMetrics totalReturn reflects final vs initial value`() {
        // 10k → 11k → 2 ticks
        val cycles = s_[
            CycleResult(0, 0L, 0.0, 10_000.0, 10_000.0, false, 0.0, emptyList(), false, emptyMap()),
            CycleResult(1, 0L, 0.0, 11_000.0, 11_000.0, false, 0.0, emptyList(), false, emptyMap()),
        ]
        // Price series: 10_000 then 11_000
        val closes = doubleSeriesOf(listOf(10_000.0, 11_000.0))
        val metrics = computeBacktestMetrics(cycles, 10_000.0, closes)

        assertEquals(0.10, metrics.totalReturn, 0.001) // +10%
    }

    @Test
    fun `computeBacktestMetrics maxDrawdown correct for equity curve`() {
        // Equity: 100 → 110 → 95 → 105
        // Peak: 100, 110, then drawdown to 95 (maxDD = 15/110 ≈ 13.6%)
        val cycles = s_[
            CycleResult(0, 0L, 0.0, 100.0, 100.0, false, 0.0, emptyList(), false, emptyMap()),
            CycleResult(1, 0L, 0.0, 110.0, 110.0, false, 0.0, emptyList(), false, emptyMap()),
            CycleResult(2, 0L, 0.0, 95.0,  95.0,  false, 0.0, emptyList(), false, emptyMap()),
            CycleResult(3, 0L, 0.0, 105.0, 105.0, false, 0.0, emptyList(), false, emptyMap()),
        ]
        val closes = doubleSeriesOf(listOf(100.0, 110.0, 95.0, 105.0))
        val metrics = computeBacktestMetrics(cycles, 100.0, closes)

        assertTrue(metrics.maxDrawdown > 0.13, "maxDrawdown=${metrics.maxDrawdown} should be ~13.6%")
        assertTrue(metrics.maxDrawdown < 0.14, "maxDrawdown=${metrics.maxDrawdown} should be ~13.6%")
    }

    @Test
    fun `computeBacktestMetrics maxDrawdownTicks counts peak to trough duration`() {
        val cycles = s_[
            CycleResult(0, 0L, 0.0, 100.0, 100.0, false, 0.0, emptyList(), false, emptyMap()),
            CycleResult(1, 1L, 0.0, 120.0, 120.0, false, 0.0, emptyList(), false, emptyMap()),
            CycleResult(2, 2L, 0.0, 115.0, 115.0, false, 0.0, emptyList(), false, emptyMap()),
            CycleResult(3, 3L, 0.0, 110.0, 110.0, false, 0.0, emptyList(), false, emptyMap()),
            CycleResult(4, 4L, 0.0, 108.0, 108.0, false, 0.0, emptyList(), false, emptyMap()),
        ]

        val metrics = computeBacktestMetrics(cycles, 100.0, doubleSeriesOf(listOf(100.0, 120.0, 115.0, 110.0, 108.0)))

        assertEquals(3, metrics.maxDrawdownTicks)
    }

    // ── 4. simulateTicks end-to-end ─────────────────────────────────────

    @Test
    fun `simulateTicks returns BacktestResult with correct symbol and initial capital`() = runTest {
        val klines = listOf(
            Kline("BTC-USD", TimeSpan.Hours1, 1704067200000L, 20500.0, 21000.0, 20300.0, 20800.0, 1500.5),
            Kline("BTC-USD", TimeSpan.Hours1, 1704070800000L, 20800.0, 21200.0, 20700.0, 21100.0, 1600.0),
        )
        val cursor = klinesToCursor(klines)

        val engine = TradingEngine(defaultGenome(), Mode.SHADOW, initialCapital = 10_000.0)
        val result = simulateTicks(cursor, engine, initialCapital = 10_000.0)

        assertEquals("BTC-USD", result.symbol)
        assertEquals(10_000.0, result.initialCapital)
        assertEquals(2, result.cycles.size)
        assertNotNull(result.metrics)
    }

    @Test
    fun `simulateTicks captures correct cycle count`() = runTest {
        val klines = listOf(
            Kline("BTC-USD", TimeSpan.Hours1, 1704067200000L, 20500.0, 21000.0, 20300.0, 20800.0, 1500.5),
            Kline("BTC-USD", TimeSpan.Hours1, 1704070800000L, 20800.0, 21200.0, 20700.0, 21100.0, 1600.0),
            Kline("BTC-USD", TimeSpan.Hours1, 1704074400000L, 21100.0, 21500.0, 21000.0, 21400.0, 1700.0),
        )
        val cursor = klinesToCursor(klines)

        val engine = TradingEngine(defaultGenome(), Mode.SHADOW, initialCapital = 10_000.0)
        val result = simulateTicks(cursor, engine, initialCapital = 10_000.0)

        assertEquals(3, result.metrics.totalTicks)
        assertEquals(3, result.cycles.size)
        assertTrue(result.cycles.view.all { it.tick >= 0 })
    }

    @Test
    fun `simulateTicks zero-bar cursor returns empty BacktestResult`() = runTest {
        val emptyKlines: List<Kline> = emptyList()
        val cursor = klinesToCursor(emptyKlines)

        val engine = TradingEngine(defaultGenome(), Mode.SHADOW, initialCapital = 10_000.0)
        val result = simulateTicks(cursor, engine, initialCapital = 10_000.0)

        assertEquals(0, result.cycles.size)
        assertEquals(0, result.metrics.totalTicks)
    }

    @Test
    fun `simulateTicks onCycle callback fires each tick`() = runTest {
        val klines = listOf(
            Kline("BTC-USD", TimeSpan.Hours1, 1704067200000L, 20500.0, 21000.0, 20300.0, 20800.0, 1500.5),
            Kline("BTC-USD", TimeSpan.Hours1, 1704070800000L, 20800.0, 21200.0, 20700.0, 21100.0, 1600.0),
        )
        val cursor = klinesToCursor(klines)

        val engine = TradingEngine(defaultGenome(), Mode.SHADOW, initialCapital = 10_000.0)
        val seenTicks = mutableListOf<Int>()
        simulateTicks(cursor, engine, initialCapital = 10_000.0) { cycle ->
            seenTicks.add(cycle.tick)
        }

        assertEquals(2, seenTicks.size)
        assertEquals(listOf(0, 1), seenTicks)
    }

    // ── 5. BacktestMetrics sanity ──────────────────────────────────────

    @Test
    fun `BacktestMetrics sharpeRatio is zero for flat equity`() {
        val cycles: Series<CycleResult> = 10 j { i ->
            CycleResult(i, i.toLong() * 3600_000, 0.0, 10_000.0, 10_000.0, false, 0.0, emptyList(), false, emptyMap())
        }
        val closes = doubleSeriesOf(List(10) { 10_000.0 })
        val metrics = computeBacktestMetrics(cycles, 10_000.0, closes)

        assertEquals(0.0, metrics.sharpeRatio) // zero variance → zero Sharpe
    }

    @Test
    fun `BacktestMetrics sortinoRatio uses downside volatility only`() {
        val cycles = s_[
            CycleResult(0, 0L, 0.0, 100.0, 100.0, false, 0.0, emptyList(), false, emptyMap()),
            CycleResult(1, 1L, 0.0, 110.0, 110.0, false, 0.0, emptyList(), false, emptyMap()),
            CycleResult(2, 2L, 0.0, 104.5, 104.5, false, 0.0, emptyList(), false, emptyMap()),
            CycleResult(3, 3L, 0.0, 114.95, 114.95, false, 0.0, emptyList(), false, emptyMap()),
        ]

        val metrics = computeBacktestMetrics(cycles, 100.0, doubleSeriesOf(listOf(100.0, 110.0, 104.5, 114.95)))

        assertTrue(metrics.sortinoRatio > metrics.sharpeRatio)
        assertTrue(metrics.sortinoRatio > 20.0, "sortinoRatio=${metrics.sortinoRatio} should reward limited downside")
    }

    @Test
    fun `BacktestMetrics totalTrades counts harvest ticks`() {
        val cycles = s_[
            CycleResult(0, 0L, 0.0, 10_000.0, 10_000.0, false, 0.0, emptyList(), false, emptyMap()),
            CycleResult(1, 0L, 0.0, 10_000.0, 10_000.0, true, 100.0, listOf("BTC-USD"), false, emptyMap()),
            CycleResult(2, 0L, 0.0, 10_000.0, 10_000.0, false, 0.0, emptyList(), false, emptyMap()),
            CycleResult(3, 0L, 0.0, 10_000.0, 10_000.0, true, 80.0, listOf("BTC-USD"), false, emptyMap()),
        ]
        val closes = doubleSeriesOf(List(4) { 10_000.0 })
        val metrics = computeBacktestMetrics(cycles, 10_000.0, closes)

        assertEquals(2, metrics.totalTrades)
        assertEquals(180.0, metrics.totalHarvested, 0.001)
    }

    @Test
    fun `BacktestReport summarizes result metrics and final equity`() {
        val cycles = s_[
            CycleResult(0, 1000L, 2_500.0, 7_500.0, 10_000.0, false, 0.0, emptyList(), false, emptyMap()),
            CycleResult(1, 2000L, 3_000.0, 8_000.0, 11_000.0, true, 120.0, listOf("BTC-USD"), false, emptyMap()),
        ]
        val metrics = BacktestMetrics(
            totalTicks = 2,
            totalReturn = 0.10,
            sharpeRatio = 1.25,
            sortinoRatio = 1.75,
            maxDrawdown = 0.05,
            maxDrawdownTicks = 1,
            totalHarvested = 120.0,
            totalTrades = 1,
            avgHarvestPerTick = 60.0,
        )
        val result = BacktestResult(
            symbol = "BTC-USD",
            initialCapital = 10_000.0,
            cycles = cycles,
            metrics = metrics,
        )

        val report = result.toBacktestReport()

        assertEquals("BTC-USD", report.symbol)
        assertEquals(10_000.0, report.initialCapital, 0.001)
        assertEquals(11_000.0, report.finalEquity, 0.001)
        assertEquals(0.10, report.totalReturn, 0.001)
        assertEquals(1.25, report.sharpeRatio, 0.001)
        assertEquals(1.75, report.sortinoRatio, 0.001)
        assertEquals(0.05, report.maxDrawdown, 0.001)
        assertEquals(1, report.maxDrawdownTicks)
        assertEquals(1, report.totalTrades)
        assertEquals(120.0, report.totalHarvested, 0.001)
        assertEquals(2, report.totalTicks)
    }

    // ── 6. multiSymbolKlineToPortfolioInput ────────────────────────────

    @Test
    fun `multiSymbolKlineToPortfolioInput collects all symbols at same openTime`() {
        // Build cursor with BTC and ETH at same openTime
        val klines = listOf(
            Kline("BTC-USD", TimeSpan.Hours1, 1000L, 20000.0, 21000.0, 19900.0, 20500.0, 100.0),
            Kline("ETH-USD", TimeSpan.Hours1, 1000L, 2000.0, 2100.0, 1900.0, 2050.0, 1000.0),
            // BTC at a different time
            Kline("BTC-USD", TimeSpan.Hours1, 2000L, 21000.0, 22000.0, 20900.0, 21500.0, 110.0),
        )
        val cursor = klinesToCursor(klines)
        val holdings = mapOf("BTC-USD" to 0.5, "ETH-USD" to 2.0)

        val inputs = multiSymbolKlineToPortfolioInput(cursor, barIndex = 0, holdings = holdings)

        // Should collect BTC + ETH at openTime=1000
        assertEquals(2, inputs.size)
        val symbols = inputs.map { it.symbol }.toSet()
        assertEquals(setOf("BTC-USD", "ETH-USD"), symbols)

        val btc = inputs.first { it.symbol == "BTC-USD" }
        assertEquals(1000L, btc.openTime)
        assertEquals(0.5, btc.quantity)
        assertEquals(20500.0, btc.price, 0.001)

        val eth = inputs.first { it.symbol == "ETH-USD" }
        assertEquals(1000L, eth.openTime)
        assertEquals(2.0, eth.quantity)
        assertEquals(2050.0, eth.price, 0.001)
        assertEquals(4100.0, eth.value, 0.001)
    }

    // ── 7. simulateMultiSymbolTicks ──────────────────────────────────

    @Test
    fun `simulateMultiSymbolTicks groups bars by openTime and runs engine per tick`() = runTest {
        // 3 timestamps × 2 symbols = 6 rows in the cursor
        val klines = listOf(
            Kline("BTCUSDT", TimeSpan.Minutes1, 1000L, 100.0, 102.0, 99.0, 101.0, 50.0),
            Kline("ETHUSDT", TimeSpan.Minutes1, 1000L, 10.0, 10.5, 9.8, 10.2, 500.0),
            Kline("BTCUSDT", TimeSpan.Minutes1, 2000L, 101.0, 103.0, 100.0, 102.0, 60.0),
            Kline("ETHUSDT", TimeSpan.Minutes1, 2000L, 10.2, 10.8, 10.0, 10.5, 600.0),
            Kline("BTCUSDT", TimeSpan.Minutes1, 3000L, 102.0, 104.0, 101.0, 103.0, 70.0),
            Kline("ETHUSDT", TimeSpan.Minutes1, 3000L, 10.5, 11.0, 10.2, 10.8, 700.0),
        )
        val cursor = klinesToCursor(klines)

        val engine = TradingEngine(defaultGenome(), Mode.SHADOW, initialCapital = 10_000.0)
        val result = simulateMultiSymbolTicks(cursor, engine, initialCapital = 10_000.0)

        assertEquals("MULTI", result.symbol)
        assertEquals(10_000.0, result.initialCapital, 0.001)
        // 3 unique openTimes → 3 ticks
        assertEquals(3, result.cycles.size)
        assertEquals(3, result.metrics.totalTicks)

        // Each cycle should reflect the first openTime
        assertEquals(1000L, result.cycles[0].openTime)
        assertEquals(2000L, result.cycles[1].openTime)
        assertEquals(3000L, result.cycles[2].openTime)
    }

    @Test
    fun `simulateMultiSymbolTicks empty cursor returns empty result`() = runTest {
        val cursor = klinesToCursor(emptyList())
        val engine = TradingEngine(defaultGenome(), Mode.SHADOW, initialCapital = 10_000.0)
        val result = simulateMultiSymbolTicks(cursor, engine, initialCapital = 10_000.0)

        assertEquals("MULTI", result.symbol)
        assertEquals(0, result.cycles.size)
        assertEquals(0, result.metrics.totalTicks)
    }

    @Test
    fun `simulateMultiSymbolTicks onCycle callback fires per unique openTime`() = runTest {
        val klines = listOf(
            Kline("BTCUSDT", TimeSpan.Minutes1, 1000L, 100.0, 101.0, 99.0, 100.5, 50.0),
            Kline("ETHUSDT", TimeSpan.Minutes1, 1000L, 10.0, 10.5, 9.8, 10.2, 500.0),
            Kline("BTCUSDT", TimeSpan.Minutes1, 2000L, 100.5, 102.0, 100.0, 101.5, 60.0),
            Kline("ETHUSDT", TimeSpan.Minutes1, 2000L, 10.2, 10.8, 10.0, 10.6, 600.0),
        )
        val cursor = klinesToCursor(klines)
        val engine = TradingEngine(defaultGenome(), Mode.SHADOW, initialCapital = 10_000.0)
        val seenTicks = mutableListOf<Int>()

        simulateMultiSymbolTicks(cursor, engine, initialCapital = 10_000.0) { cycle ->
            seenTicks.add(cycle.tick)
        }

        assertEquals(listOf(0, 1), seenTicks)
    }

    // ── 8. simulateMultiSymbolTicks cash accounting ─────────────────

    @Test
    fun `simulateMultiSymbolTicks cash accounts for all symbols not just first`() = runTest {
        // Two symbols at same timestamps, equal capital allocation.
        // BTC price=100, ETH price=10. initialCapital=10_000.
        // Per-symbol allocation = 5000. BTC qty=50, ETH qty=500.
        // holdingsValue = 50*100 + 500*10 = 5000 + 5000 = 10_000.
        // So cash should be 0 and totalValue should equal initialCapital on tick 0.
        val klines = listOf(
            Kline("BTCUSDT", TimeSpan.Minutes1, 1000L, 100.0, 101.0, 99.0, 100.0, 50.0),
            Kline("ETHUSDT", TimeSpan.Minutes1, 1000L, 10.0, 10.5, 9.8, 10.0, 500.0),
            Kline("BTCUSDT", TimeSpan.Minutes1, 2000L, 100.0, 102.0, 99.0, 101.0, 60.0),
            Kline("ETHUSDT", TimeSpan.Minutes1, 2000L, 10.0, 10.8, 9.8, 10.5, 600.0),
        )
        val cursor = klinesToCursor(klines)
        val engine = TradingEngine(defaultGenome(), Mode.SHADOW, initialCapital = 10_000.0)
        val result = simulateMultiSymbolTicks(cursor, engine, initialCapital = 10_000.0)

        // First cycle: totalValue should equal initialCapital since prices haven't moved
        val firstCycle = result.cycles[0]
        // With both symbols accounted, totalValue ≈ initialCapital ± harvest adjustment
        // The bug causes only BTC to be subtracted from cash, leaving cash = 5000 instead of ~0
        val expectedHoldingsValue = 50.0 * 100.0 + 500.0 * 10.0  // 10_000
        assertEquals(expectedHoldingsValue, firstCycle.holdingsValue, 100.0,
            "holdingsValue on tick 0 should equal total allocated holdings value for both symbols")
        assertEquals(10_000.0, firstCycle.totalValue, 100.0,
            "totalValue on tick 0 should be ~initialCapital when prices haven't moved")
    }

    // ── 9. Rebalance scheduling ──────────────────────────────────────

    @Test
    fun `simulateTicks schedules rebalance when value deviates from baseline`() = runTest {
        // Build a strongly rising series: baseline will be set on first bar,
        // subsequent bars should exceed FLAT_REBALANCE_TRIGGER_PERCENT deviation
        val klines = listOf(
            Kline("BTCUSDT", TimeSpan.Hours1, 1000L, 100.0, 101.0, 99.0, 100.0, 50.0),
            Kline("BTCUSDT", TimeSpan.Hours1, 2000L, 100.0, 101.0, 99.0, 100.0, 50.0),
            Kline("BTCUSDT", TimeSpan.Hours1, 3000L, 100.0, 200.0, 99.0, 180.0, 50.0),
        )
        val cursor = klinesToCursor(klines)

        // Set a very low rebalance trigger so deviation is guaranteed to exceed it
        val genome = defaultGenome()
        genome[GenomeParam.FLAT_REBALANCE_TRIGGER_PERCENT] = 0.01 // 1% deviation triggers rebalance

        val engine = TradingEngine(genome, Mode.SHADOW, initialCapital = 10_000.0)
        val result = simulateTicks(cursor, engine, initialCapital = 10_000.0)

        // At least one cycle should have rebalanceScheduled=true
        val rebalanceTicks = result.cycles.view.filter { it.rebalanceScheduled }
        assertTrue(rebalanceTicks.isNotEmpty(), "Expected rebalance scheduling on large price deviation")
    }
}
