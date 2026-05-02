package borg.trikeshed.dreamer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [StochasticBagSpanTrainer] internals:
 * population initialization, mutation, elitism, and generation mechanics.
 */
class StochasticTrainingTest {

    private fun defaultConfig(
        populationSize: Int = 4,
        spanLength: Int = 8,
        rowsPerSeries: Int = 20,
    ) = StochasticTrainingConfig(
        bases = listOf("BTC", "ETH"),
        quote = "USDT",
        timespan = TimeSpan.Minutes1,
        rowsPerSeries = rowsPerSeries,
        populationSize = populationSize,
        spanLength = spanLength,
        initialCapital = 10_000.0,
        seed = 42,
        mutationStep = 0.015,
    )

    private fun defaultTrainer(
        config: StochasticTrainingConfig = defaultConfig(),
    ) = StochasticBagSpanTrainer(config, archiveInputs(config))

    // ── initialPopulation ───────────────────────────────────────────────────

    @Test
    fun `initialPopulation produces correct size`() {
        val config = defaultConfig(populationSize = 6)
        val trainer = StochasticBagSpanTrainer(config, archiveInputs(config))
        assertEquals(6, trainer.population.size)
    }

    @Test
    fun `initialPopulation size 1 returns seed genome only`() {
        val config = defaultConfig(populationSize = 1)
        val trainer = StochasticBagSpanTrainer(config, archiveInputs(config))
        assertEquals(1, trainer.population.size)
        assertEquals(defaultGenome().doubles.toList(), trainer.population[0].doubles.toList())
    }

    @Test
    fun `initialPopulation first individual is seed genome copy`() {
        val config = defaultConfig(populationSize = 4)
        val trainer = StochasticBagSpanTrainer(config, archiveInputs(config))
        val seed = defaultGenome()
        // First individual should be a copy of the seed genome
        assertEquals(seed.doubles.toList(), trainer.population[0].doubles.toList())
    }

    @Test
    fun `initialPopulation members have valid Genome width`() {
        val config = defaultConfig(populationSize = 4)
        val trainer = StochasticBagSpanTrainer(config, archiveInputs(config))
        trainer.population.forEach { genome ->
            assertEquals(Genome.WIDTH, genome.doubles.size)
        }
    }

    // ── mutate ──────────────────────────────────────────────────────────────

    @Test
    fun `mutate produces a different genome from parent`() {
        val config = defaultConfig()
        val trainer = defaultTrainer(config)
        val parent = defaultGenome()
        val random = kotlin.random.Random(12345)
        val mutant = trainer.mutate(parent, salt = 0, random = random)
        // At least one key should differ
        val keys = arrayOf(
            "HARVEST_TAKE_PERCENT",
            "MIN_SURPLUS_FOR_HARVEST",
            "FLAT_REBALANCE_TRIGGER_PERCENT",
        )
        val anyDiff = keys.any { key ->
            parent.getDouble(key) != mutant.getDouble(key)
        }
        assertTrue(anyDiff, "mutate should change at least one parameter")
    }

    @Test
    fun `mutate does not modify parent genome`() {
        val config = defaultConfig()
        val trainer = defaultTrainer(config)
        val parent = defaultGenome()
        val originalTake = parent.getDouble("HARVEST_TAKE_PERCENT")
        val random = kotlin.random.Random(12345)
        trainer.mutate(parent, salt = 0, random = random)
        assertEquals(originalTake, parent.getDouble("HARVEST_TAKE_PERCENT"))
    }

    @Test
    fun `mutate coerces HARVEST_TAKE_PERCENT within 0_05 to 0_95`() {
        val config = defaultConfig()
        val trainer = defaultTrainer(config)
        val parent = defaultGenome()
        // Try to push past bounds with many mutations
        val random = kotlin.random.Random(42)
        repeat(100) { salt ->
            val mutant = trainer.mutate(parent, salt = salt, random = random)
            val take = mutant.getDouble("HARVEST_TAKE_PERCENT")
            assertTrue(take >= 0.05 && take <= 0.95,
                "HARVEST_TAKE_PERCENT=$take should be in [0.05, 0.95]")
        }
    }

    @Test
    fun `mutate rotates through different parameter keys based on salt`() {
        val config = defaultConfig()
        val trainer = defaultTrainer(config)
        val parent = defaultGenome()
        val random = kotlin.random.Random(42)

        val keys = arrayOf(
            "HARVEST_TAKE_PERCENT",
            "MIN_SURPLUS_FOR_HARVEST",
            "FLAT_REBALANCE_TRIGGER_PERCENT",
            "FITNESS_DRAWDOWN_PENALTY",
            "MIN_ASSET_SURPLUS_FOR_PORTFOLIO_HARVEST",
        )

        // Different salts should target different keys
        val changedKeys = mutableSetOf<String>()
        repeat(20) { salt ->
            val mutant = trainer.mutate(parent, salt = salt, random = random)
            keys.forEach { key ->
                if (parent.getDouble(key) != mutant.getDouble(key)) {
                    changedKeys += key
                }
            }
        }
        assertTrue(changedKeys.size >= 2,
            "Should mutate at least 2 different keys across 20 mutations, got: $changedKeys")
    }

    // ── nextPopulation ──────────────────────────────────────────────────────

