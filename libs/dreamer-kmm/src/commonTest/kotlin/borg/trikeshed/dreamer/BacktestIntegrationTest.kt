package borg.trikeshed.dreamer

import borg.trikeshed.collections.s_
import borg.trikeshed.lib.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end integration tests for the full back-test pipeline:
 *   Binance archive CSV → KlineCsvParser → KlineBlock → Cursor → simulateTicks
 *     → BacktestResult → BacktestReport
 *
 * These tests exercise the complete chain from archive data through to
 * aggregate metrics, verifying that each adapter and transformation
 * composes correctly.
 */
class BacktestIntegrationTest {

    /**
     * Full-chain test: CSV text → parse → block → seal → cursor → simulateTicks → BacktestReport
     *
     * This pins the entire chain end-to-end and catches integration regressions
     * where any adapter change breaks the downstream report.
     */
    @Test
    fun `full pipeline produces BacktestReport from Binance archive CSV`() = runTest {
        val csv = """
            open_time,open,high,low,close,volume,close_time,quote_asset_volume,number_of_trades,taker_buy_base_volume,taker_buy_quote_volume,ignore
            1704067200000,42000.0,42500.0,41800.0,42300.0,150.0,1704070799999,6345000.0,3200,75.0,3167250.0,0
            1704070800000,42300.0,43100.0,42100.0,42900.0,180.0,1704074399999,7722000.0,4100,90.0,3861000.0,0
            1704074400000,42900.0,43200.0,42500.0,42800.0,140.0,1704077999999,5992000.0,2800,70.0,2996000.0,0
            1704078000000,42800.0,43500.0,42700.0,43400.0,200.0,1704081599999,8680000.0,5200,100.0,4340000.0,0
        """.trimIndent()

        val replay = SimulationReplay(
            genome = defaultGenome(),
            mode = Mode.SHADOW,
            initialCapital = 10_000.0,
        )
        val result = replay.replayCsv(csvText = csv, symbol = "BTCUSDT", timespan = TimeSpan.Hours1)
        val report = result.toBacktestReport()

        // Verify report fields
        assertEquals("BTCUSDT", report.symbol)
        assertEquals(10_000.0, report.initialCapital, 0.001)
        assertEquals(4, report.totalTicks)
        assertTrue(report.finalEquity > 0.0, "finalEquity should be positive: ${report.finalEquity}")
        assertTrue(report.totalReturn > 0.0, "totalReturn should be positive with rising prices: ${report.totalReturn}")

        // Verify metrics are wired through
        assertEquals(result.metrics.totalReturn, report.totalReturn, 0.001)
        assertEquals(result.metrics.sharpeRatio, report.sharpeRatio, 0.001)
        assertEquals(result.metrics.maxDrawdown, report.maxDrawdown, 0.001)
        assertEquals(result.metrics.totalTicks, report.totalTicks)
    }

    /**
     * Verifies that a rising price series triggers harvest activity in the engine.
     *
     * The genome has ENABLE_PORTFOLIO_HARVEST=true and a small MIN_SURPLUS_FOR_HARVEST,
     * so surplus above baseline should trigger harvest. This test confirms the
     * TradingEngine harvest logic is exercised in the simulateTicks path.
     */
    @Test
    fun `simulateTicks triggers harvest when price rises above baseline`() = runTest {
        // Build a strongly rising price series to ensure surplus triggers harvest
        val klines = (0 until 10).map { i ->
            val price = 100.0 + i * 10.0  // 100 → 190
            Kline(
                symbol = "TESTUSDT",
                timespan = TimeSpan.Hours1,
                openTime = 1_704_067_200_000L + i * 3_600_000L,
                open = price - 1.0,
                high = price + 2.0,
                low = price - 2.0,
                close = price,
                volume = 50.0 + i,
            )
        }
        val block = KlineBlock.mutable(TimeSpan.Hours1)
        klines.forEach { block.append(it) }
        val cursor = block.seal().asCursor()

        // Use genome with harvest enabled and low surplus threshold
        val genome = defaultGenome()
        genome[GenomeParam.MIN_SURPLUS_FOR_HARVEST] = 0.001  // very low threshold
        genome[GenomeParam.HARVEST_TAKE_PERCENT] = 0.50
        genome[GenomeParam.TARGET_ADJUST_PERCENT] = 0.001

        val engine = TradingEngine(genome, Mode.SHADOW, initialCapital = 10_000.0)
        val result = simulateTicks(cursor, engine, initialCapital = 10_000.0)

        // At least some cycles should have harvest activity
        val harvestCycles = result.cycles.view.filter { it.harvestedAmount > 0.0 }
        assertTrue(harvestCycles.isNotEmpty(), "Expected harvest activity with rising prices, got 0 harvest cycles")

        val totalHarvested = harvestCycles.sumOf { it.harvestedAmount }
        assertTrue(totalHarvested > 0.0, "Total harvested should be positive: $totalHarvested")

        // Trades count should be > 0
        assertTrue(result.metrics.totalTrades > 0, "totalTrades should be > 0: ${result.metrics.totalTrades}")
        assertTrue(result.metrics.totalHarvested > 0.0, "totalHarvested should be > 0: ${result.metrics.totalHarvested}")
    }

