package borg.trikeshed.dreamer

import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class BacktestAggregationTest {

    // ── aggregateReports ───────────────────────────────────────────────────────

    @Test
    fun `aggregateReports empty list returns zero sentinel`() {
        val result = aggregateReports(emptyList())

        assertEquals(0, result.runCount)
        assertEquals(0, result.totalTicks)
        assertEquals(0.0, result.avgTotalReturn)
        assertEquals(0.0, result.avgSharpeRatio)
        assertEquals(0.0, result.avgSortinoRatio)
        assertEquals(0.0, result.maxDrawdown)
        assertEquals(0, result.maxDrawdownTicks)
        assertEquals(0, result.totalTrades)
        assertEquals(0.0, result.totalHarvested)
        assertEquals(0.0, result.bestReturn)
        assertEquals(0.0, result.worstReturn)
        assertNull(result.bestGenome)
    }

    @Test
    fun `aggregateReports single report returns that report verbatim`() {
        val report = BacktestReport(
            symbol = "BTCUSDT",
            initialCapital = 10_000.0,
            finalEquity = 10_750.0,
            totalTicks = 100,
            totalReturn = 0.075,
            sharpeRatio = 1.5,
            sortinoRatio = 2.1,
            maxDrawdown = 0.12,
            maxDrawdownTicks = 15,
            totalTrades = 7,
            totalHarvested = 350.0,
        )

        val result = aggregateReports(listOf(report))

        assertEquals(1, result.runCount)
        assertEquals(100, result.totalTicks)
        assertEquals(0.075, result.avgTotalReturn)
        assertEquals(1.5, result.avgSharpeRatio)
        assertEquals(2.1, result.avgSortinoRatio)
        assertEquals(0.12, result.maxDrawdown)
        assertEquals(15, result.maxDrawdownTicks)
        assertEquals(7, result.totalTrades)
        assertEquals(350.0, result.totalHarvested)
        assertEquals(0.075, result.bestReturn)
        assertEquals(0.075, result.worstReturn)
    }

    @Test
    fun `aggregateReports two reports capital-weights returns correctly`() {
        // Report A: 10k initial capital, 5% return  → contribution = 500
        // Report B: 30k initial capital, 10% return  → contribution = 3000
        // Weighted avg = (500 + 3000) / 40000 = 3500/40000 = 0.0875
        val reportA = backtestReport(
            symbol = "BTCUSDT",
            initialCapital = 10_000.0,
            totalReturn = 0.05,
            sharpeRatio = 1.0,
            sortinoRatio = 1.5,
            maxDrawdown = 0.10,
            totalTicks = 100,
            totalTrades = 3,
            totalHarvested = 100.0,
        )
        val reportB = backtestReport(
            symbol = "ETHUSDT",
            initialCapital = 30_000.0,
            totalReturn = 0.10,
            sharpeRatio = 2.0,
            sortinoRatio = 3.0,
            maxDrawdown = 0.20,
            totalTicks = 200,
            totalTrades = 12,
            totalHarvested = 800.0,
        )

        val result = aggregateReports(listOf(reportA, reportB))

        assertEquals(2, result.runCount)
        assertEquals(300, result.totalTicks)
        assertEquals(0.0875, result.avgTotalReturn, 0.00001)
        assertEquals(1.5, result.avgSharpeRatio)        // (1.0 + 2.0) / 2
        assertEquals(2.25, result.avgSortinoRatio)       // (1.5 + 3.0) / 2
        assertEquals(0.20, result.maxDrawdown)           // worst single-run drawdown
        assertEquals(15, result.totalTrades)
        assertEquals(900.0, result.totalHarvested)
        assertEquals(0.10, result.bestReturn)            // B wins
        assertEquals(0.05, result.worstReturn)           // A loses
    }

    @Test
    fun `aggregateReports bestGenome forwarded when provided`() {
        val genomeMap = mapOf("champion" to "value")
        val report = backtestReport(
            symbol = "BTCUSDT",
            initialCapital = 10_000.0,
            totalReturn = 0.05,
            sharpeRatio = 1.0,
            sortinoRatio = 1.0,
            maxDrawdown = 0.10,
        )

        val result = aggregateReports(listOf(report), bestGenome = genomeMap)

        assertEquals(genomeMap, result.bestGenome)
    }

    @Test
    fun `aggregateReports capital-weighted return uses initial capital as weight`() {
        // Equal capital → simple average of returns
        val r1 = backtestReport(initialCapital = 5_000.0, totalReturn = 0.04)
        val r2 = backtestReport(initialCapital = 5_000.0, totalReturn = 0.08)
        // avg = (0.04 + 0.08) / 2 = 0.06

        val result = aggregateReports(listOf(r1, r2))

        assertEquals(0.06, result.avgTotalReturn, 0.00001)
    }

    // ── aggregateEvaluations ──────────────────────────────────────────────────

    @Test
    fun `aggregateEvaluations empty list returns zero sentinel`() {
        val result = aggregateEvaluations(emptyList())

        assertEquals(0, result.runCount)
        assertEquals(0.0, result.avgTotalReturn)
    }

    @Test
    fun `aggregateEvaluations single evaluation returns that result verbatim`() {
        val genome = Genome(mutableMapOf("A" to 1.0, "B" to 2.0))
        val eval = GenomeEvaluation(
            genome = genome,
            result = backtestResult(
                metrics = BacktestMetrics(
                    totalTicks = 50,
                    totalReturn = 0.062,
                    sharpeRatio = 1.4,
                    sortinoRatio = 1.9,
                    maxDrawdown = 0.08,
                    maxDrawdownTicks = 6,
                    totalHarvested = 200.0,
                    totalTrades = 4,
                    avgHarvestPerTick = 4.0,
                )
            ),
            fitness = 1.5,
        )

        val result = aggregateEvaluations(listOf(eval))

        assertEquals(1, result.runCount)
        assertEquals(50, result.totalTicks)
        assertEquals(0.062, result.avgTotalReturn)
        assertEquals(1.4, result.avgSharpeRatio)
        assertEquals(1.9, result.avgSortinoRatio)
        assertEquals(0.08, result.maxDrawdown)
        assertEquals(6, result.maxDrawdownTicks)
        assertEquals(4, result.totalTrades)
        assertEquals(200.0, result.totalHarvested)
        assertEquals(0.062, result.bestReturn)
        assertEquals(0.062, result.worstReturn)
    }

    @Test
    fun `aggregateEvaluations bestGenome defaults to champion genome backing`() {
        val weakGenome = Genome(mutableMapOf("id" to "weak"))
        val strongGenome = Genome(mutableMapOf("id" to "strong"))
        val weakEval = GenomeEvaluation(
            genome = weakGenome,
            result = backtestResult(metrics(totalReturn = 0.02, sharpeRatio = 0.5, sortinoRatio = 0.5, maxDrawdown = 0.30)),
            fitness = 0.5,
        )
        val strongEval = GenomeEvaluation(
            genome = strongGenome,
            result = backtestResult(metrics(totalReturn = 0.08, sharpeRatio = 1.5, sortinoRatio = 2.0, maxDrawdown = 0.10)),
            fitness = 3.5,
        )

        val result = aggregateEvaluations(listOf(weakEval, strongEval))

        // Champion genome backing (strongEval has highest fitness)
        assertEquals(strongGenome.backing, result.bestGenome)
    }

    @Test
    fun `aggregateEvaluations bestGenome explicit override takes precedence`() {
        val genome = Genome(mutableMapOf("id" to "genome"))
        val eval = GenomeEvaluation(
            genome = genome,
            result = backtestResult(metrics()),
            fitness = 1.0,
        )
        val override = mapOf("override" to "value")

        val result = aggregateEvaluations(listOf(eval), bestGenome = override)

        assertEquals(override, result.bestGenome)
    }

    @Test
    fun `aggregateEvaluations multi-genome ranking finds true champion by fitness`() {
        val g1 = Genome(mutableMapOf("id" to "g1"))
        val g2 = Genome(mutableMapOf("id" to "g2"))
        val g3 = Genome(mutableMapOf("id" to "g3"))

        val e1 = GenomeEvaluation(g1, backtestResult(metrics(totalReturn = 0.01, sharpeRatio = 0.1, sortinoRatio = 0.1, maxDrawdown = 0.50)), fitness = -0.3)
        val e2 = GenomeEvaluation(g2, backtestResult(metrics(totalReturn = 0.05, sharpeRatio = 1.0, sortinoRatio = 1.0, maxDrawdown = 0.15)), fitness = 1.6)
        val e3 = GenomeEvaluation(g3, backtestResult(metrics(totalReturn = 0.03, sharpeRatio = 0.5, sortinoRatio = 0.5, maxDrawdown = 0.30)), fitness = 0.7)

        val result = aggregateEvaluations(listOf(e1, e2, e3))

        assertEquals(3, result.runCount)
        // g2 had the best (highest) fitness of 1.6
        assertEquals(g2.backing, result.bestGenome)
        assertEquals(0.05, result.bestReturn)
        assertEquals(0.01, result.worstReturn)
        assertEquals(0.50, result.maxDrawdown)   // worst single-run drawdown across all 3
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun backtestReport(
        symbol: String = "BTCUSDT",
        initialCapital: Double = 10_000.0,
        totalReturn: Double = 0.05,
        sharpeRatio: Double = 1.0,
        sortinoRatio: Double = 1.5,
        maxDrawdown: Double = 0.10,
        totalTicks: Int = 100,
        totalTrades: Int = 5,
        totalHarvested: Double = 200.0,
    ): BacktestReport {
        val finalEquity = initialCapital * (1.0 + totalReturn)
        return BacktestReport(
            symbol = symbol,
            initialCapital = initialCapital,
            finalEquity = finalEquity,
            totalTicks = totalTicks,
            totalReturn = totalReturn,
            sharpeRatio = sharpeRatio,
            sortinoRatio = sortinoRatio,
            maxDrawdown = maxDrawdown,
            maxDrawdownTicks = (maxDrawdown * totalTicks).toInt().coerceAtLeast(1),
            totalTrades = totalTrades,
            totalHarvested = totalHarvested,
        )
    }

    private fun backtestResult(
        metrics: BacktestMetrics = BacktestMetrics(
            totalTicks = 100,
            totalReturn = 0.05,
            sharpeRatio = 1.0,
            sortinoRatio = 1.5,
            maxDrawdown = 0.10,
            maxDrawdownTicks = 10,
            totalHarvested = 200.0,
            totalTrades = 5,
            avgHarvestPerTick = 2.0,
        )
    ): BacktestResult = BacktestResult(
        symbol = "BTCUSDT",
        initialCapital = 10_000.0,
        cycles = Join.emptySeriesOf(),
        metrics = metrics,
    )

    private fun metrics(
        totalReturn: Double = 0.05,
        sharpeRatio: Double = 1.0,
        sortinoRatio: Double = 1.5,
        maxDrawdown: Double = 0.10,
    ): BacktestMetrics = BacktestMetrics(
        totalTicks = 100,
        totalReturn = totalReturn,
        sharpeRatio = sharpeRatio,
        sortinoRatio = sortinoRatio,
        maxDrawdown = maxDrawdown,
        maxDrawdownTicks = (maxDrawdown * 100).toInt().coerceAtLeast(1),
        totalHarvested = 0.0,
        totalTrades = 0,
        avgHarvestPerTick = 0.0,
    )

    // ── StochasticTrainingSnapshot → BacktestReport adapter ─────────────────

    @Test
    fun `StochasticTrainingSnapshot toBacktestReport produces report from training scalars`() {
        val snapshot = StochasticTrainingSnapshot(
            generation = 3,
            pairCount = 2,
            rowsPerSeries = 48,
            populationSize = 4,
            evaluations = 12,
            bestFitness = 2.5,
            bestTotalValue = 10_500.0,
            bestProfit = 500.0,
            bestDrawdown = 0.08,
            bestTrades = 7,
            totalCycles = 48,
            totalWindows = 96,
            totalSpans = 32,
            championTakePercent = 0.70,
            championMinSurplus = 0.50,
            championRebalanceTrigger = 0.05,
            sampleWindows = emptyList(),
            sampleSpans = emptyList(),
        )

        val report = snapshot.toBacktestReport(symbol = "BTC+ETH", initialCapital = 10_000.0)

        assertEquals("BTC+ETH", report.symbol)
        assertEquals(10_000.0, report.initialCapital)
        assertEquals(10_500.0, report.finalEquity, 0.001)
        assertEquals(48, report.totalTicks)
        assertEquals(0.05, report.totalReturn, 0.001)
        assertEquals(0.08, report.maxDrawdown, 0.001)
        assertEquals(7, report.totalTrades)
        assertEquals(500.0, report.totalHarvested, 0.001)
    }

    @Test
    fun `aggregateTrainingSnapshots rolls up multiple generations`() {
        val gen1 = StochasticTrainingSnapshot(
            generation = 1,
            pairCount = 2,
            rowsPerSeries = 48,
            populationSize = 4,
            evaluations = 4,
            bestFitness = 1.0,
            bestTotalValue = 10_200.0,
            bestProfit = 200.0,
            bestDrawdown = 0.12,
            bestTrades = 3,
            totalCycles = 48,
            totalWindows = 48,
            totalSpans = 16,
            championTakePercent = 0.50,
            championMinSurplus = 0.50,
            championRebalanceTrigger = 0.05,
            sampleWindows = emptyList(),
            sampleSpans = emptyList(),
        )
        val gen2 = gen1.copy(
            generation = 2,
            bestTotalValue = 10_800.0,
            bestProfit = 800.0,
            bestDrawdown = 0.06,
            bestTrades = 5,
        )

        val aggregate = aggregateTrainingSnapshots(
            snapshots = listOf(gen1, gen2),
            initialCapital = 10_000.0,
        )

        assertEquals(2, aggregate.runCount)
        assertEquals(96, aggregate.totalTicks)  // 48 + 48
        assertEquals(8, aggregate.totalTrades)  // 3 + 5
        assertEquals(0.12, aggregate.maxDrawdown, 0.001)  // worst single-run
        assertEquals(0.08, aggregate.bestReturn, 0.001)    // gen2: (10800-10000)/10000
        assertEquals(0.02, aggregate.worstReturn, 0.001)   // gen1: (10200-10000)/10000
    }
}
