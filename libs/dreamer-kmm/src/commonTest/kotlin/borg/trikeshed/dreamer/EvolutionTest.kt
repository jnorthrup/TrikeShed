package borg.trikeshed.dreamer

import borg.trikeshed.couch.kline.TimeSpan
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EvolutionTest {
    @Test
    fun `evaluatePopulation replays each genome over archive csv`() = runTest {
        val csv = """
            open_time,open,high,low,close,volume,close_time,quote_asset_volume,number_of_trades,taker_buy_base_volume,taker_buy_quote_volume,ignore
            1704067200000,100.0,102.0,99.0,101.0,10.0,1704070799999,1010.0,12,5.0,505.0,0
            1704070800000,101.0,104.0,100.0,103.0,12.0,1704074399999,1236.0,18,6.0,618.0,0
        """.trimIndent()
        val a = defaultGenome()
        val b = defaultGenome().also { it["FLAT_HARVEST_TRIGGER_PERCENT"] = 0.05 }

        val evaluations = evaluatePopulation(
            genomes = listOf(a, b),
            csvText = csv,
            symbol = "BTCUSDT",
            timespan = TimeSpan.Hours1,
            initialCapital = 10_000.0,
        )

        assertEquals(2, evaluations.size)
        assertSame(a, evaluations[0].genome)
        assertSame(b, evaluations[1].genome)
        assertEquals(2, evaluations[0].result.metrics.totalTicks)
        assertTrue(evaluations.all { it.fitness == fitnessFromResult(it.result) })
    }

    @Test
    fun `computeStochasticFitness rewards Sortino and penalizes drawdown`() {
        val baseMetrics = BacktestMetrics(
            totalTicks = 4,
            totalReturn = 0.10,
            sharpeRatio = 1.0,
            sortinoRatio = 2.0,
            maxDrawdown = 0.20,
            maxDrawdownTicks = 2,
            totalHarvested = 0.0,
            totalTrades = 0,
            avgHarvestPerTick = 0.0,
        )
        val strongerDownsideProfile = baseMetrics.copy(sortinoRatio = 4.0)
        val deeperDrawdown = baseMetrics.copy(maxDrawdown = 0.60)

        assertTrue(computeStochasticFitness(backtestResult(baseMetrics)) > 0.0)
        assertTrue(computeStochasticFitness(backtestResult(strongerDownsideProfile)) > computeStochasticFitness(backtestResult(baseMetrics)))
        assertTrue(computeStochasticFitness(backtestResult(deeperDrawdown)) < computeStochasticFitness(backtestResult(baseMetrics)))
    }

    @Test
    fun `crossoverGenome takes low sorted keys from left and high sorted keys from right`() {
        val left = Genome(mutableMapOf("A" to 1.0, "B" to 2.0, "C" to 3.0, "D" to 4.0))
        val right = Genome(mutableMapOf("A" to 10.0, "B" to 20.0, "C" to 30.0, "D" to 40.0))

        val child = crossoverGenome(left, right)

        assertEquals(1.0, child["A"])
        assertEquals(2.0, child["B"])
        assertEquals(30.0, child["C"])
        assertEquals(40.0, child["D"])
    }

    @Test
    fun `mutateGenome applies numeric deltas without mutating parent`() {
        val parent = Genome(mutableMapOf("HARVEST_TAKE_PERCENT" to 0.70, "ENABLE_CRASH_PROTECTION" to true))

        val mutant = mutateGenome(parent, mapOf("HARVEST_TAKE_PERCENT" to -0.05, "NEW_PARAM" to 2.0))

        assertEquals(0.70, parent["HARVEST_TAKE_PERCENT"])
        assertEquals(0.65, mutant["HARVEST_TAKE_PERCENT"] as Double, 0.000001)
        assertEquals(true, mutant["ENABLE_CRASH_PROTECTION"])
        assertEquals(2.0, mutant["NEW_PARAM"])
    }

    @Test
    fun `rankEvaluationsByFitness orders strongest stochastic backtests first`() {
        val weak = GenomeEvaluation(
            genome = Genome(mutableMapOf("id" to "weak")),
            result = backtestResult(metrics(totalReturn = 0.02, sharpeRatio = 0.5, sortinoRatio = 0.5, maxDrawdown = 0.30)),
            fitness = 0.72,
        )
        val strong = GenomeEvaluation(
            genome = Genome(mutableMapOf("id" to "strong")),
            result = backtestResult(metrics(totalReturn = 0.08, sharpeRatio = 1.5, sortinoRatio = 2.0, maxDrawdown = 0.10)),
            fitness = 3.48,
        )
        val middle = GenomeEvaluation(
            genome = Genome(mutableMapOf("id" to "middle")),
            result = backtestResult(metrics(totalReturn = 0.04, sharpeRatio = 1.0, sortinoRatio = 1.0, maxDrawdown = 0.20)),
            fitness = 1.84,
        )

        val ranked = rankEvaluationsByFitness(listOf(weak, strong, middle))

        assertSame(strong, ranked[0])
        assertSame(middle, ranked[1])
        assertSame(weak, ranked[2])
    }

    @Test
    fun `evolvePopulation preserves elite and fills next generation from ranked parents`() {
        val eliteGenome = Genome(mutableMapOf("A" to 1.0, "B" to 2.0, "C" to 3.0, "D" to 4.0))
        val mateGenome = Genome(mutableMapOf("A" to 10.0, "B" to 20.0, "C" to 30.0, "D" to 40.0))
        val elite = GenomeEvaluation(eliteGenome, backtestResult(metrics(0.10, 2.0, 3.0, 0.10)), fitness = 5.0)
        val mate = GenomeEvaluation(mateGenome, backtestResult(metrics(0.02, 0.5, 0.5, 0.20)), fitness = 0.82)

        val next = evolvePopulation(listOf(mate, elite), mutationDeltas = mapOf("A" to 0.5, "C" to -1.0))

        assertEquals(2, next.size)
        assertSame(eliteGenome, next[0])
        assertEquals(1.5, next[1]["A"] as Double, 0.000001)
        assertEquals(2.0, next[1]["B"] as Double, 0.000001)
        assertEquals(29.0, next[1]["C"] as Double, 0.000001)
        assertEquals(40.0, next[1]["D"] as Double, 0.000001)
    }

    private fun metrics(
        totalReturn: Double,
        sharpeRatio: Double,
        sortinoRatio: Double,
        maxDrawdown: Double,
    ): BacktestMetrics = BacktestMetrics(
        totalTicks = 4,
        totalReturn = totalReturn,
        sharpeRatio = sharpeRatio,
        sortinoRatio = sortinoRatio,
        maxDrawdown = maxDrawdown,
        maxDrawdownTicks = 1,
        totalHarvested = 0.0,
        totalTrades = 0,
        avgHarvestPerTick = 0.0,
    )

    private fun backtestResult(metrics: BacktestMetrics): BacktestResult = BacktestResult(
        symbol = "BTCUSDT",
        initialCapital = 10_000.0,
        cycles = emptyList(),
        metrics = metrics,
    )
}