    /**
     * End-to-end: BinanceVisionKlineFeed.parseCachedCsv → simulateTicks → BacktestReport
     *
     * Exercises the full production path from CSV text through the KlineFeed
     * adapter, ensuring parseCachedCsv output composes with simulateTicks.
     */
    @Test
    fun `KlineFeed parseCachedCsv composes with simulateTicks pipeline`() = runTest {
        val csv = """
            open_time,open,high,low,close,volume,close_time,quote_asset_volume,number_of_trades,taker_buy_base_volume,taker_buy_quote_volume,ignore
            1704067200000,2500.0,2550.0,2490.0,2530.0,500.0,1704070799999,1265000.0,800,250.0,632500.0,0
            1704070800000,2530.0,2600.0,2520.0,2580.0,600.0,1704074399999,1548000.0,900,300.0,774000.0,0
            1704074400000,2580.0,2610.0,2550.0,2570.0,450.0,1704077999999,1156500.0,750,225.0,578250.0,0
        """.trimIndent()

        val feed = BinanceVisionKlineFeed()
        val key = klineSeriesKey("ETH", "USDT", TimeSpan.Hours1)
        val parsed = feed.parseCachedCsv(key, csv)

        // Verify parsing
        assertEquals("ETHUSDT", parsed.key.symbol)
        assertTrue(parsed.block.state == KlineBlock.State.SEALED)
        assertEquals(3, parsed.block.rowCount)

        // Run through simulateTicks
        val engine = TradingEngine(defaultGenome(), Mode.SHADOW, initialCapital = 5_000.0)
        val result = simulateTicks(parsed.block.asCursor(), engine, initialCapital = 5_000.0)
        val report = result.toBacktestReport()

        assertEquals("ETHUSDT", report.symbol)
        assertEquals(5_000.0, report.initialCapital, 0.001)
        assertEquals(3, report.totalTicks)
        assertTrue(report.finalEquity > 0.0)
    }

    /**
     * Multi-symbol end-to-end: two symbols through archive CSV → KlineBlock →
     * RealtimeHarness replay → verify both symbols produce trading cycles.
     *
     * This tests the RealtimeHarness multi-symbol path which uses DreamerAgent
     * and StochasticBag together.
     */
    @Test
    fun `RealtimeHarness multi-symbol end-to-end produces BacktestReport-quality metrics`() = runTest {
        val config = StochasticTrainingConfig(
            bases = listOf("BTC", "ETH"),
            rowsPerSeries = 20,
            populationSize = 2,
            spanLength = 4,
            initialCapital = 10_000.0,
            seed = 42,
        )

        val inputs = archiveInputs(config)
        assertEquals(2, inputs.size)
        inputs.forEach { input ->
            assertTrue(input.block.state == KlineBlock.State.SEALED)
            assertEquals(20, input.block.rowCount)
        }

        val genome = defaultGenome()
        val harness = RealtimeHarness(
            genome = genome,
            initialCapital = config.initialCapital,
            mode = Mode.SHADOW,
            stochasticSeed = config.seed,
            stochasticSpanLength = config.spanLength,
        )

        val runResult = harness.replay(inputs)

        // Verify multi-symbol cycles
        assertTrue(runResult.cycles.size > 0, "Should have cycles")
        assertEquals(20, runResult.cycles.size)

        // Each cycle should have 2 rows (BTC + ETH)
        runResult.cycles.forEach { cycle ->
            assertEquals(2, cycle.frame.rows.size)
            val symbols = cycle.frame.rows.map { it.symbol }.toSet()
            assertTrue(symbols.contains("BTCUSDT") || symbols.contains("ETHUSDT"),
                "Each cycle should have BTCUSDT or ETHUSDT: $symbols")
        }

        // Verify wallet journal has mark-to-market entries
        assertTrue(runResult.walletJournal.any { it.action == WalletAction.MARK_TO_MARKET })

        // Compute fitness from run result (same metric used in stochastic training)
        val fitness = runResult.fitness(config.initialCapital, genome)
        assertTrue(fitness.isFinite(), "Fitness should be finite: $fitness")

        // Compute max drawdown
        val maxDD = runResult.maxDrawdown(config.initialCapital)
        assertTrue(maxDD >= 0.0, "Max drawdown should be non-negative: $maxDD")
    }

