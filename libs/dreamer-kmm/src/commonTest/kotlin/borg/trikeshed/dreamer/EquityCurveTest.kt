package borg.trikeshed.dreamer

import borg.trikeshed.lib.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TDD tests for equity curve analysis utilities.
 */
class EquityCurveTest {

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun cycles(vararg values: Double): Series<CycleResult> =
        values.mapIndexed { index, totalValue ->
            CycleResult(
                tick = index,
                openTime = 1_704_067_200_000L + index * 60_000L,
                cashBalance = totalValue,
                holdingsValue = 0.0,
                totalValue = totalValue,
                anyTradesThisCycle = false,
                harvestedAmount = 0.0,
                tradedSymbols = emptyList(),
                rebalanceScheduled = false,
                engineSnapshot = emptyMap(),
            )
        }.toSeries()

    private fun runSimAndgetCycles(prices: List<Double>): Series<CycleResult> {
        val klines = prices.mapIndexed { index, close ->
            val open = if (index == 0) close else prices[index - 1]
            Kline(
                symbol = "BTCUSDT",
                timespan = TimeSpan.Minutes1,
                openTime = 1_704_067_200_000L + index * 60_000L,
                open = open,
                high = maxOf(open, close) + 1.0,
                low = minOf(open, close) - 1.0,
                close = close,
                volume = 100.0,
            )
        }
        val block = KlineBlock.mutable(TimeSpan.Minutes1)
        klines.forEach { block.append(it) }
        block.seal()
        val engine = TradingEngine(defaultGenome(), Mode.SHADOW, initialCapital = 10_000.0)
        // simulateTicks is suspend, so we can't call it here directly.
        // Use a simple manual cycle builder instead.
        return cycles(*prices.toDoubleArray())
    }

    // ── equityCurve ─────────────────────────────────────────────────────────

    @Test
    fun `equityCurve extracts totalValue series`() {
        val c = cycles(100.0, 105.0, 110.0, 108.0)
        val curve = c.equityCurve()

        assertEquals(4, curve.size)
        assertEquals(100.0, curve.view[0], 0.001)
        assertEquals(105.0, curve.view[1], 0.001)
        assertEquals(110.0, curve.view[2], 0.001)
        assertEquals(108.0, curve.view[3], 0.001)
    }

    // ── barReturns ──────────────────────────────────────────────────────────

    @Test
    fun `barReturns computes percentage changes`() {
        val c = cycles(100.0, 105.0, 110.0, 99.0)
        val returns = c.barReturns()

        assertEquals(3, returns.size)
        assertEquals(0.05, returns.view[0], 0.001)   // (105-100)/100
        assertEquals(0.0476, returns.view[1], 0.001) // (110-105)/105
        assertEquals(-0.10, returns.view[2], 0.001)  // (99-110)/110
    }

    @Test
    fun `barReturns empty for single element`() {
        val c = cycles(100.0)
        val returns = c.barReturns()
        assertEquals(0, returns.size)
    }

    // ── winRate ─────────────────────────────────────────────────────────────

    @Test
    fun `winRate computes fraction of positive bars`() {
        // 100 → 105 (+), 105 → 110 (+), 110 → 99 (-) → winRate = 2/3
        val c = cycles(100.0, 105.0, 110.0, 99.0)
        val wr = c.winRate()
        assertEquals(2.0 / 3.0, wr, 0.001)
    }

    @Test
    fun `winRate zero for declining curve`() {
        val c = cycles(100.0, 99.0, 98.0, 97.0)
        val wr = c.winRate()
        assertEquals(0.0, wr, 0.001)
    }

    @Test
    fun `winRate one for rising curve`() {
        val c = cycles(100.0, 101.0, 102.0)
        val wr = c.winRate()
        assertEquals(1.0, wr, 0.001)
    }

    // ── profitFactor ────────────────────────────────────────────────────────

