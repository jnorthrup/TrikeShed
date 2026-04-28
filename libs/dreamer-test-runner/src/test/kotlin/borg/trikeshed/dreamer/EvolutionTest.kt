package borg.trikeshed.dreamer

import borg.trikeshed.couch.kline.Kline
import borg.trikeshed.couch.kline.KlineBlock
import borg.trikeshed.couch.kline.TimeSpan
import borg.trikeshed.miniduck.MiniCursor
import borg.trikeshed.miniduck.at
import borg.trikeshed.lib.size
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for Evolution.kt — genome genetic algorithm.
 */
class EvolutionTest {

    private fun klinesToCursor(klines: List<Kline>): MiniCursor {
        val block = KlineBlock.mutable()
        klines.forEach { block.append(it) }
        return block.seal().asCursor()
    }

    // ── randomGenome ──────────────────────────────────────────────────────

    @Test
    fun `randomGenome produces valid FLAT_HARVEST_TRIGGER_PERCENT range`() {
        val genomes = (0 until 20).map { randomGenome() }
        assertTrue(genomes.all { g ->
            val v = g.getDouble("FLAT_HARVEST_TRIGGER_PERCENT")
            v in 0.01..0.10
        })
    }

    @Test
    fun `randomGenome produces valid HARVEST_TAKE_PERCENT range`() {
        val genomes = (0 until 20).map { randomGenome() }
        assertTrue(genomes.all { g ->
            val v = g.getDouble("HARVEST_TAKE_PERCENT")
            v in 0.30..0.90
        })
    }

    // ── mutate ────────────────────────────────────────────────────────────

    @Test
    fun `mutate returns the same genome object (in-place)`() {
        val g = defaultGenome()
        val result = mutate(g, rate = 1.0, sigma = 0.01)
        assertTrue(result === g)
    }

    @Test
    fun `mutate with rate=0 changes nothing`() {
        val g = defaultGenome()
        val before = g.backing.mapValues { it.value }
        mutate(g, rate = 0.0, sigma = 0.5)
        assertEquals(before, g.backing.mapValues { it.value })
    }

    @Test
    fun `mutate with rate=1 changes at least one key`() {
        val g = defaultGenome()
        // Capture snapshot of values before mutation
        val beforeSnapshot = g.backing.entries.associate { it.key to it.value }
        mutate(g, rate = 1.0, sigma = 0.1)
        // With rate=1 and sigma=0.1, at least one value must differ
        assertTrue(g.backing.entries.any { g.backing[it.key] != beforeSnapshot[it.key] })
    }

    // ── crossover ─────────────────────────────────────────────────────────

    @Test
    fun `crossover produces two distinct children`() {
        val p1 = randomGenome(Random(42))
        val p2 = randomGenome(Random(99))
        val (c1, c2) = crossover(p1, p2, Random(123))

        // Children must have keys from both parents
        val p1Keys = p1.backing.keys
        val p2Keys = p2.backing.keys
        val allKeys = p1Keys + p2Keys

        assertTrue(c1.backing.keys.containsAll(allKeys))
        assertTrue(c2.backing.keys.containsAll(allKeys))

        // Children must differ (with very high probability)
        assertNotEquals(c1.backing.toString(), c2.backing.toString())
    }

    @Test
    fun `crossover children have all expected keys`() {
        val p1 = defaultGenome()
        val p2 = defaultGenome()
        val (c1, c2) = crossover(p1, p2, Random(7))

        val expectedKeys = p1.backing.keys + p2.backing.keys
        assertTrue(c1.backing.keys.containsAll(expectedKeys))
        assertTrue(c2.backing.keys.containsAll(expectedKeys))
    }

    // ── tournamentSelect ──────────────────────────────────────────────────

    @Test
    fun `tournamentSelect returns a genome from the population`() {
        val population = listOf(randomGenome(), randomGenome(), randomGenome())
        val fitness = listOf(1.0, 2.0, 0.5)

        val selected = tournamentSelect(population, fitness, tournamentSize = 2)
        assertTrue(selected in population)
    }

    @Test
    fun `tournamentSelect higher fitness wins more often`() {
        val bestGenome = defaultGenome().also { it["FLAT_HARVEST_TRIGGER_PERCENT"] = 0.99 }
        val population = listOf(
            randomGenome().also { it["FLAT_HARVEST_TRIGGER_PERCENT"] = 0.01 },
            randomGenome().also { it["FLAT_HARVEST_TRIGGER_PERCENT"] = 0.02 },
            bestGenome,
        )
        val fitness = listOf(1.0, 2.0, 100.0)

        // Run many tournaments — the best should be selected most of the time
        val selections = (0 until 100).map {
            tournamentSelect(population, fitness, tournamentSize = 3, Random)
        }
        val bestCount = selections.count { it === bestGenome }
        assertTrue(bestCount > 50, "Best genome should win majority of tournaments, got $bestCount")
    }

    // ── evolvePopulation ──────────────────────────────────────────────────