    /**
     * Evolution pipeline end-to-end: evaluatePopulation → pick champion → BacktestReport
     *
     * Verifies that the evolutionary search can produce genome evaluations
     * and that the best genome's result can be converted to a BacktestReport.
     */
    @Test
    fun `evaluatePopulation produces BacktestReport from champion genome`() = runTest {
        val csv = generatedArchiveCsv(
            symbol = "SOLUSDT",
            rows = 30,
            timespan = TimeSpan.Minutes5,
            startOpenTime = 1_704_067_200_000L,
            assetIndex = 2,
            seed = 99,
        )

        val genomes = listOf(
            defaultGenome(),
            mutateGenome(defaultGenome(), mapOf("HARVEST_TAKE_PERCENT" to 0.80)),
            mutateGenome(defaultGenome(), mapOf("MIN_SURPLUS_FOR_HARVEST" to 0.001)),
        )

        val evaluations = evaluatePopulation(
            genomes = genomes,
            csvText = csv,
            symbol = "SOLUSDT",
            timespan = TimeSpan.Minutes5,
            initialCapital = 10_000.0,
        )

        assertEquals(3, evaluations.size)

        // Pick champion and produce report
        val champion = evaluations.first()
        val report = champion.result.toBacktestReport()

        assertEquals("SOLUSDT", report.symbol)
        assertEquals(10_000.0, report.initialCapital, 0.001)
        assertEquals(30, report.totalTicks)
        assertTrue(report.finalEquity > 0.0)
        assertTrue(champion.fitness.isFinite())
    }

    /**
     * Verifies that simulateTicks works with the columnar cursor produced
     * by KlineBlock.asColumnarCursor(). This tests the alternative cursor
     * implementation path.
     */
    @Test
    fun `simulateTicks works with columnar cursor from asColumnarCursor`() = runTest {
        val klines = listOf(
            Kline("BTCUSDT", TimeSpan.Hours1, 1704067200000L, 42000.0, 42500.0, 41800.0, 42300.0, 150.0),
            Kline("BTCUSDT", TimeSpan.Hours1, 1704070800000L, 42300.0, 43100.0, 42100.0, 42900.0, 180.0),
        )
        val block = KlineBlock.mutable(TimeSpan.Hours1)
        klines.forEach { block.append(it) }
        block.seal()

        val columnarCursor = block.asColumnarCursor()
        assertEquals(2, columnarCursor.size)

        val engine = TradingEngine(defaultGenome(), Mode.SHADOW, initialCapital = 10_000.0)
        val result = simulateTicks(columnarCursor, engine, initialCapital = 10_000.0)

        assertEquals("BTCUSDT", result.symbol)
        assertEquals(2, result.cycles.size)
        assertEquals(2, result.metrics.totalTicks)
    }

    /**
     * Flat equity should produce zero Sharpe and zero total return through
     * the full simulateTicks pipeline, not just hand-built metrics.
     */
    @Test
    fun `simulateTicks flat equity produces zero Sharpe and near-zero return`() = runTest {
        // Flat prices: all at 100.0
        val klines = (0 until 8).map { i ->
            Kline(
                symbol = "FLATUSDT",
                timespan = TimeSpan.Hours1,
                openTime = 1_704_067_200_000L + i * 3_600_000L,
                open = 100.0,
                high = 100.0,
                low = 100.0,
                close = 100.0,
                volume = 10.0,
            )
        }
        val block = KlineBlock.mutable(TimeSpan.Hours1)
        klines.forEach { block.append(it) }
        val cursor = block.seal().asCursor()

        val engine = TradingEngine(defaultGenome(), Mode.SHADOW, initialCapital = 10_000.0)
        val result = simulateTicks(cursor, engine, initialCapital = 10_000.0)

        assertEquals(0.0, result.metrics.sharpeRatio, 0.001, "Flat equity should produce zero Sharpe")
        assertTrue(result.metrics.totalReturn < 0.001, "Flat equity should produce near-zero return")
    }

    /**
     * Verify Sharpe and Sortino are non-zero for a volatile equity curve.
     * Uses a sinusoidal price series that goes up and down, producing
     * both positive and negative returns.
     */
    @Test
    fun `computeBacktestMetrics produces non-zero Sharpe and Sortino for volatile equity curve`() {
        // Build 60-cycle Series with oscillating totalValue (10000 → 10500 → 10000 → ...)
        val n = 60
        val cycles = (0 until n).map { i ->
            val peak = if (i % 20 < 10) 1.0 else 0.0
            val frac = (i % 10) / 10.0
            val totalValue = 10_000.0 + 500.0 * peak * frac
            CycleResult(
                tick = i,
                openTime = 1_704_067_200_000L + i * 3600_000L,
                cashBalance = 0.0,
                holdingsValue = totalValue,
                totalValue = totalValue,
                anyTradesThisCycle = false,
                harvestedAmount = 0.0,
                tradedSymbols = emptyList(),
                rebalanceScheduled = false,
                engineSnapshot = emptyMap(),
            )
        }
        val series: Series<CycleResult> = cycles.toSeries()
        val closePrices: Series<Double> = cycles.map { it.totalValue / 100.0 }.toSeries()

        val metrics = computeBacktestMetrics(series, initialCapital = 10_000.0, closePrices = closePrices)

        assertTrue(metrics.sharpeRatio != 0.0, "Volatile equity should produce non-zero Sharpe")
        assertTrue(metrics.sortinoRatio != 0.0, "Volatile equity should produce non-zero Sortino")
        assertTrue(metrics.maxDrawdown > 0.0, "Oscillating equity should have drawdown > 0")
        assertTrue(metrics.maxDrawdownTicks > 0, "Drawdown should span at least 1 tick")
    }