    @Test
    fun `profitFactor gross profit over gross loss`() {
        // Returns: +0.05, +0.0476, -0.10
        // Gross profit = 0.05 + 0.0476 = 0.0976
        // Gross loss = 0.10
        // PF = 0.976
        val c = cycles(100.0, 105.0, 110.0, 99.0)
        val pf = c.profitFactor()
        assertEquals(0.976, pf, 0.01)
    }

    @Test
    fun `profitFactor infinity for all wins`() {
        val c = cycles(100.0, 101.0, 102.0)
        val pf = c.profitFactor()
        assertTrue(pf.isInfinite() || pf > 100.0, "PF should be very large or infinite: $pf")
    }

    // ── maxConsecutiveLosses ────────────────────────────────────────────────

    @Test
    fun `maxConsecutiveLosses finds longest losing streak`() {
        // Returns: +, +, -, -, +, -, -, -
        val c = cycles(100.0, 101.0, 102.0, 100.0, 98.0, 99.0, 97.0, 95.0, 93.0)
        val streak = c.maxConsecutiveLosses()
        assertEquals(3, streak) // bars 6-8: 99→97→95→93
    }

    @Test
    fun `maxConsecutiveLosses zero for rising curve`() {
        val c = cycles(100.0, 101.0, 102.0)
        assertEquals(0, c.maxConsecutiveLosses())
    }

    // ── avgDrawdown ─────────────────────────────────────────────────────────

    @Test
    fun `avgDrawdown computes mean drawdown`() {
        // Equity: 100, 110, 105, 108, 100
        // Peak=100, Peak=110, DD=(110-105)/110=0.045, DD=(110-108)/110=0.018, DD=(110-100)/110=0.091
        val c = cycles(100.0, 110.0, 105.0, 108.0, 100.0)
        val avgDD = c.avgDrawdown()
        assertTrue(avgDD > 0.0, "avgDD should be positive: $avgDD")
        assertTrue(avgDD < 0.15, "avgDD should be reasonable: $avgDD")
    }

    @Test
    fun `avgDrawdown zero for monotonically rising curve`() {
        val c = cycles(100.0, 101.0, 102.0, 103.0)
        assertEquals(0.0, c.avgDrawdown(), 0.001)
    }

    // ── maxDrawdownFromCurve ────────────────────────────────────────────────

    @Test
    fun `maxDrawdownFromCurve computes peak-to-trough`() {
        // Equity: 100, 110, 105, 108, 100
        // Peak=110, trough=100 → DD = 10/110 = 0.0909
        val c = cycles(100.0, 110.0, 105.0, 108.0, 100.0)
        val mdd = c.maxDrawdownFromCurve()
        assertEquals(0.0909, mdd, 0.001)
    }

    @Test
    fun `maxDrawdownFromCurve zero for rising curve`() {
        val c = cycles(100.0, 101.0, 102.0)
        assertEquals(0.0, c.maxDrawdownFromCurve(), 0.001)
    }

    // ── calmarRatio ─────────────────────────────────────────────────────────

    @Test
    fun `calmarRatio return over max drawdown`() {
        // initial=100, final=100, return=0%, maxDD=0.0909 → calmar = 0/0.0909 = 0
        val c = cycles(100.0, 110.0, 105.0, 108.0, 100.0)
        val calmar = c.calmarRatio(initialCapital = 100.0)
        // totalReturn = (100-100)/100 = 0.0
        assertEquals(0.0, calmar, 0.001)
    }

    @Test
    fun `calmarRatio positive return with drawdown`() {
        // initial=100, final=108, return=8%, maxDD=0.0909
        val c = cycles(100.0, 110.0, 105.0, 108.0)
        val calmar = c.calmarRatio(initialCapital = 100.0)
        // totalReturn = (108-100)/100 = 0.08
        // maxDD = (110-105)/110 = 0.04545
        // calmar = 0.08 / 0.04545 = 1.76
        assertTrue(calmar > 0.0, "calmar should be positive: $calmar")
    }
}
