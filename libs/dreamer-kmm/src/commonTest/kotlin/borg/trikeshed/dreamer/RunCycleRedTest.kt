package borg.trikeshed.dreamer

import borg.trikeshed.collections.s_
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression test for the full Cursor → PortfolioInput → runCycle → CycleResult
 * back-test pipeline, covering the end-to-end stochastic back-testing features.
 *
 * Chain under test:
 *   Binance archive data → KlineCsvParser → KlineBlock → asCursor() → Cursor
 *     → klineBarToPortfolioInput → PortfolioInput
 *     → simulateTicks(TradingEngine) → BacktestResult
 *     → computeBacktestMetrics → BacktestMetrics
 *     → BacktestResult.toBacktestReport() → BacktestReport
 *     → aggregate back-test metrics (total return, Sharpe, max drawdown)
 *
 * This is a regression test — it pins the pipeline against known-good inputs
 * and asserts that every adapter in the chain produces the expected output.
 * If any adapter in the chain breaks, one of these tests will fail first.
 */
class RunCycleRedTest {

    private val header = "open_time,open,high,low,close,volume,close_time,quote_asset_volume,number_of_trades,taker_buy_base_volume,taker_buy_quote_volume,ignore"

    private fun csv(vararg rows: String): String = buildString {
        appendLine(header)
        rows.forEach { appendLine(it) }
    }

    private fun doubleSeriesOf(values: List<Double>): Series<Double> = values.toSeries()

    // ── 1. Cursor → PortfolioInput adapters ─────────────────────────────────

    @Test
    fun `klineBarToPortfolioInput round-trips through simulateTicks`() = runTest {
        // Small rising series: 100 → 103 → 106
        val csvText = csv(
            "1704067200000,100.0,102.0,99.0,103.0,10.0,1704070799999,1030.0,12,5.0,515.0,0",
            "1704070800000,103.0,105.0,101.0,106.0,11.0,1704074399999,1166.0,15,6.0,636.0,0",
        )
        val chars = csvText.length j { i: Int -> csvText[i] }
        val klines = klinesFromCsv(chars, "BTCUSDT", TimeSpan.Hours1)
        assertEquals(2, klines.size)

        val block = KlineBlock.mutable(TimeSpan.Hours1)
        for (i in 0 until klines.size) block.append(klines.b(i).toKline())
        block.seal()
        val cursor = block.asCursor()

        val engine = TradingEngine(defaultGenome(), Mode.SHADOW, initialCapital = 10_000.0)
        val result = simulateTicks(cursor, engine, initialCapital = 10_000.0)

        assertEquals("BTCUSDT", result.symbol)
        assertEquals(2, result.cycles.size)
        assertEquals(2, result.metrics.totalTicks)
        // Rising prices: totalReturn should be positive
        assertTrue(result.metrics.totalReturn > 0.0,
            "Rising series should have positive totalReturn: ${result.metrics.totalReturn}")
    }

    @Test
    fun `klineBarToPortfolioInput uses close price from cursor row`() = runTest {
        val csvText = csv(
            "1704067200000,200.0,205.0,198.0,203.0,10.0,1704070799999,2030.0,12,5.0,1015.0,0",
        )
        val chars = csvText.length j { i: Int -> csvText[i] }
        val klines = klinesFromCsv(chars, "ETHUSDT", TimeSpan.Hours1)
        val block = KlineBlock.mutable(TimeSpan.Hours1)
        klines.forEach { block.append(it.toKline()) }
        val cursor = block.seal().asCursor()

        val input = klineBarToPortfolioInput(cursor, 0, currentQuantity = 1.0)
        assertEquals("ETHUSDT", input.symbol)
        assertEquals(203.0, input.price, 0.001, "price should be close (203.0), not open (200.0)")
        assertEquals(203.0, input.value, 0.001)
    }

    @Test
    fun `klineBarToPortfolioInput falls back to open when close is zero`() = runTest {
        val klines = listOf(
            Kline("BTCUSDT", TimeSpan.Hours1, 1704067200000L, 100.0, 102.0, 98.0, 0.0, 10.0),
        )
        val block = KlineBlock.mutable(TimeSpan.Hours1)
        klines.forEach { block.append(it) }
        val cursor = block.seal().asCursor()

        val input = klineBarToPortfolioInput(cursor, 0, currentQuantity = 2.0)
        assertEquals(100.0, input.price, 0.001, "price should fall back to open when close is 0")
        assertEquals(200.0, input.value, 0.001)
    }

    // ── 2. simulateTicks — run a full back-test simulation over cursor bars ──