    /**
     * Multi-generation evolution produces improving fitness.
     *
     * Creates a population of diverse genomes, runs evaluatePopulation over
     * synthetic data for 3 generations, and verifies that the elite genome's
     * fitness is non-decreasing across generations.
     */
    @Test
    fun `multi-generation evolution improves or maintains elite fitness`() = runTest {
        val csv = generatedArchiveCsv(
            symbol = "BTCUSDT",
            rows = 30,
            timespan = TimeSpan.Minutes1,
            startOpenTime = 1_704_067_200_000L,
            assetIndex = 0,
            seed = 42,
        )

        // Create a diverse initial population with different harvest thresholds
        val initialPopulation = (0 until 6).map { i ->
            Genome(mutableMapOf(
                "FLAT_HARVEST_TRIGGER_PERCENT" to 0.01 + i * 0.02,
                "FLAT_TAKE_PERCENT" to 0.05 + i * 0.01,
                "FLAT_SURPLUS_INVEST_PERCENT" to 0.50,
            ))
        }

        var currentPopulation = initialPopulation
        val generationFitnesses = mutableListOf<Double>()

        for (gen in 1..3) {
            val evaluations = evaluatePopulation(
                genomes = currentPopulation,
                csvText = csv,
                symbol = "BTCUSDT",
                timespan = TimeSpan.Minutes1,
                initialCapital = 10_000.0,
            )
            val ranked = rankEvaluationsByFitness(evaluations)
            val eliteFitness = ranked.first().fitness
            generationFitnesses.add(eliteFitness)

            currentPopulation = evolvePopulation(
                evaluations = evaluations,
                mutationDeltas = mapOf("FLAT_HARVEST_TRIGGER_PERCENT" to 0.005),
            )
        }

        // Fitness should be non-decreasing (elite is always preserved)
        for (i in 1 until generationFitnesses.size) {
            assertTrue(
                generationFitnesses[i] >= generationFitnesses[i - 1] - 1e-10,
                "Gen ${i + 1} fitness (${generationFitnesses[i]}) should >= gen $i fitness (${generationFitnesses[i - 1]})"
            )
        }

        // All fitnesses should be finite
        assertTrue(generationFitnesses.all { it.isFinite() })
    }

    @Test
    fun `aggregateReports empty list returns zero fields`() {
        val agg = aggregateReports(emptyList())
        assertEquals(0, agg.runCount)
        assertEquals(0.0, agg.avgTotalReturn)
        assertEquals(0.0, agg.avgSharpeRatio)
        assertEquals(0, agg.totalTicks)
    }

    @Test
    fun `aggregateReports single report passthrough preserves all fields`() {
        val report = BacktestReport(
            symbol = "ETHUSDT",
            initialCapital = 10_000.0,
            finalEquity = 11_500.0,
            totalReturn = 0.15,
            sharpeRatio = 1.2,
            sortinoRatio = 1.8,
            maxDrawdown = 0.05,
            maxDrawdownTicks = 3,
            totalTrades = 7,
            totalHarvested = 320.0,
            totalTicks = 30,
        )
        val agg = aggregateReports(listOf(report))
        assertEquals(1, agg.runCount)
        assertEquals(0.15, agg.avgTotalReturn, 0.001)
        assertEquals(1.2, agg.avgSharpeRatio, 0.001)
        assertEquals(1.8, agg.avgSortinoRatio, 0.001)
        assertEquals(0.05, agg.maxDrawdown, 0.001)
        assertEquals(7, agg.totalTrades)
        assertEquals(320.0, agg.totalHarvested, 0.001)
        assertEquals(0.15, agg.bestReturn, 0.001)
        assertEquals(0.15, agg.worstReturn, 0.001)
    }

