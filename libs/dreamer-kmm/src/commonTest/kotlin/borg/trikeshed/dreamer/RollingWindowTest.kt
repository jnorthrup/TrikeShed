package borg.trikeshed.dreamer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * TDD tests for [rollingWindowBacktest] — rolling window back-test
 * for walk-forward validation and time-series cross-validation.
 */
class RollingWindowTest {

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

    // ── windowCount ─────────────────────────────────────────────────────────

    @Test
    fun `windowCount non-overlapping windows`() {
        // 30 bars, window=10, step=10 → 3 windows [0..10), [10..20), [20..30)
        assertEquals(3, windowCount(30, windowSize = 10, stepSize = 10))
    }

    @Test
    fun `windowCount overlapping windows`() {
        // 30 bars, window=10, step=5 → 5 windows [0..10), [5..15), [10..20), [15..25), [20..30)
        assertEquals(5, windowCount(30, windowSize = 10, stepSize = 5))
    }

    @Test
    fun `windowCount data smaller than window returns zero`() {
        assertEquals(0, windowCount(5, windowSize = 10, stepSize = 10))
    }

    @Test
    fun `windowCount exact fit returns one`() {
        assertEquals(1, windowCount(10, windowSize = 10, stepSize = 10))
    }

    @Test
    fun `windowCount remainder less than window is dropped`() {
        // 25 bars, window=10, step=10 → 2 windows [0..10), [10..20), remainder 5 dropped
        assertEquals(2, windowCount(25, windowSize = 10, stepSize = 10))
    }

    // ── rollingWindowBacktest ───────────────────────────────────────────────

    @Test
    fun `rollingWindowBacktest produces correct number of windows`() = runTest {
        val result = rollingWindowBacktest(
            klines = risingKlines(30),
            genome = defaultGenome(),
            initialCapital = 10_000.0,
            windowSize = 10,
            stepSize = 10,
        )

        assertEquals(3, result.windowCount)
        assertEquals(3, result.reports.size)
        assertEquals(10, result.windowSize)
        assertEquals(10, result.stepSize)
    }

    @Test
    fun `rollingWindowBacktest each window has correct tick count`() = runTest {
        val result = rollingWindowBacktest(
            klines = risingKlines(30),
            genome = defaultGenome(),
            initialCapital = 10_000.0,
            windowSize = 10,
            stepSize = 10,
        )

        result.reports.forEach { report ->
            assertEquals(10, report.totalTicks)
        }
    }

    @Test
    fun `rollingWindowBacktest aggregate rolls up all windows`() = runTest {
        val result = rollingWindowBacktest(
            klines = risingKlines(30),
            genome = defaultGenome(),
            initialCapital = 10_000.0,
            windowSize = 10,
            stepSize = 10,
        )

        assertEquals(3, result.aggregate.runCount)
        assertEquals(30, result.aggregate.totalTicks) // 10 * 3
        assertTrue(result.aggregate.avgTotalReturn.isFinite())
        assertTrue(result.aggregate.avgSharpeRatio.isFinite())
    }

    @Test
    fun `rollingWindowBacktest overlapping windows produce more results`() = runTest {
        val result = rollingWindowBacktest(
            klines = risingKlines(30),
            genome = defaultGenome(),
            initialCapital = 10_000.0,
            windowSize = 10,
            stepSize = 5,
        )

        assertEquals(5, result.windowCount)
        assertEquals(5, result.reports.size)
        assertEquals(5, result.aggregate.runCount)
    }

    @Test
    fun `rollingWindowBacktest rising prices produce positive returns`() = runTest {
        val result = rollingWindowBacktest(
            klines = risingKlines(30),
            genome = defaultGenome(),
            initialCapital = 10_000.0,
            windowSize = 10,
            stepSize = 10,
        )

        // Rising prices should produce positive returns in each window
        result.reports.forEach { report ->
            assertTrue(report.totalReturn > -0.01,
                "Window return ${report.totalReturn} should be near-zero or positive for rising prices")
        }
        assertTrue(result.aggregate.avgTotalReturn > -0.01)
    }

    @Test
    fun `rollingWindowBacktest requires non-empty klines`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            rollingWindowBacktest(
                klines = emptyList(),
                genome = defaultGenome(),
                initialCapital = 10_000.0,
                windowSize = 10,
            )
        }
    }

    @Test
    fun `rollingWindowBacktest requires positive windowSize`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            rollingWindowBacktest(
                klines = risingKlines(10),
                genome = defaultGenome(),
                initialCapital = 10_000.0,
                windowSize = 0,
            )
        }
    }

    @Test
    fun `rollingWindowBacktest data smaller than window returns empty result`() = runTest {
        val result = rollingWindowBacktest(
            klines = risingKlines(5),
            genome = defaultGenome(),
            initialCapital = 10_000.0,
            windowSize = 10,
        )

        assertEquals(0, result.windowCount)
        assertTrue(result.reports.isEmpty())
        assertEquals(0, result.aggregate.runCount)
    }
}