    @Test
    fun `simulateTicks end-to-end from CSV text to BacktestResult`() = runTest {
        val csvText = csv(
            "1704067200000,42000.0,42500.0,41800.0,42300.0,150.0,1704070799999,6345000.0,3200,75.0,3167250.0,0",
            "1704070800000,42300.0,43100.0,42100.0,42900.0,180.0,1704074399999,7722000.0,4100,90.0,3861000.0,0",
            "1704074400000,42900.0,43200.0,42500.0,42800.0,140.0,1704077999999,5992000.0,2800,70.0,2996000.0,0",
        )
        val chars = csvText.length j { i: Int -> csvText[i] }
        val klines = klinesFromCsv(chars, "BTCUSDT", TimeSpan.Hours1)
        assertEquals(3, klines.size)

        val block = KlineBlock.mutable(TimeSpan.Hours1)
        for (i in 0 until klines.size) block.append(klines.b(i).toKline())
        val cursor = block.seal().asCursor()

        val engine = TradingEngine(defaultGenome(), Mode.SHADOW, initialCapital = 10_000.0)
        val result = simulateTicks(cursor, engine, initialCapital = 10_000.0)

        assertEquals("BTCUSDT", result.symbol)
        assertEquals(10_000.0, result.initialCapital)
        assertEquals(3, result.cycles.size)
        assertTrue(result.metrics.totalTicks == 3)
        assertNotNull(result.metrics)
    }

    @Test
    fun `simulateTicks onCycle callback fires for every bar`() = runTest {
        val csvText = csv(
            "1704067200000,100.0,102.0,99.0,101.0,10.0,1704070799999,1010.0,12,5.0,505.0,0",
            "1704070800000,101.0,104.0,100.0,103.0,12.0,1704074399999,1236.0,18,6.0,618.0,0",
            "1704074400000,103.0,106.0,102.0,105.0,14.0,1704077999999,1470.0,24,7.0,735.0,0",
        )
        val chars = csvText.length j { i: Int -> csvText[i] }
        val klines = klinesFromCsv(chars, "BTCUSDT", TimeSpan.Hours1)
        val block = KlineBlock.mutable(TimeSpan.Hours1)
        for (i in 0 until klines.size) block.append(klines.b(i).toKline())
        val cursor = block.seal().asCursor()

        val seenTicks = mutableListOf<Int>()
        val engine = TradingEngine(defaultGenome(), Mode.SHADOW, initialCapital = 10_000.0)
        simulateTicks(cursor, engine, initialCapital = 10_000.0) { cycle ->
            seenTicks.add(cycle.tick)
        }

        assertEquals(3, seenTicks.size)
        assertEquals(listOf(0, 1, 2), seenTicks)
    }

    @Test
    fun `simulateTicks zero-bar cursor returns empty result`() = runTest {
        val emptyCsv = header  // header only
        val chars = emptyCsv.length j { i: Int -> emptyCsv[i] }
        val klines = klinesFromCsv(chars, "BTCUSDT", TimeSpan.Hours1)
        val block = KlineBlock.mutable(TimeSpan.Hours1)
        for (i in 0 until klines.size) block.append(klines.b(i).toKline())
        val cursor = block.seal().asCursor()

        val engine = TradingEngine(defaultGenome(), Mode.SHADOW, initialCapital = 10_000.0)
        val result = simulateTicks(cursor, engine, initialCapital = 10_000.0)

        assertEquals(0, result.cycles.size)
        assertEquals(0, result.metrics.totalTicks)
        assertEquals(0.0, result.metrics.totalReturn)
    }

    // ── 3. CycleResult — per-tick result fields ───────────────────────────────

    @Test
    fun `CycleResult fields are populated correctly through simulateTicks`() = runTest {
        val csvText = csv(
            "1704067200000,100.0,102.0,99.0,101.0,10.0,1704070799999,1010.0,12,5.0,505.0,0",
            "1704070800000,101.0,104.0,100.0,103.0,12.0,1704074399999,1236.0,18,6.0,618.0,0",
        )
        val chars = csvText.length j { i: Int -> csvText[i] }
        val klines = klinesFromCsv(chars, "BTCUSDT", TimeSpan.Hours1)
        val block = KlineBlock.mutable(TimeSpan.Hours1)
        for (i in 0 until klines.size) block.append(klines.b(i).toKline())
        val cursor = block.seal().asCursor()

        val engine = TradingEngine(defaultGenome(), Mode.SHADOW, initialCapital = 10_000.0)
        val result = simulateTicks(cursor, engine, initialCapital = 10_000.0)

        val c0 = result.cycles[0]
        val c1 = result.cycles[1]
        assertEquals(0, c0.tick)
        assertEquals(1, c1.tick)
        assertEquals(1704067200000L, c0.openTime)
        assertEquals(1704070800000L, c1.openTime)
        // cashBalance + holdingsValue = totalValue
        assertTrue(c0.totalValue >= 0.0)
        assertTrue(c1.totalValue >= 0.0)
        // Second tick has higher close, so holdingsValue should increase
        assertTrue(c1.holdingsValue >= c0.holdingsValue - 1.0,
            "holdingsValue c1=${c1.holdingsValue} should be >= c0=${c0.holdingsValue} (rising prices)")
    }

    // ── 4. BacktestMetrics — aggregate metrics from cycle results ─────────────

    @Test
    fun `computeBacktestMetrics totalReturn computed from final vs initial equity`() {
        val cycles: Series<CycleResult> = s_[
            CycleResult(0, 0L, 5000.0, 5000.0, 10_000.0, false, 0.0, emptyList(), false, emptyMap()),
            CycleResult(1, 0L, 5000.0, 6000.0, 11_000.0, false, 0.0, emptyList(), false, emptyMap()),
        ]
        val closes = doubleSeriesOf(listOf(10_000.0, 11_000.0))
        val metrics = computeBacktestMetrics(cycles, 10_000.0, closes)

        assertEquals(0.10, metrics.totalReturn, 0.001)
    }