    @Test
    fun `aggregateReports two reports averages SharpeSortino and capital-weights return`() {
        val r1 = BacktestReport("A", 10_000.0, 11_000.0, 0.10, 1.0, 1.5, 0.05, 2, 5, 100.0, 20)
        val r2 = BacktestReport("B", 30_000.0, 33_000.0, 0.10, 2.0, 2.5, 0.08, 4, 10, 300.0, 40)
        val agg = aggregateReports(listOf(r1, r2))

        assertEquals(2, agg.runCount)
        // Capital-weighted return: (0.10*10000 + 0.10*30000) / 40000 = 0.10
        assertEquals(0.10, agg.avgTotalReturn, 0.001)
        // Arithmetic mean Sharpe: (1.0 + 2.0) / 2 = 1.5
        assertEquals(1.5, agg.avgSharpeRatio, 0.001)
        assertEquals(2.0, agg.avgSortinoRatio, 0.001)
        // Max drawdown is worst: max(0.05, 0.08) = 0.08
        assertEquals(0.08, agg.maxDrawdown, 0.001)
        assertEquals(15, agg.totalTrades)
        assertEquals(400.0, agg.totalHarvested, 0.001)
        assertEquals(0.10, agg.bestReturn, 0.001)
        assertEquals(0.10, agg.worstReturn, 0.001)
    }

    @Test
    fun `aggregateReports best and worst return pinned correctly across runs`() {
        val r1 = BacktestReport("A", 10_000.0, 13_000.0, 0.30, 1.0, 1.0, 0.02, 1, 3, 50.0, 10)
        val r2 = BacktestReport("B", 10_000.0, 10_500.0, 0.05, 0.5, 0.5, 0.10, 5, 8, 20.0, 10)
        val r3 = BacktestReport("C", 10_000.0, 12_000.0, 0.20, 0.8, 0.8, 0.05, 3, 5, 30.0, 10)
        val agg = aggregateReports(listOf(r1, r2, r3))

        assertEquals(0.30, agg.bestReturn, 0.001)
        assertEquals(0.05, agg.worstReturn, 0.001)
        // Equal capital weighting → simple avg of returns: (0.30+0.05+0.20)/3 ≈ 0.1833
        assertEquals(0.1833, agg.avgTotalReturn, 0.001)
    }

    @Test
    fun `aggregateEvaluations extracts champion genome backing map`() = runTest {
        val csv = generatedArchiveCsv(
            symbol = "BTCUSDT",
            rows = 20,
            timespan = TimeSpan.Minutes1,
            startOpenTime = 1_704_067_200_000L,
            assetIndex = 0,
            seed = 7,
        )
        val genomes = listOf(
            Genome(mutableMapOf("FITNESS_DRAWDOWN_PENALTY" to 0.5)),
            Genome(mutableMapOf("FITNESS_DRAWDOWN_PENALTY" to 2.0)),
        )
        val evals = evaluatePopulation(genomes, csv, "BTCUSDT", TimeSpan.Minutes1, 10_000.0)
        val agg = aggregateEvaluations(evals)

        assertEquals(2, agg.runCount)
        assertNotNull(agg.bestGenome)
        assertTrue(agg.bestGenome.isNotEmpty())
    }

    /**
     * Stochastic determinism: running the same evolution pipeline with the
     * same seed produces identical results. This pins the reproducibility
     * property needed for stochastic back-testing confidence.
     */
    @Test
    fun `evaluatePopulation is deterministic with same inputs`() = runTest {
        val csv = generatedArchiveCsv(
            symbol = "BTCUSDT",
            rows = 20,
            timespan = TimeSpan.Minutes1,
            startOpenTime = 1_704_067_200_000L,
            assetIndex = 0,
            seed = 42,
        )
        val genomes = listOf(
            defaultGenome(),
            Genome(mutableMapOf("HARVEST_TAKE_PERCENT" to 0.30)),
            Genome(mutableMapOf("FITNESS_DRAWDOWN_PENALTY" to 1.5)),
        )

        // Run twice with identical inputs
        val run1 = evaluatePopulation(genomes, csv, "BTCUSDT", TimeSpan.Minutes1, 10_000.0)
        val run2 = evaluatePopulation(genomes, csv, "BTCUSDT", TimeSpan.Minutes1, 10_000.0)

        assertEquals(run1.size, run2.size)
        for (i in run1.indices) {
            assertEquals(run1[i].fitness, run2[i].fitness, 0.0,
                "Fitness for genome $i should be identical across runs")
            assertEquals(run1[i].result.metrics.totalReturn, run2[i].result.metrics.totalReturn, 0.0,
                "totalReturn for genome $i should be identical across runs")
            assertEquals(run1[i].result.metrics.totalTicks, run2[i].result.metrics.totalTicks,
                "totalTicks for genome $i should be identical across runs")
        }
    }

