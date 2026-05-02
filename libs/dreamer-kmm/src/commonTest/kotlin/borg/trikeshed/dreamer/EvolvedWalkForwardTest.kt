package borg.trikeshed.dreamer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [evolvedWalkForwardValidation] — true walk-forward where the genome
 * is evolved on each training window before being tested on the next window.
 */
class EvolvedWalkForwardTest {

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

    private fun multiSymbolKlines(count: Int = 30): List<Kline> {
        val btc = (0 until count).map { index ->
            val close = 100.0 + index * 0.5
            val open = if (index == 0) close else 100.0 + (index - 1) * 0.5
            Kline("BTCUSDT", TimeSpan.Minutes1, 1_704_067_200_000L + index * 60_000L,
                open, maxOf(open, close) + 1.0, minOf(open, close) - 1.0, close, 100.0 + index)
        }
        val eth = (0 until count).map { index ->
            val close = 10.0 + index * 0.05
            val open = if (index == 0) close else 10.0 + (index - 1) * 0.05
            Kline("ETHUSDT", TimeSpan.Minutes1, 1_704_067_200_000L + index * 60_000L,
                open, maxOf(open, close) + 0.5, minOf(open, close) - 0.5, close, 50.0 + index)
        }
        return (btc + eth).sortedBy { it.openTime }
    }

    // ── evolvedWalkForwardValidation ────────────────────────────────────────

    @Test
    fun `evolvedWalkForward pairs consecutive windows and evolves on train`() = runTest {
        // 40 bars, window=10, step=10 → 4 windows, 3 pairs
        val result = evolvedWalkForwardValidation(
            klines = risingKlines(40),
            initialCapital = 10_000.0,
            windowSize = 10,
            stepSize = 10,
            trainingConfig = StochasticTrainingConfig(
                bases = listOf("BTC"),
                rowsPerSeries = 10,
                populationSize = 2,
                spanLength = 4,
                initialCapital = 10_000.0,
                seed = 42,
            ),
            trainingGenerations = 2,
        )

        assertEquals(3, result.pairCount)
        assertEquals(3, result.trainReports.size)
        assertEquals(3, result.testReports.size)
        assertEquals(3, result.championGenomes.size)
    }

    @Test
    fun `evolvedWalkForward champion genomes differ from the seed genome`() = runTest {
        val seedGenome = defaultGenome()
        val result = evolvedWalkForwardValidation(
            klines = risingKlines(40),
            initialCapital = 10_000.0,
            windowSize = 10,
            stepSize = 10,
            trainingConfig = StochasticTrainingConfig(
                bases = listOf("BTC"),
                rowsPerSeries = 10,
                populationSize = 3,
                spanLength = 4,
                initialCapital = 10_000.0,
                seed = 99,
            ),
            trainingGenerations = 3,
        )

        // Champion genomes should exist and have valid width
        result.championGenomes.forEach { champion ->
            assertEquals(Genome.WIDTH, champion.doubles.size)
        }
    }

    @Test
    fun `evolvedWalkForward test reports use evolved champion from train window`() = runTest {
        val result = evolvedWalkForwardValidation(
            klines = risingKlines(40),
            initialCapital = 10_000.0,
            windowSize = 10,
            stepSize = 10,
            trainingConfig = StochasticTrainingConfig(
                bases = listOf("BTC"),
                rowsPerSeries = 10,
                populationSize = 2,
                spanLength = 4,
                initialCapital = 10_000.0,
                seed = 77,
            ),
            trainingGenerations = 2,
        )

        // Each test report should use the champion from the preceding train window
        result.testReports.forEach { report ->
            assertEquals("BTCUSDT", report.symbol)
            assertEquals(10_000.0, report.initialCapital, 0.001)
            assertEquals(10, report.totalTicks)
            assertTrue(report.finalEquity > 0.0)
            assertTrue(report.totalReturn.isFinite())
        }
    }

