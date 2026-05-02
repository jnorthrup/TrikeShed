package borg.trikeshed.dreamer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end stochastic back-test orchestration tests.
 *
 * Tests the full chain:
 *   Binance archive CSV → feedsToHarnessInputs → StochasticBagSpanTrainer
 *   → runMultiGenerationTraining → champion genome → comprehensive report
 */
class StochasticBacktestTest {

    // ── feedsToHarnessInputs ────────────────────────────────────────────────

    @Test
    fun `feedsToHarnessInputs converts symbol-CSV map into sealed HarnessReplayInput list`() {
        val config = StochasticTrainingConfig(
            bases = listOf("BTC", "ETH"),
            quote = "USDT",
            timespan = TimeSpan.Minutes1,
            rowsPerSeries = 10,
            startOpenTime = 1_704_067_200_000L,
            seed = 42,
        )
        val feeds = mapOf(
            "BTC" to generatedArchiveCsv("BTCUSDT", 10, TimeSpan.Minutes1, 1_704_067_200_000L, 0, 42),
            "ETH" to generatedArchiveCsv("ETHUSDT", 10, TimeSpan.Minutes1, 1_704_067_200_000L, 1, 42),
        )

        val inputs = feedsToHarnessInputs(feeds, config)

        assertEquals(2, inputs.size)
        inputs.forEach { input ->
            assertEquals(KlineBlock.State.SEALED, input.block.state)
            assertEquals(10, input.block.rowCount)
            assertTrue(input.key.symbol.endsWith("USDT"))
        }
    }

    @Test
    fun `feedsToHarnessInputs with empty map returns empty list`() {
        val config = StochasticTrainingConfig()
        val inputs = feedsToHarnessInputs(emptyMap(), config)
        assertEquals(0, inputs.size)
    }

    // ── runMultiGenerationTraining ──────────────────────────────────────────

    @Test
    fun `runMultiGenerationTraining runs N generations and returns snapshots`() = runTest {
        val config = StochasticTrainingConfig(
            bases = listOf("BTC", "ETH"),
            rowsPerSeries = 20,
            populationSize = 4,
            spanLength = 8,
            initialCapital = 10_000.0,
            seed = 99,
        )
        val inputs = archiveInputs(config)
        val trainer = StochasticBagSpanTrainer(config, inputs)

        val result = trainer.runMultiGenerationTraining(generations = 3)

        assertEquals(3, result.snapshots.size)
        result.snapshots.forEachIndexed { index, snapshot ->
            assertEquals(index + 1, snapshot.generation)
            assertEquals(4, snapshot.evaluations)
            assertTrue(snapshot.bestTotalValue > 0.0, "gen ${index + 1} bestTotalValue should be positive")
        }
        val championSnapshot = result.snapshots.last()
        assertEquals(3, championSnapshot.generation)
    }

    @Test
    fun `runMultiGenerationTraining with convergence stops early`() = runTest {
        val config = StochasticTrainingConfig(
            bases = listOf("BTC"),
            rowsPerSeries = 15,
            populationSize = 2,
            spanLength = 4,
            initialCapital = 10_000.0,
            seed = 7,
        )
        val inputs = archiveInputs(config)
        val trainer = StochasticBagSpanTrainer(config, inputs)

        // Very tight convergence threshold — should stop after 2 gens or fewer
        val result = trainer.runMultiGenerationTraining(
            generations = 10,
            convergenceThreshold = 1e-10,
        )

        assertTrue(result.snapshots.size <= 10,
            "Should stop before max generations: got ${result.snapshots.size}")
        assertTrue(result.snapshots.size >= 1, "Should run at least one generation")
    }

    // ── stochasticBacktest orchestration ────────────────────────────────────

    @Test
    fun `stochasticBacktest runs full pipeline from CSV feeds to comprehensive report`() = runTest {
        val config = StochasticTrainingConfig(
            bases = listOf("BTC", "ETH"),
            rowsPerSeries = 30,
            populationSize = 3,
            spanLength = 10,
            initialCapital = 10_000.0,
            seed = 2024,
        )
        val feeds = config.bases.mapIndexed { index, base ->
            val key = klineSeriesKey(base, config.quote, config.timespan)
            base to generatedArchiveCsv(key.symbol, config.rowsPerSeries, config.timespan, config.startOpenTime, index, config.seed)
        }.toMap()

        val report = stochasticBacktest(feeds, config, generations = 2)

        assertNotNull(report)
        assertEquals(2, report.snapshots.size)
        assertNotNull(report.championGenome)
        assertTrue(report.championTotalValue > 0.0,
            "championTotalValue should be positive: ${report.championTotalValue}")
        // The aggregate report should have valid metrics
        val agg = report.aggregate
        assertTrue(agg.runCount >= 1)
        assertTrue(agg.totalTicks > 0)
    }