    /**
     * Multi-generation evolution produces consistent champion across runs
     * with the same seed and genomes.
     */
    @Test
    fun `multi-generation evolution is deterministic with same seed`() = runTest {
        val csv = generatedArchiveCsv(
            symbol = "BTCUSDT",
            rows = 25,
            timespan = TimeSpan.Minutes1,
            startOpenTime = 1_704_067_200_000L,
            assetIndex = 0,
            seed = 123,
        )

        suspend fun runEvolution(): List<Double> {
            val genomes = (0 until 4).map { i ->
                Genome(mutableMapOf("HARVEST_TAKE_PERCENT" to (0.10 + i * 0.15)))
            }
            var currentPop = genomes
            val eliteFitnesses = mutableListOf<Double>()
            for (gen in 1..3) {
                val evals = evaluatePopulation(currentPop, csv, "BTCUSDT", TimeSpan.Minutes1, 10_000.0)
                val ranked = rankEvaluationsByFitness(evals)
                eliteFitnesses.add(ranked.first().fitness)
                currentPop = evolvePopulation(evals, mapOf("HARVEST_TAKE_PERCENT" to 0.005))
            }
            return eliteFitnesses
        }

        val fitnesses1 = runEvolution()
        val fitnesses2 = runEvolution()

        assertEquals(fitnesses1.size, fitnesses2.size)
        for (i in fitnesses1.indices) {
            assertEquals(fitnesses1[i], fitnesses2[i], 0.0,
                "Gen ${i + 1} elite fitness should be identical across runs")
        }
    }

    /**
     * Full stochastic back-testing chain:
     *   generated CSV → BinanceVisionKlineFeed → KlineBlock → Cursor
     *     → simulateTicks (with TimeSpan-aware Sharpe) → BacktestResult
     *     → computeBacktestMetrics (hourly annualization) → BacktestReport
     *     → evaluatePopulation → rankEvaluationsByFitness → evolvePopulation
     *     → aggregateReports → BacktestAggregateReport
     *
     * Validates that the entire chain from synthetic data through evolution
     * and aggregation produces consistent, non-trivial results.
     */
    @Test
    fun `full stochastic chain from generated CSV through evolution and aggregation`() = runTest {
        // Generate synthetic hourly archive data for BTC
        val csv = generatedArchiveCsv(
            symbol = "BTCUSDT",
            rows = 50,
            timespan = TimeSpan.Hours1,
            startOpenTime = 1_704_067_200_000L,
            assetIndex = 0,
            seed = 42,
        )

        // Step 1: Parse CSV → KlineBlock → Cursor
        val chars = csv.length j { i: Int -> csv[i] }
        val klines = klinesFromCsv(chars, "BTCUSDT", TimeSpan.Hours1)
        assertTrue(klines.size > 0, "Should parse klines from generated CSV")
        val block = KlineBlock.mutable()
        klines.view.forEach { block.append(it.toKline()) }
        block.seal()
        val cursor = block.asCursor()
        assertEquals(50, cursor.size, "Should have 50 bars")

        // Step 2: Run single-symbol simulateTicks with default genome
        val genome = defaultGenome()
        genome[GenomeParam.MIN_SURPLUS_FOR_HARVEST] = 0.001
        genome[GenomeParam.HARVEST_TAKE_PERCENT] = 0.30
        val engine = TradingEngine(genome, Mode.SHADOW, initialCapital = 10_000.0)
        val result = simulateTicks(cursor, engine, initialCapital = 10_000.0)

        // Verify basic result structure
        assertEquals("BTCUSDT", result.symbol)
        assertEquals(10_000.0, result.initialCapital, 0.001)
        assertEquals(50, result.metrics.totalTicks)
        assertTrue(result.metrics.totalReturn.isFinite(),
            "totalReturn should be finite: ${result.metrics.totalReturn}")

        // Step 3: Compute metrics with hourly annualization
        val hourlyMetrics = computeBacktestMetrics(
            result.cycles, 10_000.0, closesFromCursor(cursor),
            annualizationFactor = TimeSpan.Hours1.annualizationFactor
        )
        assertEquals(result.metrics.totalReturn, hourlyMetrics.totalReturn, 0.001,
            "totalReturn should be same regardless of annualization")
        // Sharpe should differ from daily (if non-zero)
        if (result.metrics.sharpeRatio != 0.0) {
            assertTrue(hourlyMetrics.sharpeRatio != result.metrics.sharpeRatio,
                "Hourly Sharpe should differ from daily")
        }

        // Step 4: Evolution — evaluate population of diverse genomes
        val genomes = (0 until 6).map { i ->
            val g = defaultGenome()
            g[GenomeParam.HARVEST_TAKE_PERCENT] = 0.05 + i * 0.10
            g[GenomeParam.MIN_SURPLUS_FOR_HARVEST] = 0.001 + i * 0.002
            g
        }
        val evaluations = evaluatePopulation(genomes, csv, "BTCUSDT", TimeSpan.Hours1, 10_000.0)
        assertEquals(6, evaluations.size, "Should evaluate all 6 genomes")

        // All evaluations should produce finite fitness
        evaluations.forEach { eval ->
            assertTrue(eval.fitness.isFinite(),
                "Fitness should be finite: ${eval.fitness}")
            assertTrue(eval.result.metrics.totalTicks > 0,
                "Should have ticks in evaluation result")
        }

        // Step 5: Rank and verify ordering
        val ranked = rankEvaluationsByFitness(evaluations)
        for (i in 0 until ranked.size - 1) {
            assertTrue(ranked[i].fitness >= ranked[i + 1].fitness,
                "Ranked evaluations should be in descending fitness order")
        }

        // Step 6: Evolve population
        val nextGen = evolvePopulation(evaluations, mapOf("HARVEST_TAKE_PERCENT" to 0.01))
        assertEquals(6, nextGen.size, "Evolved population should have same size")

        // Step 7: Aggregate reports
        val reports = evaluations.map { it.result.toBacktestReport() }
        val aggregate = aggregateReports(reports)
        assertEquals(6, aggregate.runCount)
        assertTrue(aggregate.totalTicks > 0)
        assertTrue(aggregate.avgTotalReturn.isFinite())

        // Step 8: Aggregate evaluations with champion genome
        val evalAggregate = aggregateEvaluations(evaluations)
        assertEquals(6, evalAggregate.runCount)
        assertNotNull(evalAggregate.bestGenome, "Champion genome should be captured")

        // Step 9: Run second generation and verify improvement potential
        val gen2Evals = evaluatePopulation(nextGen, csv, "BTCUSDT", TimeSpan.Hours1, 10_000.0)
        val gen2Ranked = rankEvaluationsByFitness(gen2Evals)
        // At minimum, gen2 should produce valid results
        assertTrue(gen2Ranked.first().fitness.isFinite(),
            "Gen2 elite should have finite fitness")
    }