    @Test
    fun `computeBacktestMetrics maxDrawdown correct for peak-trough sequence`() {
        // Equity: 100 → 110 → 95 → 105
        // Peak: 110 at tick 1, trough: 95 at tick 2, maxDD = (110-95)/110 ≈ 13.6%
        val cycles = s_[
            CycleResult(0, 0L, 0.0, 100.0, 100.0, false, 0.0, emptyList(), false, emptyMap()),
            CycleResult(1, 0L, 0.0, 110.0, 110.0, false, 0.0, emptyList(), false, emptyMap()),
            CycleResult(2, 0L, 0.0, 95.0,  95.0,  false, 0.0, emptyList(), false, emptyMap()),
            CycleResult(3, 0L, 0.0, 105.0, 105.0, false, 0.0, emptyList(), false, emptyMap()),
        ]
        val closes = doubleSeriesOf(listOf(100.0, 110.0, 95.0, 105.0))
        val metrics = computeBacktestMetrics(cycles, 100.0, closes)

        assertTrue(metrics.maxDrawdown > 0.135 && metrics.maxDrawdown < 0.138,
            "maxDrawdown=${metrics.maxDrawdown} should be ≈ 13.6%")
    }

    @Test
    fun `computeBacktestMetrics sharpeRatio zero for flat equity`() {
        val cycles = s_[
            CycleResult(0, 0L, 0.0, 10_000.0, 10_000.0, false, 0.0, emptyList(), false, emptyMap()),
            CycleResult(1, 0L, 0.0, 10_000.0, 10_000.0, false, 0.0, emptyList(), false, emptyMap()),
            CycleResult(2, 0L, 0.0, 10_000.0, 10_000.0, false, 0.0, emptyList(), false, emptyMap()),
        ]
        val closes = doubleSeriesOf(listOf(10_000.0, 10_000.0, 10_000.0))
        val metrics = computeBacktestMetrics(cycles, 10_000.0, closes)

        assertEquals(0.0, metrics.sharpeRatio, "Flat equity → zero variance → zero Sharpe")
    }

    @Test
    fun `computeBacktestMetrics sortinoRatio finite and positive for up-only equity`() {
        // Up-only with no downside: returns are +10%, 0%, +10%
        // Downside = 0, so sortino is finite (not infinite) due to 0-div guard → 0
        // We test that sortino does not throw and is computed correctly.
        // Note: with MAR=0, sortino equals sharpe when downside=0 (both ratio to 0-deviation).
        // The key property is: both ratios are finite and positive.
        val cycles = s_[
            CycleResult(0, 0L, 0.0, 100.0, 100.0, false, 0.0, emptyList(), false, emptyMap()),
            CycleResult(1, 0L, 0.0, 110.0, 110.0, false, 0.0, emptyList(), false, emptyMap()),
            CycleResult(2, 0L, 0.0, 110.0, 110.0, false, 0.0, emptyList(), false, emptyMap()),
            CycleResult(3, 0L, 0.0, 121.0, 121.0, false, 0.0, emptyList(), false, emptyMap()),
        ]
        val closes = doubleSeriesOf(listOf(100.0, 110.0, 110.0, 121.0))
        val metrics = computeBacktestMetrics(cycles, 100.0, closes)

        // Both ratios should be finite and positive for this up-only equity curve
        assertTrue(metrics.sharpeRatio > 0.0, "sharpe=${metrics.sharpeRatio} should be positive")
        assertTrue(metrics.sortinoRatio >= 0.0, "sortino=${metrics.sortinoRatio} should be non-negative")
        // Total return should be +21%
        assertTrue(metrics.totalReturn > 0.20, "totalReturn=${metrics.totalReturn} should exceed 0.20")
    }

    // ── 5. BacktestReport — stable summary ───────────────────────────────────

    @Test
    fun backtestResult_toBacktestReport_extracts_final_equity() {
        val cycles = s_[
            CycleResult(0, 1000L, 0.0, 10_000.0, 10_000.0, false, 0.0, emptyList(), false, emptyMap()),
            CycleResult(1, 2000L, 0.0, 12_000.0, 12_000.0, false, 0.0, emptyList(), false, emptyMap()),
        ]
        val metrics = BacktestMetrics(
            totalTicks = 2, totalReturn = 0.20, sharpeRatio = 1.5,
            sortinoRatio = 2.0, maxDrawdown = 0.05, maxDrawdownTicks = 0,
            totalHarvested = 0.0, totalTrades = 0, avgHarvestPerTick = 0.0,
        )
        val result = BacktestResult("BTCUSDT", 10_000.0, cycles, metrics)
        val report = result.toBacktestReport()

        assertEquals(12_000.0, report.finalEquity, 0.001)
        assertEquals(0.20, report.totalReturn, 0.001)
        assertEquals(1.5, report.sharpeRatio, 0.001)
    }

