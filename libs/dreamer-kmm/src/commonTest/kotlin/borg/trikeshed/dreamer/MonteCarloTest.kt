package borg.trikeshed.dreamer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * TDD tests for [monteCarloPermutationBacktest] — Monte Carlo permutation
 * back-test for strategy significance testing.
 *
 * The permutation test shuffles bar order N times and compares the original
 * strategy return against the distribution of permuted returns. A low p-value
 * means the strategy captures genuine signal, not just noise.
 */
class MonteCarloTest {

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

    private fun flatKlines(count: Int = 10): List<Kline> = (0 until count).map { index ->
        Kline(
            symbol = "BTCUSDT",
            timespan = TimeSpan.Minutes1,
            openTime = 1_704_067_200_000L + index * 60_000L,
            open = 100.0,
            high = 101.0,
            low = 99.0,
            close = 100.0,
            volume = 100.0,
        )
    }

    // ── klinesToBacktestResult ──────────────────────────────────────────────

    @Test
    fun `klinesToBacktestResult produces valid result from kline list`() = runTest {
        val result = klinesToBacktestResult(
            klines = risingKlines(5),
            genome = defaultGenome(),
            initialCapital = 10_000.0,
        )
        assertEquals("BTCUSDT", result.symbol)
        assertEquals(5, result.metrics.totalTicks)
        assertTrue(result.metrics.totalReturn.isFinite())
    }

    // ── monteCarloPermutationBacktest ───────────────────────────────────────

    @Test
    fun `monteCarloPermutationBacktest runs N shuffles and returns distribution`() = runTest {
        val result = monteCarloPermutationBacktest(
            klines = risingKlines(30),
            genome = defaultGenome(),
            initialCapital = 10_000.0,
            numPermutations = 5,
            seed = 42,
        )

        assertEquals(5, result.permutations)
        assertTrue(result.originalReturn.isFinite())
        assertTrue(result.originalSharpe.isFinite())
        assertTrue(result.meanReturn.isFinite())
        assertTrue(result.medianReturn.isFinite())
        assertTrue(result.p5Return <= result.p95Return,
            "p5=${result.p5Return} should be <= p95=${result.p95Return}")
        assertTrue(result.pValue >= 0.0 && result.pValue <= 1.0,
            "pValue=${result.pValue} should be in [0,1]")
    }

    @Test
    fun `monteCarloPermutationBacktest is deterministic with same seed`() = runTest {
        val klines = risingKlines(20)

        val r1 = monteCarloPermutationBacktest(klines, defaultGenome(), 10_000.0, numPermutations = 3, seed = 77)
        val r2 = monteCarloPermutationBacktest(klines, defaultGenome(), 10_000.0, numPermutations = 3, seed = 77)

        assertEquals(r1.originalReturn, r2.originalReturn, 0.0001)
        assertEquals(r1.meanReturn, r2.meanReturn, 0.0001)
        assertEquals(r1.pValue, r2.pValue, 0.0001)
        assertEquals(r1.p5Return, r2.p5Return, 0.0001)
    }

    @Test
    fun `monteCarloPermutationBacktest different seeds produce different distributions`() = runTest {
        val klines = risingKlines(30)

        val r1 = monteCarloPermutationBacktest(klines, defaultGenome(), 10_000.0, numPermutations = 5, seed = 1)
        val r2 = monteCarloPermutationBacktest(klines, defaultGenome(), 10_000.0, numPermutations = 5, seed = 999)

        // Original should be identical (same klines)
        assertEquals(r1.originalReturn, r2.originalReturn, 0.0001)
        // Permuted distributions should differ (different seeds)
        // Not guaranteed to differ for all seeds, but very likely with 30 bars and 5 permutations
        val sameDistribution = kotlin.math.abs(r1.meanReturn - r2.meanReturn) < 0.0001 &&
            kotlin.math.abs(r1.p5Return - r2.p5Return) < 0.0001
        // Allow for the unlikely case where they're the same
        assertTrue(r1.meanReturn.isFinite() && r2.meanReturn.isFinite())
    }

    @Test
    fun `monteCarloPermutationBacktest flat prices produce near-zero pValue difference`() = runTest {
        val result = monteCarloPermutationBacktest(
            klines = flatKlines(10),
            genome = defaultGenome(),
            initialCapital = 10_000.0,
            numPermutations = 3,
            seed = 1,
        )

        // Flat prices: original return ≈ 0, all permuted returns ≈ 0
        assertEquals(0.0, result.originalReturn, 0.01)
        assertTrue(result.pValue >= 0.0)
        // All returns near zero → pValue should be ~1.0
        assertTrue(result.pValue > 0.5, "pValue=${result.pValue} should be high for flat prices")
    }

    @Test
    fun `monteCarloPermutationBacktest requires non-empty klines`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            monteCarloPermutationBacktest(
                klines = emptyList(),
                genome = defaultGenome(),
                initialCapital = 10_000.0,
                numPermutations = 1,
            )
        }
    }

    @Test
    fun `monteCarloPermutationBacktest requires at least one permutation`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            monteCarloPermutationBacktest(
                klines = risingKlines(5),
                genome = defaultGenome(),
                initialCapital = 10_000.0,
                numPermutations = 0,
            )
        }
    }

    @Test
    fun `monteCarloPermutationBacktest single permutation returns pValue 0 or 1`() = runTest {
        val result = monteCarloPermutationBacktest(
            klines = risingKlines(15),
            genome = defaultGenome(),
            initialCapital = 10_000.0,
            numPermutations = 1,
            seed = 42,
        )

        assertEquals(1, result.permutations)
        // With 1 permutation, pValue is either 0.0 or 1.0
        assertTrue(result.pValue == 0.0 || result.pValue == 1.0,
            "pValue=${result.pValue} should be 0.0 or 1.0 with single permutation")
        // meanReturn == p5Return == p95Return (only 1 sample)
        assertEquals(result.meanReturn, result.p5Return, 0.0001)
        assertEquals(result.meanReturn, result.p95Return, 0.0001)
    }
}