    /**
     * Integration test: TimeSpan-aware Sharpe produces correct results
     * for different bar durations on the same equity curve.
     */
    @Test
    fun `TimeSpan-aware Sharpe scales correctly across bar durations`() = runTest {
        // Generate CSV data at 1-minute resolution
        val csv1m = generatedArchiveCsv(
            symbol = "BTCUSDT",
            rows = 30,
            timespan = TimeSpan.Minutes1,
            startOpenTime = 1_704_067_200_000L,
            assetIndex = 0,
            seed = 99,
        )

        val replay = SimulationReplay(
            genome = defaultGenome(),
            mode = Mode.SHADOW,
            initialCapital = 10_000.0,
        )
        val result = replay.replayCsv(csvText = csv1m, symbol = "BTCUSDT", timespan = TimeSpan.Minutes1)
        val closes = closesFromCursor(result.cycles.let { cycles ->
            // Re-derive cursor from the same CSV for close prices
            val chars = csv1m.length j { i: Int -> csv1m[i] }
            val klines = klinesFromCsv(chars, "BTCUSDT", TimeSpan.Minutes1)
            val block = KlineBlock.mutable()
            klines.view.forEach { block.append(it.toKline()) }
            block.seal().asCursor()
        })

        // Compute metrics with different annualization factors
        val sharpe1m = computeBacktestMetrics(
            result.cycles, 10_000.0, closes,
            annualizationFactor = TimeSpan.Minutes1.annualizationFactor
        ).sharpeRatio
        val sharpe1d = computeBacktestMetrics(
            result.cycles, 10_000.0, closes,
            annualizationFactor = TimeSpan.Days1.annualizationFactor
        ).sharpeRatio

        // Both should be finite
        assertTrue(sharpe1m.isFinite(), "1m Sharpe should be finite: $sharpe1m")
        assertTrue(sharpe1d.isFinite(), "1d Sharpe should be finite: $sharpe1d")

        // If there's any volatility, 1m annualization should give different Sharpe than 1d
        if (sharpe1d != 0.0 && sharpe1m != 0.0) {
            val ratio = sharpe1m / sharpe1d
            // sqrt(362880) / sqrt(252) ≈ 37.96
            val expectedRatio = TimeSpan.Minutes1.annualizationFactor / TimeSpan.Days1.annualizationFactor
            assertEquals(expectedRatio, ratio, 0.01,
                "Sharpe ratio should scale with annualization factor")
        }
    }

    // ── HarnessRunResult ↔ BacktestResult bridge tests ──────────────────────