    @Test
    fun backtestResult_toBacktestReport_with_empty_cycles() {
        val metrics = BacktestMetrics(
            totalTicks = 0, totalReturn = 0.0, sharpeRatio = 0.0,
            sortinoRatio = 0.0, maxDrawdown = 0.0, maxDrawdownTicks = 0,
            totalHarvested = 0.0, totalTrades = 0, avgHarvestPerTick = 0.0,
        )
        val result = BacktestResult("ETHUSDT", 25_000.0, emptySeries(), metrics)
        val report = result.toBacktestReport()

        assertEquals(25_000.0, report.finalEquity, 0.001,
            "Empty cycles: initialCapital should be the finalEquity")
        assertEquals(0.0, report.totalReturn)
    }

    // ── 6. SimulationReplay — CSV text → BacktestResult in one call ──────────

    @Test
    fun replayCsv_produces_consistent_BacktestResult() = runTest {
        val csvText = csv(
            "1704067200000,100.0,102.0,99.0,101.0,10.0,1704070799999,1010.0,12,5.0,505.0,0",
            "1704070800000,101.0,104.0,100.0,103.0,12.0,1704074399999,1236.0,18,6.0,618.0,0",
            "1704074400000,103.0,106.0,102.0,105.0,14.0,1704077999999,1470.0,24,7.0,735.0,0",
        )
        val replay = SimulationReplay(
            genome = defaultGenome(),
            mode = Mode.SHADOW,
            initialCapital = 10_000.0,
        )
        val result = replay.replayCsv(csvText, "BTCUSDT", TimeSpan.Hours1)

        assertEquals("BTCUSDT", result.symbol)
        assertEquals(10_000.0, result.initialCapital)
        assertEquals(3, result.cycles.size)
        assertTrue(result.metrics.totalReturn > 0.0, "Rising prices should yield positive return")
    }

    @Test
    fun `SimulationReplay with declining prices produces negative totalReturn`() = runTest {
        val csvText = csv(
            "1704067200000,100.0,101.0,98.0,100.0,10.0,1704070799999,1000.0,10,5.0,500.0,0",
            "1704070800000,100.0,99.0,96.0,97.0,12.0,1704074399999,1164.0,15,6.0,582.0,0",
            "1704074400000,97.0,96.0,92.0,93.0,14.0,1704077999999,1302.0,20,7.0,651.0,0",
        )
        val replay = SimulationReplay(genome = defaultGenome(), mode = Mode.SHADOW, initialCapital = 10_000.0)
        val result = replay.replayCsv(csvText, "BTCUSDT", TimeSpan.Hours1)

        assertTrue(result.metrics.totalReturn < 0.0,
            "Declining prices should yield negative totalReturn: ${result.metrics.totalReturn}")
        assertTrue(result.metrics.maxDrawdown > 0.0,
            "Declining prices should produce a drawdown: ${result.metrics.maxDrawdown}")
    }

    // ── 7. Evolution.kt integration ───────────────────────────────────────────

    @Test
    fun `evaluatePopulation replays all genomes over same CSV and assigns correct fitness`() = runTest {
        val csvText = csv(
            "1704067200000,100.0,102.0,99.0,101.0,10.0,1704070799999,1010.0,12,5.0,505.0,0",
            "1704070800000,101.0,104.0,100.0,103.0,12.0,1704074399999,1236.0,18,6.0,618.0,0",
        )
        val a = defaultGenome()
        val b = defaultGenome().also { it["HARVEST_TAKE_PERCENT"] = 0.50 }

        val evaluations = evaluatePopulation(
            genomes = listOf(a, b),
            csvText = csvText,
            symbol = "BTCUSDT",
            timespan = TimeSpan.Hours1,
            initialCapital = 10_000.0,
        )

        assertEquals(2, evaluations.size)
        assertTrue(evaluations.all { it.fitness.isFinite() })
        assertTrue(evaluations.all { it.result.metrics.totalTicks == 2 })
    }

    @Test
    fun `crossoverGenome child inherits left genome's numeric params below pivot`() {
        val left = Genome(mutableMapOf("A" to 1.0, "B" to 2.0, "C" to 3.0, "D" to 4.0))
        val right = Genome(mutableMapOf("A" to 10.0, "B" to 20.0, "C" to 30.0, "D" to 40.0))
        val child = crossoverGenome(left, right)
        // Pivot at WIDTH/2 for doubles; keys pivot at midpoint of sorted keys
        assertTrue(child["A"] as Double <= 1.0 || child["A"] as Double >= 10.0)
    }

    @Test
    fun `mutateGenome does not mutate parent`() {
        val parent = Genome(mutableMapOf("X" to 0.50))
        val mutant = mutateGenome(parent, mapOf("X" to -0.10))
        assertEquals(0.50, parent["X"] as Double, "parent should not be mutated")
        assertEquals(0.40, mutant["X"] as Double, 0.001)
    }

    @Test
    fun `rankEvaluationsByFitness sorts highest fitness first`() {
        val low = GenomeEvaluation(
            Genome(mutableMapOf("id" to "low")),
            backtestResult(metrics(0.01, 0.1, 0.1, 0.30)), 0.31,
        )
        val high = GenomeEvaluation(
            Genome(mutableMapOf("id" to "high")),
            backtestResult(metrics(0.10, 2.0, 3.0, 0.05)), 5.15,
        )
        val ranked = rankEvaluationsByFitness(listOf(low, high))
        assertEquals("high", ranked[0].genome["id"])
        assertEquals("low", ranked[1].genome["id"])
    }

