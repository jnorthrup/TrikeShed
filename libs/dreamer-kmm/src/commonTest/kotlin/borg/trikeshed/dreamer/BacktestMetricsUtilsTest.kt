package borg.trikeshed.dreamer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [BacktestMetricsUtils] — fitness() and maxDrawdown()
 * on [HarnessRunResult].
 */
class BacktestMetricsUtilsTest {

    // ── maxDrawdown ──────────────────────────────────────────────────────────

    @Test
    fun `maxDrawdown zero when equity monotonically increases`() {
        val result = harnessRunResult(
            totalValues = listOf(10_000.0, 10_500.0, 11_000.0, 11_800.0),
        )
        val dd = result.maxDrawdown(10_000.0)
        assertEquals(0.0, dd, 0.001)
    }

    @Test
    fun `maxDrawdown captures single drawdown`() {
        // 10000 → 11000 (peak) → 9900 (trough) → 10500
        val result = harnessRunResult(
            totalValues = listOf(10_000.0, 11_000.0, 9_900.0, 10_500.0),
        )
        val dd = result.maxDrawdown(10_000.0)
        // Drawdown = (11000 - 9900) / 11000 = 0.1
        assertEquals(0.1, dd, 0.001)
    }

    @Test
    fun `maxDrawdown picks the worst of multiple drawdowns`() {
        // Peak 1: 10000 → 8000 = 20% DD
        // Peak 2: 10500 → 9000 = 14.3% DD
        val result = harnessRunResult(
            totalValues = listOf(10_000.0, 8_000.0, 10_500.0, 9_000.0),
        )
        val dd = result.maxDrawdown(10_000.0)
        // (10000-8000)/10000 = 0.20
        assertEquals(0.20, dd, 0.001)
    }

    @Test
    fun `maxDrawdown zero for flat equity`() {
        val result = harnessRunResult(
            totalValues = listOf(10_000.0, 10_000.0, 10_000.0),
        )
        assertEquals(0.0, result.maxDrawdown(10_000.0), 0.001)
    }

    @Test
    fun `maxDrawdown zero for empty cycles`() {
        val result = HarnessRunResult(emptyList(), 10_000.0, 10_000.0)
        assertEquals(0.0, result.maxDrawdown(10_000.0), 0.001)
    }

    // ── fitness ──────────────────────────────────────────────────────────────

    @Test
    fun `fitness positive for profitable run with no drawdown`() {
        val result = harnessRunResult(
            totalValues = listOf(10_000.0, 10_500.0, 11_000.0),
            anyTrades = listOf(true, true),
        )
        val fitness = result.fitness(10_000.0, defaultGenome())
        // totalReturn = (11000 - 10000) / 10000 = 0.1
        // tradeScore = 2 * 0.001 = 0.002
        // drawdown = 0
        // fitness = 0.1 + 0.002 - 0 = 0.102
        assertTrue(fitness > 0.1)
        assertTrue(fitness.isFinite())
    }

    @Test
    fun `fitness negative for large drawdown`() {
        val result = harnessRunResult(
            totalValues = listOf(10_000.0, 6_000.0), // 40% drawdown
        )
        val fitness = result.fitness(10_000.0, defaultGenome())
        // totalReturn = (6000 - 10000) / 10000 = -0.4
        // drawdown = 0.4, penalty = 1.0 (default)
        // fitness = -0.4 + 0 - (0.4 * 1.0) = -0.8
        assertTrue(fitness < 0.0)
        assertTrue(fitness.isFinite())
    }

    @Test
    fun `fitness zero capital returns zero`() {
        val result = harnessRunResult(
            totalValues = listOf(0.0, 0.0),
        )
        val fitness = result.fitness(0.0, defaultGenome())
        assertEquals(0.0, fitness, 0.001)
    }

    @Test
    fun `fitness respects genome drawdown penalty`() {
        val result = harnessRunResult(
            totalValues = listOf(10_000.0, 8_000.0), // 20% drawdown
        )
        val noPenalty = defaultGenome().also { it["FITNESS_DRAWDOWN_PENALTY"] = 0.0 }
        val heavyPenalty = defaultGenome().also { it["FITNESS_DRAWDOWN_PENALTY"] = 5.0 }

        val fitNoPenalty = result.fitness(10_000.0, noPenalty)
        val fitHeavy = result.fitness(10_000.0, heavyPenalty)

        assertTrue(fitHeavy < fitNoPenalty, "Heavy penalty should reduce fitness")
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun harnessRunResult(
        totalValues: List<Double>,
        anyTrades: List<Boolean> = List((totalValues.size - 1).coerceAtLeast(0)) { false },
    ): HarnessRunResult {
        val cycles = totalValues.dropLast(1).zip(totalValues.drop(1)).mapIndexed { index, (_, totalValue) ->
            HarnessCycle(
                frame = HarnessFrame(
                    tick = index,
                    openTime = 1_704_067_200_000L + index * 60_000L,
                    rows = emptyList(),
                    bag = StochasticBagSelection(emptyList(), emptyList()),
                ),
                result = EngineResult(anyTradesThisCycle = anyTrades.getOrElse(index) { false }, harvestedAmount = 0.0),
                totalValue = totalValue,
            )
        }
        return HarnessRunResult(
            cycles = cycles,
            finalCash = 0.0,
            finalTotalValue = totalValues.last(),
        )
    }
}