    @Test
    fun `evolvePopulation preserves population size`() {
        val population = (0 until 10).map { randomGenome(Random(it.hashCode())) }
        val fitness = (0 until 10).map { it.toDouble() }

        val evolved = evolvePopulation(population, fitness, eliteCount = 2, random = Random(42))

        assertEquals(population.size, evolved.size)
    }

    @Test
    fun `evolvePopulation keeps elite genomes unchanged`() {
        val population = (0 until 10).map { randomGenome(Random(it.hashCode())) }
        val fitness = (0 until 10).map { it.toDouble() }

        val evolved = evolvePopulation(population, fitness, eliteCount = 3, random = Random(42))

        // Top 3 by fitness are indices 9, 8, 7 (sorted descending)
        val sorted = population.indices.sortedByDescending { fitness[it] }
        val eliteIndices = sorted.take(3)
        val eliteGenomes = eliteIndices.map { population[it] }

        // Elites should appear in evolved population
        assertTrue(evolved.any { it === eliteGenomes[0] })
        assertTrue(evolved.any { it === eliteGenomes[1] })
        assertTrue(evolved.any { it === eliteGenomes[2] })
    }

    @Test
    fun `evolvePopulation non-elites differ from originals`() {
        val population = (0 until 6).map { randomGenome(Random(it.hashCode())) }
        // Give identical fitness so any could be selected
        val fitness = List(6) { 1.0 }

        val evolved = evolvePopulation(population, fitness, eliteCount = 1, random = Random(77))

        // Non-elites should not be the same objects
        val nonElite = evolved.drop(1)
        val originals = population.toSet()
        assertTrue(nonElite.none { it in originals })
    }

    // ── fitnessFromResult ───────────────────────────────────────────────

    @Test
    fun `fitnessFromResult returns higher for better metrics`() {
        val resultGood = BacktestResult(
            symbol = "BTC",
            initialCapital = 10_000.0,
            cycles = emptyList(),
            metrics = BacktestMetrics(
                totalTicks = 10,
                totalReturn = 0.20,  // +20%
                sharpeRatio = 1.5,
                maxDrawdown = 0.05,   // 5%
                maxDrawdownTicks = 2,
                totalHarvested = 500.0,
                totalTrades = 3,
                avgHarvestPerTick = 50.0,
            ),
        )

        val resultBad = BacktestResult(
            symbol = "BTC",
            initialCapital = 10_000.0,
            cycles = emptyList(),
            metrics = BacktestMetrics(
                totalTicks = 10,
                totalReturn = 0.05,   // +5%
                sharpeRatio = 0.3,
                maxDrawdown = 0.20,   // 20%
                maxDrawdownTicks = 5,
                totalHarvested = 50.0,
                totalTrades = 1,
                avgHarvestPerTick = 5.0,
            ),
        )

        val goodFitness = fitnessFromResult(resultGood)
        val badFitness = fitnessFromResult(resultBad)

        assertTrue(goodFitness > badFitness,
            "Good fitness=$goodFitness should exceed bad fitness=$badFitness")
    }

    // ── evaluatePopulation ──────────────────────────────────────────────

    @Test
    fun `evaluatePopulation returns one fitness per genome`() = runBlocking {
        val klines = listOf(
            Kline("BTC-USD", TimeSpan.Hours1, 1704067200000L, 20500.0, 21000.0, 20300.0, 20800.0, 1500.5),
            Kline("BTC-USD", TimeSpan.Hours1, 1704070800000L, 20800.0, 21200.0, 20700.0, 21100.0, 1600.0),
            Kline("BTC-USD", TimeSpan.Hours1, 1704074400000L, 21100.0, 21500.0, 21000.0, 21400.0, 1700.0),
        )
        val cursor = klinesToCursor(klines)
        val population = listOf(defaultGenome(), defaultGenome())

        val fitness = evaluatePopulation(population, cursor, initialCapital = 10_000.0)

        assertEquals(2, fitness.size)
        // Both should produce the same fitness (identical genomes)
        assertEquals(fitness[0], fitness[1])
    }

    // ── runGeneration ───────────────────────────────────────────────────

    @Test
    fun `runGeneration returns same-sized evolved population`() = runBlocking {
        val klines = listOf(
            Kline("BTC-USD", TimeSpan.Hours1, 1704067200000L, 20500.0, 21000.0, 20300.0, 20800.0, 1500.5),
            Kline("BTC-USD", TimeSpan.Hours1, 1704070800000L, 20800.0, 21200.0, 20700.0, 21100.0, 1600.0),
            Kline("BTC-USD", TimeSpan.Hours1, 1704074400000L, 21100.0, 21500.0, 21000.0, 21400.0, 1700.0),
        )
        val cursor = klinesToCursor(klines)
        val population = (0 until 6).map { randomGenome(Random(it)) }

        val (evolved, topFitness) = runGeneration(population, cursor, initialCapital = 10_000.0)

        assertEquals(population.size, evolved.size)
        assertTrue(topFitness.isFinite())
    }
}