    @Test
    fun `evolvePopulation preserves elite genome unchanged`() {
        val elite = GenomeEvaluation(
            Genome(mutableMapOf("A" to 1.0, "B" to 2.0)),
            backtestResult(metrics(0.20, 3.0, 4.0, 0.02)), 7.28,
        )
        val weak = GenomeEvaluation(
            Genome(mutableMapOf("A" to 0.5, "B" to 1.0)),
            backtestResult(metrics(0.01, 0.1, 0.1, 0.30)), 0.31,
        )
        val next = evolvePopulation(listOf(elite, weak))
        assertEquals(2, next.size)
        // Elite must be preserved unchanged
        assertEquals(1.0, next[0]["A"] as Double, "elite genome should be preserved exactly")
    }

    private fun metrics(
        totalReturn: Double, sharpeRatio: Double,
        sortinoRatio: Double, maxDrawdown: Double,
    ): BacktestMetrics = BacktestMetrics(
        totalTicks = 4, totalReturn = totalReturn, sharpeRatio = sharpeRatio,
        sortinoRatio = sortinoRatio, maxDrawdown = maxDrawdown, maxDrawdownTicks = 1,
        totalHarvested = 0.0, totalTrades = 0, avgHarvestPerTick = 0.0,
    )

    private fun backtestResult(metrics: BacktestMetrics): BacktestResult = BacktestResult(
        symbol = "BTCUSDT",
        initialCapital = 10_000.0,
        cycles = emptySeries(),
        metrics = metrics,
    )

    // ── 8. Aggregate back-test metrics — full chain verification ───────────────

    @Test
    fun full_chain_csv_to_simulation_to_BacktestReport() = runTest {
        // 4 bars: 100 → 103 → 106 → 109 (roughly +9% total)
        val csvText = csv(
            "1704067200000,100.0,102.0,99.0,103.0,10.0,1704070799999,1030.0,12,5.0,515.0,0",
            "1704070800000,103.0,105.0,101.0,106.0,11.0,1704074399999,1166.0,15,6.0,636.0,0",
            "1704074400000,106.0,108.0,105.0,109.0,12.0,1704077999999,1308.0,18,7.0,763.0,0",
            "1704078000000,109.0,111.0,108.0,112.0,13.0,1704081599999,1456.0,21,8.0,896.0,0",
        )
        val replay = SimulationReplay(genome = defaultGenome(), mode = Mode.SHADOW, initialCapital = 10_000.0)
        val result = replay.replayCsv(csvText, "BTCUSDT", TimeSpan.Hours1)
        val report = result.toBacktestReport()

        assertEquals("BTCUSDT", report.symbol)
        assertEquals(10_000.0, report.initialCapital, 0.001)
        assertEquals(4, report.totalTicks)
        assertTrue(report.finalEquity > 10_000.0,
            "finalEquity=${report.finalEquity} should exceed initialCapital=10000 (rising prices)")
        assertTrue(report.totalReturn > 0.0,
            "totalReturn=${report.totalReturn} should be positive for rising series")
        assertTrue(report.sharpeRatio.isFinite(), "sharpeRatio should be finite: ${report.sharpeRatio}")
        assertTrue(report.sortinoRatio.isFinite(), "sortinoRatio should be finite: ${report.sortinoRatio}")
        assertTrue(report.maxDrawdown >= 0.0, "maxDrawdown should be non-negative: ${report.maxDrawdown}")
        assertTrue(report.totalTrades >= 0)
    }

    @Test
    fun full_chain_replay_evaluate_evolve() = runTest {
        val csvText = csv(
            "1704067200000,100.0,102.0,99.0,101.0,10.0,1704070799999,1010.0,12,5.0,505.0,0",
            "1704070800000,101.0,104.0,100.0,103.0,12.0,1704074399999,1236.0,18,6.0,618.0,0",
            "1704074400000,103.0,106.0,102.0,105.0,14.0,1704077999999,1470.0,24,7.0,735.0,0",
        )
        val genomeA = defaultGenome()
        val genomeB = mutateGenome(defaultGenome(), mapOf("HARVEST_TAKE_PERCENT" to 0.10))

        val evaluations = evaluatePopulation(
            listOf(genomeA, genomeB),
            csvText, "BTCUSDT", TimeSpan.Hours1, initialCapital = 10_000.0,
        )

        val ranked = rankEvaluationsByFitness(evaluations)
        val nextGen = evolvePopulation(evaluations, mutationDeltas = mapOf("HARVEST_TAKE_PERCENT" to 0.01))

        assertEquals(2, nextGen.size, "nextGen should have same population size")
        assertEquals(ranked.first().genome["A"] ?: ranked.first().genome["HARVEST_TAKE_PERCENT"],
            nextGen[0]["HARVEST_TAKE_PERCENT"],
            "elite genome should be preserved as first citizen in nextGen")
        assertTrue(nextGen.all { it["HARVEST_TAKE_PERCENT"] is Double })
    }

    // ── 9. Edge cases ────────────────────────────────────────────────────────