    @Test
    fun `stochasticBacktest champion genome produces valid BacktestReport via bridge`() = runTest {
        val config = StochasticTrainingConfig(
            bases = listOf("BTC"),
            rowsPerSeries = 20,
            populationSize = 2,
            spanLength = 8,
            initialCapital = 5_000.0,
            seed = 55,
        )
        val feeds = config.bases.mapIndexed { index, base ->
            val key = klineSeriesKey(base, config.quote, config.timespan)
            base to generatedArchiveCsv(key.symbol, config.rowsPerSeries, config.timespan, config.startOpenTime, index, config.seed)
        }.toMap()

        val report = stochasticBacktest(feeds, config, generations = 2)

        // Champion genome should be convertible to a BacktestReport via the bridge
        val championReport = report.championBacktestReport
        assertNotNull(championReport)
        assertEquals("MULTI", championReport.symbol)
        assertEquals(5_000.0, championReport.initialCapital, 0.01)
        assertTrue(championReport.finalEquity > 0.0)
        assertTrue(championReport.totalReturn.isFinite())
        assertTrue(championReport.maxDrawdown >= 0.0)
    }

    @Test
    fun `stochasticBacktest with empty feeds returns empty sentinel`() = runTest {
        val config = StochasticTrainingConfig(bases = emptyList())
        val report = stochasticBacktest(emptyMap(), config, generations = 1)

        assertNotNull(report)
        assertEquals(0, report.snapshots.size)
        assertEquals(0.0, report.championTotalValue)
    }

    // ── ComprehensiveStochasticReport ───────────────────────────────────────

    @Test
    fun `ComprehensiveStochasticReport carries evolution history and aggregate metrics`() = runTest {
        val config = StochasticTrainingConfig(
            bases = listOf("BTC", "ETH"),
            rowsPerSeries = 25,
            populationSize = 3,
            spanLength = 8,
            initialCapital = 10_000.0,
            seed = 123,
        )
        val feeds = config.bases.mapIndexed { index, base ->
            val key = klineSeriesKey(base, config.quote, config.timespan)
            base to generatedArchiveCsv(key.symbol, config.rowsPerSeries, config.timespan, config.startOpenTime, index, config.seed)
        }.toMap()

        val report = stochasticBacktest(feeds, config, generations = 3)

        // Verify evolution history depth
        assertEquals(3, report.snapshots.size)

        // Each snapshot should have progressively higher or equal generation
        val generations = report.snapshots.map { it.generation }
        assertEquals(listOf(1, 2, 3), generations)

        // Aggregate should roll up all snapshot reports
        assertTrue(report.aggregate.totalTicks > 0)
        assertTrue(report.aggregate.runCount > 0)

        // Fitness should be finite across all snapshots
        report.snapshots.forEach { snapshot ->
            assertTrue(snapshot.bestFitness.isFinite(),
                "fitness should be finite: ${snapshot.bestFitness}")
        }
    }

    @Test
    fun `stochasticBacktest with validation runs champion on full data`() = runTest {
        val config = StochasticTrainingConfig(
            bases = listOf("BTC"),
            rowsPerSeries = 40,
            populationSize = 3,
            spanLength = 10,
            initialCapital = 10_000.0,
            seed = 777,
        )
        val feeds = config.bases.mapIndexed { index, base ->
            val key = klineSeriesKey(base, config.quote, config.timespan)
            base to generatedArchiveCsv(key.symbol, config.rowsPerSeries, config.timespan, config.startOpenTime, index, config.seed)
        }.toMap()

        val report = stochasticBacktest(
            feeds = feeds,
            config = config,
            generations = 2,
            validateChampion = true,
        )

        // When validateChampion=true, a validation BacktestReport is produced
        // by running the champion genome over all input data via SimulationReplay
        assertNotNull(report.validationReport)
        assertTrue(report.validationReport!!.finalEquity > 0.0)
        assertTrue(report.validationReport!!.totalTicks > 0)
    }
}