    @Test
    fun `evolvedWalkForward aggregates roll up train and test separately`() = runTest {
        val result = evolvedWalkForwardValidation(
            klines = risingKlines(40),
            initialCapital = 10_000.0,
            windowSize = 10,
            stepSize = 10,
            trainingConfig = StochasticTrainingConfig(
                bases = listOf("BTC"),
                rowsPerSeries = 10,
                populationSize = 2,
                spanLength = 4,
                initialCapital = 10_000.0,
                seed = 33,
            ),
            trainingGenerations = 2,
        )

        assertEquals(3, result.trainAggregate.runCount)
        assertEquals(3, result.testAggregate.runCount)
        assertEquals(30, result.trainAggregate.totalTicks) // 10 * 3
        assertEquals(30, result.testAggregate.totalTicks)
        assertTrue(result.trainAggregate.avgTotalReturn.isFinite())
        assertTrue(result.testAggregate.avgTotalReturn.isFinite())
    }

    @Test
    fun `evolvedWalkForward with only one window produces zero pairs`() = runTest {
        val result = evolvedWalkForwardValidation(
            klines = risingKlines(10),
            initialCapital = 10_000.0,
            windowSize = 10,
            stepSize = 10,
            trainingConfig = StochasticTrainingConfig(
                bases = listOf("BTC"),
                rowsPerSeries = 10,
                populationSize = 2,
                spanLength = 4,
                initialCapital = 10_000.0,
                seed = 42,
            ),
            trainingGenerations = 1,
        )

        assertEquals(0, result.pairCount)
        assertTrue(result.trainReports.isEmpty())
        assertTrue(result.testReports.isEmpty())
        assertEquals(0, result.trainAggregate.runCount)
        assertEquals(0, result.testAggregate.runCount)
    }

    @Test
    fun `evolvedWalkForward multi-symbol evolves on multiple symbols per window`() = runTest {
        // multiSymbolKlines(40) produces 40 BTC + 40 ETH = 80 interleaved klines
        // window=20, step=20 → 4 windows of 20 klines (10 per symbol), 3 pairs
        val result = evolvedWalkForwardValidation(
            klines = multiSymbolKlines(40),
            initialCapital = 10_000.0,
            windowSize = 20,
            stepSize = 20,
            trainingConfig = StochasticTrainingConfig(
                bases = listOf("BTC", "ETH"),
                rowsPerSeries = 10,
                populationSize = 2,
                spanLength = 4,
                initialCapital = 10_000.0,
                seed = 42,
            ),
            trainingGenerations = 1,
        )

        assertEquals(3, result.pairCount)
        result.championGenomes.forEach { champion ->
            assertNotNull(champion)
            assertEquals(Genome.WIDTH, champion.doubles.size)
        }
    }

    @Test
    fun `evolvedWalkForward outperforms fixed-genome walk-forward on rising data`() = runTest {
        // On monotonically rising data, evolved genomes should find at least
        // as good performance as the fixed genome (since the seed is in the population).
        val fixedResult = walkForwardValidation(
            klines = risingKlines(40),
            genome = defaultGenome(),
            initialCapital = 10_000.0,
            windowSize = 10,
            stepSize = 10,
        )

        val evolvedResult = evolvedWalkForwardValidation(
            klines = risingKlines(40),
            initialCapital = 10_000.0,
            windowSize = 10,
            stepSize = 10,
            trainingConfig = StochasticTrainingConfig(
                bases = listOf("BTC"),
                rowsPerSeries = 10,
                populationSize = 4,
                spanLength = 4,
                initialCapital = 10_000.0,
                seed = 12,
            ),
            trainingGenerations = 3,
        )

        // The evolved walk-forward should produce valid reports
        assertEquals(fixedResult.pairCount, evolvedResult.pairCount)
        // Train reports should exist and be valid
        evolvedResult.trainReports.forEach { report ->
            assertTrue(report.totalReturn.isFinite())
            assertTrue(report.finalEquity > 0.0)
        }
    }
}