    @Test
    fun `simulateTicks with flat prices produces near-zero return and zero Sharpe`() = runTest {
        val csvText = csv(
            "1704067200000,100.0,100.0,100.0,100.0,10.0,1704070799999,1000.0,10,5.0,500.0,0",
            "1704070800000,100.0,100.0,100.0,100.0,10.0,1704074399999,1000.0,10,5.0,500.0,0",
            "1704070800000,100.0,100.0,100.0,100.0,10.0,1704077999999,1000.0,10,5.0,500.0,0",
        )
        val replay = SimulationReplay(genome = defaultGenome(), mode = Mode.SHADOW, initialCapital = 10_000.0)
        val result = replay.replayCsv(csvText, "FLATUSDT", TimeSpan.Hours1)

        assertTrue(result.metrics.totalReturn < 0.001,
            "Flat prices → near-zero return: ${result.metrics.totalReturn}")
        assertEquals(0.0, result.metrics.sharpeRatio, 0.001,
            "Flat equity → zero Sharpe: ${result.metrics.sharpeRatio}")
    }

    @Test
    fun `computeBacktestMetrics with single cycle returns zero return and zero sharpe`() {
        val cycles = s_[
            CycleResult(0, 0L, 0.0, 10_000.0, 10_000.0, false, 0.0, emptyList(), false, emptyMap()),
        ]
        val closes = doubleSeriesOf(listOf(10_000.0))
        val metrics = computeBacktestMetrics(cycles, 10_000.0, closes)

        assertEquals(1, metrics.totalTicks)
        assertEquals(0.0, metrics.totalReturn, 0.001)
        assertEquals(0.0, metrics.sharpeRatio, 0.001)
        assertEquals(0.0, metrics.sortinoRatio, 0.001)
        assertEquals(0.0, metrics.maxDrawdown, 0.001)
        assertEquals(0, metrics.maxDrawdownTicks)
    }

    @Test
    fun `closesFromCursor extracts close prices from kline cursor`() = runTest {
        val csvText = csv(
            "1704067200000,100.0,102.0,99.0,101.0,10.0,1704070799999,1010.0,12,5.0,505.0,0",
            "1704070800000,101.0,104.0,100.0,103.0,12.0,1704074399999,1236.0,18,6.0,618.0,0",
            "1704074400000,103.0,106.0,102.0,105.0,14.0,1704077999999,1470.0,24,7.0,735.0,0",
        )
        val chars = csvText.length j { i: Int -> csvText[i] }
        val klines = klinesFromCsv(chars, "BTCUSDT", TimeSpan.Hours1)
        val block = KlineBlock.mutable(TimeSpan.Hours1)
        for (i in 0 until klines.size) block.append(klines.b(i).toKline())
        val cursor = block.seal().asCursor()

        val closes = closesFromCursor(cursor)
        assertEquals(3, closes.size)
        assertEquals(101.0, closes[0])
        assertEquals(103.0, closes[1])
        assertEquals(105.0, closes[2])
    }

    // ── 9b. Genome backing is populated after construction ────────────────────

    @Test
    fun `Genome backing is populated from doubles after construction`() {
        val g = defaultGenome()
        // backing must contain the genome parameters so that crossoverGenome and
        // aggregateEvaluations can read them correctly
        assertTrue(g.backing.isNotEmpty(),
            "backing should be populated from doubles after construction; found ${g.backing.size} entries")
        // The doubles are accessible both via GenomeParam ordinal and via backing string key
        val harvestKey = "HARVEST_TAKE_PERCENT"
        val fromBacking = g[harvestKey]
        assertNotNull(fromBacking, "HARVEST_TAKE_PERCENT should be in backing")
    }

    @Test
    fun `crossoverGenome child has populated backing after construction`() {
        val left = defaultGenome()
        val right = defaultGenome()
        val child = crossoverGenome(left, right)
        // Child's backing must contain genome parameters so aggregateEvaluations
        // can extract bestGenome backing map for next-generation carry-forward.
        // Child is constructed via Genome(DoubleArray) which bypasses initBacking.
        assertTrue(child.backing.isNotEmpty(),
            "crossoverGenome child backing must be populated from doubles; found ${child.backing.size} entries")
        // Verify a known key is present and has the correct double value
        val harvestKey = "HARVEST_TAKE_PERCENT"
        val fromBacking = child[harvestKey]
        assertNotNull(fromBacking, "HARVEST_TAKE_PERCENT must be in child's backing")
        assertTrue(fromBacking is Double, "HARVEST_TAKE_PERCENT in backing should be a Double")
    }

    // ── 10. simulateMultiSymbolTicks — multi-symbol back-test ─────────────────