    /**
     * RealtimeHarness.replay → HarnessRunResult.toBacktestResult → BacktestReport.
     * Bridges the two simulation pipelines.
     */
    @Test
    fun `HarnessRunResult toBacktestResult bridges RealtimeHarness into BacktestReport`() = runTest {
        val btc = block("BTC", listOf(100.0, 102.0, 104.0, 106.0))
        val eth = block("ETH", listOf(10.0, 9.8, 10.1, 10.4))
        val harness = RealtimeHarness(
            genome = defaultGenome(),
            initialCapital = 10_000.0,
            stochasticSeed = 11,
            stochasticSpanLength = 2,
        )
        val harnessRun = harness.replay(
            listOf(
                HarnessReplayInput(klineSeriesKey("BTC", "USDT", TimeSpan.Minutes1), btc),
                HarnessReplayInput(klineSeriesKey("ETH", "USDT", TimeSpan.Minutes1), eth),
            )
        )

        val result = harnessRun.toBacktestResult(symbol = "BTC+ETH", initialCapital = 10_000.0)
        assertEquals("BTC+ETH", result.symbol)
        assertEquals(10_000.0, result.initialCapital)
        assertEquals(4, result.cycles.size)
        assertEquals(4, result.metrics.totalTicks)
        assertTrue(result.metrics.totalReturn.isFinite())
        assertTrue(result.metrics.sharpeRatio.isFinite())
        assertTrue(result.metrics.maxDrawdown >= 0.0)

        val report = result.toBacktestReport()
        assertEquals("BTC+ETH", report.symbol)
        assertEquals(10_000.0, report.initialCapital)
        assertEquals(4, report.totalTicks)
        assertTrue(report.finalEquity > 0.0)
    }

    @Test
    fun `HarnessRunResult toBacktestResult empty run produces zero sentinel`() = runTest {
        val emptyBlock = KlineBlock.mutable(TimeSpan.Minutes1).seal()
        val harness = RealtimeHarness(
            genome = defaultGenome(),
            initialCapital = 10_000.0,
        )
        val harnessRun = harness.replay(
            listOf(HarnessReplayInput(klineSeriesKey("BTC", "USDT", TimeSpan.Minutes1), emptyBlock))
        )

        val result = harnessRun.toBacktestResult(initialCapital = 10_000.0)
        assertEquals(0, result.cycles.size)
        assertEquals(0, result.metrics.totalTicks)
        assertEquals(0.0, result.metrics.totalReturn)
    }

    /**
     * HarnessRunResult.toBacktestReport convenience — one call from harness to report.
     */
    @Test
    fun `HarnessRunResult toBacktestReport convenience produces stable summary`() = runTest {
        val btc = block("BTC", listOf(100.0, 101.0, 103.0, 102.0, 105.0))
        val harness = RealtimeHarness(
            genome = defaultGenome(),
            initialCapital = 10_000.0,
        )
        val harnessRun = harness.replay(
            listOf(HarnessReplayInput(klineSeriesKey("BTC", "USDT", TimeSpan.Minutes1), btc))
        )

        val report = harnessRun.toBacktestReport(symbol = "BTCUSDT", initialCapital = 10_000.0)
        assertEquals("BTCUSDT", report.symbol)
        assertEquals(10_000.0, report.initialCapital)
        assertEquals(5, report.totalTicks)
        assertTrue(report.finalEquity > 0.0)
        assertTrue(report.totalReturn.isFinite())
        assertTrue(report.sharpeRatio.isFinite())
    }

    /**
     * GenomeTrainer → HarnessRunResult → BacktestResult → aggregateReports.
     * Full pipeline from training through to aggregate report.
     */
    @Test
    fun `GenomeTrainer output feeds into aggregateReports via HarnessRunResult bridge`() = runTest {
        val key = klineSeriesKey("BTC", "USDT", TimeSpan.Minutes1)
        val trainer = GenomeTrainer(initialCapital = 10_000.0)
        val trainingResult = trainer.trainOneDimensional(
            key = key,
            block = block("BTC", listOf(100.0, 101.0, 103.0, 102.0, 105.0)),
        )

        assertTrue(trainingResult.evaluations.isNotEmpty())

        // Convert each candidate's HarnessRunResult into a BacktestReport
        val reports = trainingResult.evaluations.map { candidate ->
            candidate.result.toBacktestReport(symbol = "BTCUSDT", initialCapital = 10_000.0)
        }

        val aggregate = aggregateReports(reports, bestGenome = trainingResult.champion.backing)
        assertEquals(reports.size, aggregate.runCount)
        assertTrue(aggregate.totalTicks > 0)
        assertTrue(aggregate.avgSharpeRatio.isFinite())
        assertNotNull(aggregate.bestGenome)
    }

    private fun block(base: String, prices: List<Double>): KlineBlock {
        val block = KlineBlock.mutable(TimeSpan.Minutes1)
        prices.forEachIndexed { index, close ->
            val open = if (index == 0) close else prices[index - 1]
            block.append(
                Kline(
                    symbol = "${base}USDT",
                    timespan = TimeSpan.Minutes1,
                    openTime = 1_704_067_200_000L + (index * 60_000L),
                    open = open,
                    high = maxOf(open, close) + 1.0,
                    low = minOf(open, close) - 1.0,
                    close = close,
                    volume = 100.0 + index,
                )
            )
        }
        return block.seal()
    }
}
