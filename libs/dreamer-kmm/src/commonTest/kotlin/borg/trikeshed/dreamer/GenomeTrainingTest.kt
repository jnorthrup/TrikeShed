package borg.trikeshed.dreamer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [GenomeTrainer]: one-dimensional and pair-bag training paths
 * that evaluate candidate genomes against sealed kline blocks.
 */
class GenomeTrainingTest {

    @Test
    fun `trainOneDimensional evaluates 4 candidates and returns champion`() = runTest {
        val key = klineSeriesKey("BTC", "USDT", TimeSpan.Minutes1)
        val trainer = GenomeTrainer(initialCapital = 10_000.0)

        val result = trainer.trainOneDimensional(
            key = key,
            block = block("BTC", listOf(100.0, 101.0, 103.0, 102.0, 105.0)),
        )

        assertEquals(4, result.evaluations.size)
        assertNotNull(result.champion)
        assertEquals(Genome.WIDTH, result.champion.doubles.size)
        // Evaluations should be sorted by fitness descending
        assertTrue(result.evaluations.first().fitness >= result.evaluations.last().fitness)
    }

    @Test
    fun `trainOneDimensional champion genome is one of the candidates`() = runTest {
        val key = klineSeriesKey("BTC", "USDT", TimeSpan.Minutes1)
        val trainer = GenomeTrainer(initialCapital = 10_000.0)
        val result = trainer.trainOneDimensional(key = key, block = block("BTC", listOf(100.0, 101.0, 103.0)))

        // Champion should be the genome from the best evaluation
        assertEquals(result.evaluations.first().genome, result.champion)
    }

    @Test
    fun `trainOneDimensional all candidates produce positive total value`() = runTest {
        val key = klineSeriesKey("BTC", "USDT", TimeSpan.Minutes1)
        val trainer = GenomeTrainer(initialCapital = 10_000.0)
        val result = trainer.trainOneDimensional(key = key, block = block("BTC", listOf(100.0, 102.0, 104.0, 106.0)))

        result.evaluations.forEach { candidate ->
            assertTrue(candidate.result.finalTotalValue > 0.0,
                "Candidate genome should produce positive totalValue: ${candidate.result.finalTotalValue}")
        }
    }

    @Test
    fun `trainPairBag evaluates multiple symbols and returns ranked candidates`() = runTest {
        val btcKey = klineSeriesKey("BTC", "USDT", TimeSpan.Minutes1)
        val ethKey = klineSeriesKey("ETH", "USDT", TimeSpan.Minutes1)
        val inputs = listOf(
            HarnessReplayInput(btcKey, block("BTC", listOf(100.0, 101.0, 103.0, 102.0, 105.0))),
            HarnessReplayInput(ethKey, block("ETH", listOf(10.0, 10.2, 10.5, 10.3, 10.8))),
        )

        val trainer = GenomeTrainer(initialCapital = 10_000.0)
        val result = trainer.trainPairBag(inputs = inputs)

        assertEquals(4, result.evaluations.size)
        assertTrue(result.evaluations.first().fitness >= result.evaluations.last().fitness)
        result.evaluations.forEach { candidate ->
            assertTrue(candidate.result.cycles.isNotEmpty())
            assertTrue(candidate.result.finalTotalValue > 0.0)
        }
    }

    @Test
    fun `trainPairBag fitness values are all finite`() = runTest {
        val btcKey = klineSeriesKey("BTC", "USDT", TimeSpan.Minutes1)
        val ethKey = klineSeriesKey("ETH", "USDT", TimeSpan.Minutes1)
        val inputs = listOf(
            HarnessReplayInput(btcKey, block("BTC", listOf(100.0, 101.0, 103.0, 102.0))),
            HarnessReplayInput(ethKey, block("ETH", listOf(10.0, 10.1, 10.3, 10.2))),
        )

        val trainer = GenomeTrainer(initialCapital = 10_000.0)
        val result = trainer.trainPairBag(inputs = inputs)

        result.evaluations.forEach { candidate ->
            assertTrue(candidate.fitness.isFinite(),
                "Fitness should be finite: ${candidate.fitness}")
        }
    }

    @Test
    fun `GenomeTrainer evaluate with custom seed genome includes seed as candidate`() = runTest {
        val key = klineSeriesKey("BTC", "USDT", TimeSpan.Minutes1)
        val customGenome = defaultGenome().also {
            it[GenomeParam.HARVEST_TAKE_PERCENT] = 0.90
            it[GenomeParam.MIN_SURPLUS_FOR_HARVEST] = 0.001
        }

        val trainer = GenomeTrainer(initialCapital = 10_000.0)
        val result = trainer.trainOneDimensional(key = key, block = block("BTC", listOf(100.0, 103.0, 107.0)), seed = customGenome)

        // The seed genome should be among the candidates (it's the first candidate in trainOneDimensional)
        val seedCandidate = result.evaluations.find {
            it.genome.getDouble("HARVEST_TAKE_PERCENT") == 0.90 &&
            it.genome.getDouble("MIN_SURPLUS_FOR_HARVEST") == 0.001
        }
        assertNotNull(seedCandidate, "Seed genome should be among evaluated candidates")
    }

    @Test
    fun `GenomeTrainingCandidate result feeds into toBacktestReport via bridge`() = runTest {
        val key = klineSeriesKey("BTC", "USDT", TimeSpan.Minutes1)
        val trainer = GenomeTrainer(initialCapital = 10_000.0)
        val result = trainer.trainOneDimensional(key = key, block = block("BTC", listOf(100.0, 101.0, 103.0)))

        // Each candidate's HarnessRunResult should be convertible to a BacktestReport
        val reports = result.evaluations.map { candidate ->
            candidate.result.toBacktestReport(symbol = "BTCUSDT", initialCapital = 10_000.0)
        }

        assertEquals(4, reports.size)
        reports.forEach { report ->
            assertEquals("BTCUSDT", report.symbol)
            assertEquals(10_000.0, report.initialCapital, 0.001)
            assertTrue(report.finalEquity > 0.0)
            assertTrue(report.totalReturn.isFinite())
        }
    }
}