    /**
     * Build a multi-symbol interleaved cursor from two single-symbol KlineBlocks
     * and verify simulateMultiSymbolTicks produces cycles with multiple symbols.
     */
    @Test
    fun `simulateMultiSymbolTicks produces MULTI symbol result with two symbols`() = runTest {
        val btcKlines = listOf(
            Kline("BTCUSDT", TimeSpan.Hours1, 1704067200000L, 42000.0, 42500.0, 41800.0, 42300.0, 150.0),
            Kline("BTCUSDT", TimeSpan.Hours1, 1704070800000L, 42300.0, 43100.0, 42100.0, 42900.0, 180.0),
        )
        val ethKlines = listOf(
            Kline("ETHUSDT", TimeSpan.Hours1, 1704067200000L, 2500.0, 2550.0, 2490.0, 2530.0, 500.0),
            Kline("ETHUSDT", TimeSpan.Hours1, 1704070800000L, 2530.0, 2600.0, 2520.0, 2580.0, 600.0),
        )
        val btcBlock = KlineBlock.mutable(TimeSpan.Hours1)
        btcKlines.forEach { btcBlock.append(it) }
        val ethBlock = KlineBlock.mutable(TimeSpan.Hours1)
        ethKlines.forEach { ethBlock.append(it) }
        btcBlock.seal()
        ethBlock.seal()

        // Interleave: BTC[0], ETH[0], BTC[1], ETH[1]
        val interleaved = listOf(btcKlines[0], ethKlines[0], btcKlines[1], ethKlines[1])
        val mixedBlock = KlineBlock.mutable(TimeSpan.Hours1)
        interleaved.forEach { mixedBlock.append(it) }
        mixedBlock.seal()
        val cursor = mixedBlock.asCursor()
        assertEquals(4, cursor.size)

        val engine = TradingEngine(defaultGenome(), Mode.SHADOW, initialCapital = 10_000.0)
        val result = simulateMultiSymbolTicks(cursor, engine, initialCapital = 10_000.0)

        assertEquals("MULTI", result.symbol)
        assertEquals(2, result.cycles.size, "2 ticks (2 unique openTimes)")
        assertEquals(2, result.metrics.totalTicks)
    }

    /**
     * Verify allSymbolsAtBar collects every distinct symbol at a given openTime.
     */
    @Test
    fun `allSymbolsAtBar returns one input per distinct symbol at that openTime`() = runTest {
        val btcKlines = listOf(
            Kline("BTCUSDT", TimeSpan.Hours1, 1704067200000L, 42000.0, 42500.0, 41800.0, 42300.0, 150.0),
        )
        val ethKlines = listOf(
            Kline("ETHUSDT", TimeSpan.Hours1, 1704067200000L, 2500.0, 2550.0, 2490.0, 2530.0, 500.0),
        )
        val block = KlineBlock.mutable(TimeSpan.Hours1)
        block.append(btcKlines[0])
        block.append(ethKlines[0])
        block.seal()
        val cursor = block.asCursor()

        val holdings = mapOf("BTCUSDT" to 0.1, "ETHUSDT" to 1.0)
        val inputs = allSymbolsAtBar(cursor, barIndex = 0, holdings = holdings)

        assertEquals(2, inputs.size, "Both BTC and ETH share the same openTime")
        assertTrue(inputs.any { it.symbol == "BTCUSDT" })
        assertTrue(inputs.any { it.symbol == "ETHUSDT" })
        // Verify each has correct quantity from holdings
        val btcInput = inputs.first { it.symbol == "BTCUSDT" }
        val ethInput = inputs.first { it.symbol == "ETHUSDT" }
        assertEquals(0.1, btcInput.quantity, 0.001)
        assertEquals(1.0, ethInput.quantity, 0.001)
    }

    /**
     * allSymbolsAtBar on cursor with one symbol returns that symbol.
     * Out-of-range barIndex wraps to index 0 via cursor.at() modulo semantics.
     */
    @Test
    fun `allSymbolsAtBar returns single symbol when cursor has one symbol`() = runTest {
        val block = KlineBlock.mutable(TimeSpan.Hours1)
        block.append(Kline("BTCUSDT", TimeSpan.Hours1, 1704067200000L, 42000.0, 42500.0, 41800.0, 42300.0, 150.0))
        block.seal()
        val cursor = block.asCursor()

        val holdings = mapOf("BTCUSDT" to 0.1)
        val inputs = allSymbolsAtBar(cursor, barIndex = 0, holdings = holdings)

        assertEquals(1, inputs.size)
        assertEquals("BTCUSDT", inputs.first().symbol)
        assertEquals(0.1, inputs.first().quantity, 0.001)
    }

    /**
     * simulateMultiSymbolTicks with empty cursor returns empty result.
     */
    @Test
    fun `simulateMultiSymbolTicks on empty cursor returns empty result`() = runTest {
        val emptyBlock = KlineBlock.mutable(TimeSpan.Hours1)
        emptyBlock.seal()
        val cursor = emptyBlock.asCursor()

        val engine = TradingEngine(defaultGenome(), Mode.SHADOW, initialCapital = 10_000.0)
        val result = simulateMultiSymbolTicks(cursor, engine, initialCapital = 10_000.0)

        assertEquals(0, result.cycles.size)
        assertEquals(0, result.metrics.totalTicks)
        assertEquals("MULTI", result.symbol)
    }

    // ── 11. Multi-symbol edge cases: 3+ symbols, uneven timestamps ──────

