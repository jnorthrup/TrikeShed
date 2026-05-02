package borg.trikeshed.dreamer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TDD tests for [comprehensiveBacktest] — the unified back-test analysis
 * that layers equity metrics, Monte Carlo, and walk-forward on top of
 * simulateTicks.
 */
class ComprehensiveBacktestTest {

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun risingKlines(count: Int = 30): List<Kline> = (0 until count).map { index ->
        val close = 100.0 + index * 0.5
        val open = if (index == 0) close else 100.0 + (index - 1) * 0.5
        Kline(
            symbol = "BTCUSDT",
            timespan = TimeSpan.Minutes1,
            openTime = 1_704_067_200_000L + index * 60_000L,
            open = open,
            high = maxOf(open, close) + 1.0,
            low = minOf(open, close) - 1.0,
            close = close,
            volume = 100.0 + index,
        )
    }

    // ── equityMetrics ───────────────────────────────────────────────────────

    @Test
    fun `equityMetrics extracts from BacktestResult`() = runTest {
        val result = klinesToBacktestResult(risingKlines(20), defaultGenome(), 10_000.0)
        val metrics = result.equityMetrics()

        assertTrue(metrics.winRate >= 0.0 && metrics.winRate <= 1.0,
            "winRate=${metrics.winRate} should be in [0,1]")
        assertTrue(metrics.profitFactor.isFinite() || metrics.profitFactor.isInfinite(),
            "profitFactor should be finite or infinite: ${metrics.profitFactor}")
        assertTrue(metrics.maxConsecutiveLosses >= 0)
        assertTrue(metrics.avgDrawdown >= 0.0)
        assertTrue(metrics.calmarRatio.isFinite() || metrics.calmarRatio.isInfinite(),
            "calmarRatio should be finite or infinite: ${metrics.calmarRatio}")
    }

    // ── comprehensiveBacktest basic ─────────────────────────────────────────

    @Test
    fun `comprehensiveBacktest without Monte Carlo or walk-forward`() = runTest {
        val report = comprehensiveBacktest(
            klines = risingKlines(30),
            genome = defaultGenome(),
            initialCapital = 10_000.0,
        )

        assertEquals("BTCUSDT", report.report.symbol)
        assertEquals(30, report.report.totalTicks)
        assertTrue(report.equityMetrics.winRate >= 0.0)
        assertNull(report.monteCarlo)
        assertNull(report.walkForward)
    }

    @Test
    fun `comprehensiveBacktest with Monte Carlo permutation test`() = runTest {
        val report = comprehensiveBacktest(
            klines = risingKlines(30),
            genome = defaultGenome(),
            initialCapital = 10_000.0,
            monteCarlo = MonteCarloConfig(numPermutations = 5, seed = 42),
        )

        assertNotNull(report.monteCarlo)
        assertEquals(5, report.monteCarlo.permutations)
        assertTrue(report.monteCarlo.originalReturn.isFinite())
        assertTrue(report.monteCarlo.pValue >= 0.0)
        assertNull(report.walkForward)
    }

    @Test
    fun `comprehensiveBacktest with walk-forward validation`() = runTest {
        val report = comprehensiveBacktest(
            klines = risingKlines(40),
            genome = defaultGenome(),
            initialCapital = 10_000.0,
            walkForward = WalkForwardConfig(windowSize = 10, stepSize = 10),
        )

        assertNotNull(report.walkForward)
        assertEquals(10, report.walkForward.windowSize)
        assertEquals(3, report.walkForward.pairCount) // 40/10=4 windows → 3 pairs
        assertTrue(report.walkForward.testAggregate.avgTotalReturn.isFinite())
        assertNull(report.monteCarlo)
    }

    @Test
    fun `comprehensiveBacktest with all analysis dimensions`() = runTest {
        val report = comprehensiveBacktest(
            klines = risingKlines(50),
            genome = defaultGenome(),
            initialCapital = 10_000.0,
            monteCarlo = MonteCarloConfig(numPermutations = 3, seed = 1),
            walkForward = WalkForwardConfig(windowSize = 10, stepSize = 10),
        )

        assertNotNull(report.monteCarlo)
        assertNotNull(report.walkForward)
        assertEquals("BTCUSDT", report.report.symbol)
        assertTrue(report.equityMetrics.winRate >= 0.0)
        assertEquals(3, report.monteCarlo.permutations)
        assertEquals(4, report.walkForward.pairCount) // 50/10=5 windows → 4 pairs
    }

    @Test
    fun `comprehensiveBacktest report matches individual simulateTicks report`() = runTest {
        val klines = risingKlines(20)
        val genome = defaultGenome()

        val standalone = klinesToBacktestResult(klines, genome, 10_000.0).toBacktestReport()
        val comprehensive = comprehensiveBacktest(
            klines = klines,
            genome = genome,
            initialCapital = 10_000.0,
        )

        assertEquals(standalone.symbol, comprehensive.report.symbol)
        assertEquals(standalone.totalTicks, comprehensive.report.totalTicks)
        assertEquals(standalone.initialCapital, comprehensive.report.initialCapital, 0.001)
        assertEquals(standalone.totalReturn, comprehensive.report.totalReturn, 0.001)
    }
}
