package borg.trikeshed.dreamer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * TDD tests for [walkForwardValidation] — walk-forward validation
 * for out-of-sample backtest evaluation.
 */
class WalkForwardTest {

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

    // ── walkForwardValidation ───────────────────────────────────────────────

    @Test
    fun `walkForward pairs consecutive windows`() = runTest {
        // 40 bars, window=10, step=10 → windows: [0..10), [10..20), [20..30), [30..40)
        // Pairs: (train=0, test=1), (train=1, test=2), (train=2, test=3) → 3 pairs
        val result = walkForwardValidation(
            klines = risingKlines(40),
            genome = defaultGenome(),
            initialCapital = 10_000.0,
            windowSize = 10,
            stepSize = 10,
        )

        assertEquals(3, result.pairCount)
        assertEquals(3, result.trainReports.size)
        assertEquals(3, result.testReports.size)
    }

    @Test
    fun `walkForward each window has correct tick count`() = runTest {
        val result = walkForwardValidation(
            klines = risingKlines(40),
            genome = defaultGenome(),
            initialCapital = 10_000.0,
            windowSize = 10,
            stepSize = 10,
        )

        result.trainReports.forEach { report ->
            assertEquals(10, report.totalTicks)
        }
        result.testReports.forEach { report ->
            assertEquals(10, report.totalTicks)
        }
    }

    @Test
    fun `walkForward aggregates roll up correctly`() = runTest {
        val result = walkForwardValidation(
            klines = risingKlines(40),
            genome = defaultGenome(),
            initialCapital = 10_000.0,
            windowSize = 10,
            stepSize = 10,
        )

        assertEquals(3, result.trainAggregate.runCount)
        assertEquals(3, result.testAggregate.runCount)
        assertEquals(30, result.trainAggregate.totalTicks) // 10 * 3
        assertEquals(30, result.testAggregate.totalTicks)
        assertTrue(result.trainAggregate.avgTotalReturn.isFinite())
        assertTrue(result.testAggregate.avgTotalReturn.isFinite())
    }

    @Test
    fun `walkForward overlapping windows produce more pairs`() = runTest {
        // 30 bars, window=10, step=5 → windows: [0..10), [5..15), [10..20), [15..25), [20..30)
        // Pairs: (0,1), (1,2), (2,3), (3,4) → 4 pairs
        val result = walkForwardValidation(
            klines = risingKlines(30),
            genome = defaultGenome(),
            initialCapital = 10_000.0,
            windowSize = 10,
            stepSize = 5,
        )

        assertEquals(4, result.pairCount)
    }

    @Test
    fun `walkForward with only one window produces zero pairs`() = runTest {
        // 10 bars, window=10 → 1 window → 0 pairs (need at least 2)
        val result = walkForwardValidation(
            klines = risingKlines(10),
            genome = defaultGenome(),
            initialCapital = 10_000.0,
            windowSize = 10,
        )

        assertEquals(0, result.pairCount)
        assertTrue(result.trainReports.isEmpty())
        assertTrue(result.testReports.isEmpty())
        assertEquals(0, result.trainAggregate.runCount)
        assertEquals(0, result.testAggregate.runCount)
    }

    @Test
    fun `walkForward train and test windows are independent`() = runTest {
        // Verify that test window performance is independent of train window.
        // Each window uses a fresh TradingEngine, so no state leaks.
        val result = walkForwardValidation(
            klines = risingKlines(40),
            genome = defaultGenome(),
            initialCapital = 10_000.0,
            windowSize = 10,
            stepSize = 10,
        )

        // Each report should have the same initialCapital (fresh engine)
        result.trainReports.forEach { report ->
            assertEquals(10_000.0, report.initialCapital, 0.001)
        }
        result.testReports.forEach { report ->
            assertEquals(10_000.0, report.initialCapital, 0.001)
        }
    }

    @Test
    fun `walkForward requires non-empty klines`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            walkForwardValidation(
                klines = emptyList(),
                genome = defaultGenome(),
                initialCapital = 10_000.0,
                windowSize = 10,
            )
        }
    }

    @Test
    fun `walkForward requires positive windowSize`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            walkForwardValidation(
                klines = risingKlines(30),
                genome = defaultGenome(),
                initialCapital = 10_000.0,
                windowSize = 0,
            )
        }
    }

    @Test
    fun `walkForward data smaller than window produces zero pairs`() = runTest {
        val result = walkForwardValidation(
            klines = risingKlines(5),
            genome = defaultGenome(),
            initialCapital = 10_000.0,
            windowSize = 10,
        )

        assertEquals(0, result.pairCount)
        assertTrue(result.trainReports.isEmpty())
        assertTrue(result.testReports.isEmpty())
    }
}