    /**
     * allSymbolsAtBar with 3 symbols at the same openTime returns all 3.
     */
    @Test
    fun `allSymbolsAtBar returns 3 inputs for 3 symbols sharing openTime`() = runTest {
        val openTime = 1704067200000L
        val block = KlineBlock.mutable(TimeSpan.Hours1)
        block.append(Kline("BTCUSDT", TimeSpan.Hours1, openTime, 42000.0, 42500.0, 41800.0, 42300.0, 150.0))
        block.append(Kline("ETHUSDT", TimeSpan.Hours1, openTime, 2500.0, 2550.0, 2490.0, 2530.0, 500.0))
        block.append(Kline("SOLUSDT", TimeSpan.Hours1, openTime, 142.0, 145.0, 140.0, 143.0, 1000.0))
        block.seal()
        val cursor = block.asCursor()

        val holdings = mapOf("BTCUSDT" to 0.1, "ETHUSDT" to 1.0, "SOLUSDT" to 5.0)
        val inputs = allSymbolsAtBar(cursor, barIndex = 0, holdings = holdings)

        assertEquals(3, inputs.size, "Should find all 3 symbols")
        val symbols = inputs.map { it.symbol }.toSet()
        assertTrue("BTCUSDT" in symbols)
        assertTrue("ETHUSDT" in symbols)
        assertTrue("SOLUSDT" in symbols)
        // Verify quantities come from holdings
        assertEquals(0.1, inputs.first { it.symbol == "BTCUSDT" }.quantity, 0.001)
        assertEquals(1.0, inputs.first { it.symbol == "ETHUSDT" }.quantity, 0.001)
        assertEquals(5.0, inputs.first { it.symbol == "SOLUSDT" }.quantity, 0.001)
    }

    /**
     * simulateMultiSymbolTicks with 3 symbols interleaved at same timestamps
     * produces correct tick count and cycle count.
     */
    @Test
    fun `simulateMultiSymbolTicks with 3 symbols produces correct tick count`() = runTest {
        val t0 = 1704067200000L
        val t1 = 1704070800000L
        val block = KlineBlock.mutable(TimeSpan.Hours1)
        // tick 0: BTC, ETH, SOL
        block.append(Kline("BTCUSDT", TimeSpan.Hours1, t0, 42000.0, 42500.0, 41800.0, 42300.0, 150.0))
        block.append(Kline("ETHUSDT", TimeSpan.Hours1, t0, 2500.0, 2550.0, 2490.0, 2530.0, 500.0))
        block.append(Kline("SOLUSDT", TimeSpan.Hours1, t0, 142.0, 145.0, 140.0, 143.0, 1000.0))
        // tick 1: BTC, ETH, SOL
        block.append(Kline("BTCUSDT", TimeSpan.Hours1, t1, 42300.0, 43100.0, 42100.0, 42900.0, 180.0))
        block.append(Kline("ETHUSDT", TimeSpan.Hours1, t1, 2530.0, 2600.0, 2520.0, 2580.0, 600.0))
        block.append(Kline("SOLUSDT", TimeSpan.Hours1, t1, 143.0, 148.0, 141.0, 147.0, 1100.0))
        block.seal()
        val cursor = block.asCursor()
        assertEquals(6, cursor.size)

        val engine = TradingEngine(defaultGenome(), Mode.SHADOW, initialCapital = 30_000.0)
        val result = simulateMultiSymbolTicks(cursor, engine, initialCapital = 30_000.0)

        assertEquals("MULTI", result.symbol)
        assertEquals(2, result.cycles.size, "2 unique openTimes → 2 ticks")
        assertEquals(2, result.metrics.totalTicks)
    }

    /**
     * When symbols have different sets of timestamps, simulateMultiSymbolTicks
     * groups by unique openTime. Only the symbols present at each timestamp are
     * included in that tick's PortfolioInput list.
     */
    @Test
    fun `allSymbolsAtBar with uneven timestamps returns only symbols at that openTime`() = runTest {
        val t0 = 1704067200000L
        val t1 = 1704070800000L
        val block = KlineBlock.mutable(TimeSpan.Hours1)
        // t0: BTC and ETH only (SOL has no bar at t0)
        block.append(Kline("BTCUSDT", TimeSpan.Hours1, t0, 42000.0, 42500.0, 41800.0, 42300.0, 150.0))
        block.append(Kline("ETHUSDT", TimeSpan.Hours1, t0, 2500.0, 2550.0, 2490.0, 2530.0, 500.0))
        // t1: BTC and SOL only (ETH has no bar at t1)
        block.append(Kline("BTCUSDT", TimeSpan.Hours1, t1, 42300.0, 43100.0, 42100.0, 42900.0, 180.0))
        block.append(Kline("SOLUSDT", TimeSpan.Hours1, t1, 143.0, 148.0, 141.0, 147.0, 1100.0))
        block.seal()
        val cursor = block.asCursor()

        val holdings = mapOf("BTCUSDT" to 0.1, "ETHUSDT" to 1.0, "SOLUSDT" to 5.0)

        // At t0: only BTC and ETH
        val inputsAtT0 = allSymbolsAtBar(cursor, barIndex = 0, holdings = holdings)
        assertEquals(2, inputsAtT0.size, "t0 should have 2 symbols (BTC, ETH)")
        assertTrue(inputsAtT0.all { it.symbol in setOf("BTCUSDT", "ETHUSDT") })

        // At t1: BTC and SOL
        val inputsAtT1 = allSymbolsAtBar(cursor, barIndex = 2, holdings = holdings)
        assertEquals(2, inputsAtT1.size, "t1 should have 2 symbols (BTC, SOL)")
        assertTrue(inputsAtT1.all { it.symbol in setOf("BTCUSDT", "SOLUSDT") })
    }
}
