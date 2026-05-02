package borg.trikeshed.dreamer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end integration test for [comprehensiveStochasticBacktest].
 *
 * Exercises the full pipeline:
 *   CSV feeds -> stochasticBacktest -> equityMetrics -> evolvedWalkForward
 *   -> ComprehensiveStochasticReport
 */
class ComprehensiveStochasticTest {

    private fun btcFeed(rows: Int = 60, seed: Int = 42): Map<String, String> {
        val key = klineSeriesKey("BTC", "USDT", TimeSpan.Minutes1)
        return mapOf(
            "BTC" to generatedArchiveCsv(
                symbol = key.symbol,
                rows = rows,
                timespan = key.b,
                startOpenTime = 1_704_067_200_000L,
                assetIndex = 0,
                seed = seed,
            )
        )
    }

    private fun defaultConfig(
        rows: Int = 60,
        seed: Int = 42,
    ) = StochasticTrainingConfig(
        bases = listOf("BTC"),
        rowsPerSeries = rows,
        populationSize = 3,
        spanLength = 10,
        initialCapital = 10_000.0,
        seed = seed,
    )

    // -- comprehensiveStochasticBacktest basics ---

    @Test
    fun comprehensiveReportHasEquityMetricsFromChampionReplay() = runTest {
        val feeds = btcFeed(60)
        val config = defaultConfig(60)

        val report = comprehensiveStochasticBacktest(
            feeds = feeds,
            config = config,
            generations = 2,
            walkForwardFolds = 1, // skip walk-forward to isolate equity metrics
        )

        // Should have equity metrics computed from champion replay
        assertNotNull(report.equityMetrics, "equityMetrics should be populated")
        assertTrue(report.equityMetrics!!.winRate >= 0.0, "winRate >= 0")
        assertTrue(report.equityMetrics!!.profitFactor >= 0.0, "profitFactor >= 0")
    }

    @Test
    fun comprehensiveReportWithEmptyFeedsHasNoEquityMetrics() = runTest {
        val config = defaultConfig()

        val report = comprehensiveStochasticBacktest(
            feeds = emptyMap(),
            config = config,
            generations = 2,
        )

        assertNull(report.equityMetrics)
        assertNull(report.evolvedWalkForward)
        assertEquals(0, report.snapshots.size)
    }

    @Test
    fun comprehensiveReportSnapshotHistoryMatchesGenerationCount() = runTest {
        val feeds = btcFeed(40)
        val config = defaultConfig(40)

        val report = comprehensiveStochasticBacktest(
            feeds = feeds,
            config = config,
            generations = 3,
            walkForwardFolds = 1,
        )

        // Should have exactly 3 snapshots (one per generation)
        assertEquals(3, report.snapshots.size)
    }

    // -- evolved walk-forward integration ---

    @Test
    fun comprehensiveReportIncludesEvolvedWalkForwardWhenFoldsGe2() = runTest {
        val rows = 60
        val feeds = btcFeed(rows)
        val config = defaultConfig(rows)

        val report = comprehensiveStochasticBacktest(
            feeds = feeds,
            config = config,
            generations = 2,
            walkForwardFolds = 3,
        )

        // Evolved walk-forward should be populated
        assertNotNull(report.evolvedWalkForward, "evolvedWalkForward should be populated with 3 folds")
        val wf = report.evolvedWalkForward!!
        assertTrue(wf.pairCount > 0, "should have at least 1 train/test pair")
    }

    @Test
    fun evolvedWalkForwardTrainReportsHaveTicks() = runTest {
        // Use a seed that generates upward-trending data
        val rows = 80
        val feeds = btcFeed(rows, seed = 7)
        val config = defaultConfig(rows, seed = 7)

        val report = comprehensiveStochasticBacktest(
            feeds = feeds,
            config = config,
            generations = 3,
            walkForwardFolds = 4,
        )

        assertNotNull(report.evolvedWalkForward)
        val wf = report.evolvedWalkForward!!
        // Train reports should exist and have positive ticks
        assertTrue(wf.trainReports.isNotEmpty())
        wf.trainReports.forEach { tr ->
            assertTrue(tr.totalTicks > 0, "train report should have ticks")
        }
    }

    // -- champion quality checks ---

    @Test
    fun championGenomeIsDeterministicForSameSeed() = runTest {
        val feeds = btcFeed(50)
        val config = defaultConfig(50, seed = 999)

        val report1 = comprehensiveStochasticBacktest(
            feeds = feeds,
            config = config,
            generations = 2,
            walkForwardFolds = 1,
        )

        val report2 = comprehensiveStochasticBacktest(
            feeds = feeds,
            config = config,
            generations = 2,
            walkForwardFolds = 1,
        )

        // Same seed produces same champion genome
        assertEquals(
            report1.championGenome.backing.toList(),
            report2.championGenome.backing.toList(),
        )
    }

    @Test
    fun championTotalValueIsPositive() = runTest {
        val feeds = btcFeed(40)
        val config = defaultConfig(40)

        val report = comprehensiveStochasticBacktest(
            feeds = feeds,
            config = config,
            generations = 2,
            walkForwardFolds = 1,
        )

        assertTrue(report.championTotalValue > 0.0, "champion total value must be positive")
    }

    // -- validation report ---

    @Test
    fun validationReportIsPopulatedFromBaseStochasticBacktest() = runTest {
        val feeds = btcFeed(40)
        val config = defaultConfig(40)

        val report = comprehensiveStochasticBacktest(
            feeds = feeds,
            config = config,
            generations = 2,
            walkForwardFolds = 1,
        )

        // comprehensiveStochasticBacktest calls stochasticBacktest with validateChampion=true
        assertNotNull(report.validationReport)
        assertEquals(config.initialCapital, report.validationReport!!.initialCapital)
    }

    // -- equity metrics detail ---

    @Test
    fun equityMetricsCalmarRatioIsFinite() = runTest {
        val feeds = btcFeed(50)
        val config = defaultConfig(50)

        val report = comprehensiveStochasticBacktest(
            feeds = feeds,
            config = config,
            generations = 2,
            walkForwardFolds = 1,
        )

        assertNotNull(report.equityMetrics)
        // Calmar can be +Infinity when no drawdown occurs, which is valid
        val calmar = report.equityMetrics!!.calmarRatio
        assertTrue(calmar.isFinite() || calmar == Double.POSITIVE_INFINITY,
            "Calmar ratio should be finite or +Infinity: $calmar")
    }

    @Test
    fun equityMetricsMaxConsecutiveLossesIsNonNegative() = runTest {
        val feeds = btcFeed(50)
        val config = defaultConfig(50)

        val report = comprehensiveStochasticBacktest(
            feeds = feeds,
            config = config,
            generations = 2,
            walkForwardFolds = 1,
        )

        assertNotNull(report.equityMetrics)
        assertTrue(report.equityMetrics!!.maxConsecutiveLosses >= 0)
    }
}