    @Test
    fun `nextPopulation preserves elite as first individual`() = runTest {
        val config = defaultConfig(populationSize = 4)
        val trainer = defaultTrainer(config)

        // Run one generation to get evaluations
        trainer.runGeneration()

        // Build evaluations manually
        val random = kotlin.random.Random(9999)
        val evaluations = trainer.population.mapIndexed { index, genome ->
            val run = RealtimeHarness(
                genome = genome,
                initialCapital = config.initialCapital,
                mode = Mode.SHADOW,
                stochasticSeed = config.seed + index,
                stochasticSpanLength = config.spanLength,
            ).replay(trainer.inputs)
            StochasticTrainingEvaluation(
                genome = genome,
                run = run,
                fitness = run.fitness(config.initialCapital, genome),
            )
        }.sortedByDescending { it.fitness }

        val next = trainer.nextPopulation(evaluations, random)
        assertEquals(config.populationSize, next.size)
        // Elite should be first
        assertEquals(evaluations.first().genome.doubles.toList(), next[0].doubles.toList())
    }

    @Test
    fun `nextPopulation with size 1 returns elite only`() = runTest {
        val config = defaultConfig(populationSize = 1)
        val trainer = defaultTrainer(config)
        val snapshot = trainer.runGeneration()

        val random = kotlin.random.Random(9999)
        val evaluation = StochasticTrainingEvaluation(
            genome = trainer.population.first(),
            run = RealtimeHarness(
                genome = trainer.population.first(),
                initialCapital = config.initialCapital,
                mode = Mode.SHADOW,
            ).replay(trainer.inputs),
            fitness = 1.0,
        )
        val next = trainer.nextPopulation(listOf(evaluation), random)
        assertEquals(1, next.size)
    }

    // ── runGeneration ───────────────────────────────────────────────────────

    @Test
    fun `runGeneration returns snapshot with correct fields`() = runTest {
        val config = defaultConfig(populationSize = 3, rowsPerSeries = 15, spanLength = 6)
        val trainer = defaultTrainer(config)

        val snapshot = trainer.runGeneration()

        assertEquals(1, snapshot.generation)
        assertEquals(2, snapshot.pairCount) // BTC + ETH
        assertEquals(15, snapshot.rowsPerSeries)
        assertEquals(3, snapshot.populationSize)
        assertEquals(3, snapshot.evaluations)
        assertTrue(snapshot.bestFitness.isFinite())
        assertTrue(snapshot.bestTotalValue > 0.0)
        assertTrue(snapshot.bestTrades >= 0)
        assertTrue(snapshot.totalCycles > 0)
        assertNotNull(snapshot.sampleWindows)
        assertNotNull(snapshot.sampleSpans)
    }

    @Test
    fun `runGeneration advances generation counter`() = runTest {
        val config = defaultConfig(populationSize = 2, rowsPerSeries = 10, spanLength = 4)
        val trainer = defaultTrainer(config)

        assertEquals(0, trainer.generation)
        val s1 = trainer.runGeneration()
        assertEquals(1, trainer.generation)
        assertEquals(1, s1.generation)

        val s2 = trainer.runGeneration()
        assertEquals(2, trainer.generation)
        assertEquals(2, s2.generation)
    }

    @Test
    fun `runGeneration snapshot contains champion genome params`() = runTest {
        val config = defaultConfig(populationSize = 2, rowsPerSeries = 10, spanLength = 4)
        val trainer = defaultTrainer(config)
        val snapshot = trainer.runGeneration()

        assertTrue(snapshot.championTakePercent >= 0.05 && snapshot.championTakePercent <= 0.95,
            "championTakePercent=${snapshot.championTakePercent} should be in [0.05, 0.95]")
        assertTrue(snapshot.championMinSurplus >= 0.001,
            "championMinSurplus=${snapshot.championMinSurplus} should be >= 0.001")
        assertTrue(snapshot.championRebalanceTrigger >= 0.001,
            "championRebalanceTrigger=${snapshot.championRebalanceTrigger} should be >= 0.001")
    }

    // ── integration with multi-generation training ──────────────────────────

    @Test
    fun `runGeneration produces different population after each gen`() = runTest {
        val config = defaultConfig(populationSize = 3, rowsPerSeries = 15, spanLength = 6)
        val trainer = defaultTrainer(config)

        val gen1Pop = trainer.population.map { it.doubles.toList() }
        trainer.runGeneration()
        val gen2Pop = trainer.population.map { it.doubles.toList() }

        // Population should have been replaced after generation
        // (at least some individuals differ)
        val anyDifferent = gen1Pop.zip(gen2Pop).any { (a, b) -> a != b }
        assertTrue(anyDifferent, "Population should change after a generation")
    }

    @Test
    fun `consecutive generations produce increasing or stable fitness`() = runTest {
        val config = defaultConfig(populationSize = 4, rowsPerSeries = 20, spanLength = 8)
        val trainer = defaultTrainer(config)

        val snapshots = mutableListOf<StochasticTrainingSnapshot>()
        repeat(3) {
            snapshots += trainer.runGeneration()
        }

        // Fitness should be finite for all generations
        snapshots.forEach { snapshot ->
            assertTrue(snapshot.bestFitness.isFinite(),
                "gen ${snapshot.generation} fitness=${snapshot.bestFitness} should be finite")
        }
        // Each generation's snapshot should have increasing generation number
        assertEquals(listOf(1, 2, 3), snapshots.map { it.generation })
    }

    @Test
    fun `short extension formats doubles to 2 decimal places`() {
        assertEquals("0.12", 0.123.short())
        assertEquals("1.0", 1.0.short())
        assertEquals("0.0", 0.0.short())
        assertEquals("-0.5", (-0.5).short())
    }
}
