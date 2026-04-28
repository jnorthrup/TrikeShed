package borg.trikeshed.dreamer

import kotlin.math.abs
import kotlin.random.Random

/**
 * Genome evolution: genetic algorithm for the trading engine.
 *
 * Architecture role:
 *   Genome (genotype) → TradingEngine (phenotype) → BacktestResult → fitness score
 *   → Selection / Crossover / Mutation → next generation
 *
 * Pipeline:
 *   1. Evaluate population: for each genome, run simulateTicks → score by BacktestMetrics
 *   2. Select: tournament or rank-based selection
 *   3. Crossover: combine two parent genomes into two children
 *   4. Mutate: Gaussian noise on numerics, flip on booleans
 *   5. Elitism: carry top-N unchanged into next generation
 *
 * @param population list of genomes to evolve
 * @param fitness   map of genome identity (index) to fitness score (higher = better)
 * @param eliteCount number of top genomes to carry over unchanged
 */
fun evolvePopulation(
    population: List<Genome>,
    fitness: List<Double>,
    eliteCount: Int = 2,
    mutationRate: Double = 0.1,
    mutationSigma: Double = 0.05,
    random: Random = Random,
): List<Genome> {
    require(population.isNotEmpty()) { "Population must not be empty" }
    require(fitness.size == population.size) { "Fitness list must match population size" }

    val sorted = population.indices.sortedByDescending { fitness[it] }
    val elite = sorted.take(eliteCount).map { population[it] }

    val offspring = mutableListOf<Genome>()
    while (offspring.size < population.size - eliteCount) {
        val p1 = tournamentSelect(population, fitness, tournamentSize = 3, random)
        val p2 = tournamentSelect(population, fitness, tournamentSize = 3, random)

        val (c1, c2) = crossover(p1, p2, random)
        offspring.add(mutate(c1, mutationRate, mutationSigma, random))
        offspring.add(mutate(c2, mutationRate, mutationSigma, random))
    }

    return (elite + offspring.take(population.size - eliteCount))
}

/**
 * Tournament selection: pick the best from a random sample of [tournamentSize].
 */
fun tournamentSelect(
    population: List<Genome>,
    fitness: List<Double>,
    tournamentSize: Int = 3,
    random: Random = Random,
): Genome {
    require(population.isNotEmpty())
    val selected = (0 until minOf(tournamentSize, population.size))
        .map { random.nextInt(population.size) }
        .maxByOrNull { fitness[it] } ?: 0
    return population[selected]
}

/**
 * Uniform crossover: for each key, randomly pick from p1 or p2.
 * Returns two children.
 */
fun crossover(p1: Genome, p2: Genome, random: Random): Pair<Genome, Genome> {
    val keys = (p1.backing.keys + p2.backing.keys).toSet()
    val child1Backing = mutableMapOf<String, Any?>()
    val child2Backing = mutableMapOf<String, Any?>()

    for (key in keys) {
        if (random.nextBoolean()) {
            child1Backing[key] = p1[key]
            child2Backing[key] = p2[key]
        } else {
            child1Backing[key] = p2[key]
            child2Backing[key] = p1[key]
        }
    }

    // Always carry overrides specially
    val overrides1 = p1.backing["overrides"] as? MutableMap<String, MutableMap<String, Any?>>
    val overrides2 = p2.backing["overrides"] as? MutableMap<String, MutableMap<String, Any?>>
    if (overrides1 != null || overrides2 != null) {
        child1Backing["overrides"] = if (random.nextBoolean()) overrides1 else overrides2
        child2Backing["overrides"] = if (random.nextBoolean()) overrides1 else overrides2
    }

    return Genome(child1Backing) to Genome(child2Backing)
}

/**
 * Mutate a genome in-place (Gaussian noise for numbers, flip for booleans).
 *
 * @param genome        the genome to mutate
 * @param rate          per-key probability of mutation (default 10%)
 * @param sigma         Gaussian sigma as fraction of value (default 5%)
 * @param random        random source
 * @return the same genome (mutated in place)
 */
fun mutate(
    genome: Genome,
    rate: Double = 0.1,
    sigma: Double = 0.05,
    random: Random = Random,
): Genome {
    for (key in genome.backing.keys.toList()) {
        if (random.nextDouble() > rate) continue
        val value = genome[key]
        val mutated = when (value) {
            is Double -> {
                val noise = value * sigma * random.nextGaussian()
                (value + noise).coerceAtLeast(0.0)
            }
            is Int -> (value + (random.nextGaussian() * abs(value) * sigma).toInt()).coerceAtLeast(0)
            is Boolean -> !value
            is Long -> (value + (random.nextGaussian() * abs(value.toDouble()) * sigma).toLong()).coerceAtLeast(0L)
            is String -> value // strings left unchanged
            else -> value
        }
        genome[key] = mutated
    }
    return genome
}

/**
 * Create a random genome with sensible default ranges.
 */
fun randomGenome(random: Random = Random): Genome {
    val g = Genome(mutableMapOf())
    g["FLAT_HARVEST_TRIGGER_PERCENT"] = random.nextDouble(0.01, 0.10)
    g["FLAT_REBALANCE_TRIGGER_PERCENT"] = random.nextDouble(0.01, 0.10)
    g["HARVEST_TAKE_PERCENT"] = random.nextDouble(0.30, 0.90)
    g["FORCED_HARVEST_TIMEOUT"] = random.nextLong(15 * 60_000, 120 * 60_000)
    g["REFRESH_INTERVAL"] = random.nextLong(5_000, 30_000)
    g["ENABLE_PORTFOLIO_HARVEST"] = random.nextBoolean()
    g["MIN_ASSET_SURPLUS_FOR_PORTFOLIO_HARVEST"] = random.nextDouble(0.05, 0.30)
    g["MIN_SURPLUS_FOR_HARVEST"] = random.nextDouble(0.10, 0.60)
    g["MIN_SURPLUS_FOR_FORCED_HARVEST"] = random.nextDouble(0.50, 2.00)
    g["MIN_PARTIAL_REBALANCE_USD"] = random.nextDouble(0.10, 1.00)
    g["MAX_REBALANCE_ATTEMPTS"] = random.nextInt(1, 5)
    g["REBALANCE_COOLDOWN"] = random.nextLong(10 * 60_000, 60 * 60_000)
    g["CRASH_FUND_THRESHOLD_PERCENT"] = random.nextDouble(0.05, 0.20)
    g["ENABLE_CRASH_PROTECTION"] = random.nextBoolean()
    g["CP_TRIGGER_ASSET_PERCENT"] = random.nextDouble(0.50, 0.90)
    g["CP_TRIGGER_MIN_NEGATIVE_DEV_PERCENT"] = -random.nextDouble(0.03, 0.15)
    return g
}

/**
 * Evaluate a population of genomes against a cursor and return fitness scores.
 *
 * Fitness = totalReturn * 100 + sharpeRatio * 10 - maxDrawdown * 50
 * (Balances return, risk-adjusted gain, and drawdown protection)
 *
 * @param population  list of genomes to evaluate
 * @param cursor      pre-built MiniCursor of kline bars
 * @param initialCapital starting cash
 * @return list of fitness scores (same order as population)
 */
suspend fun evaluatePopulation(
    population: List<Genome>,
    cursor: borg.trikeshed.miniduck.MiniCursor,
    initialCapital: Double = 10_000.0,
): List<Double> = population.map { genome ->
    val engine = TradingEngine(genome, Mode.SHADOW, initialCapital = initialCapital)
    val result = simulateTicks(cursor, engine, initialCapital)
    fitnessFromResult(result)
}

/**
 * Compute a scalar fitness score from a backtest result.
 */
fun fitnessFromResult(result: BacktestResult): Double {
    val m = result.metrics
    return m.totalReturn * 100.0 + m.sharpeRatio * 10.0 - m.maxDrawdown * 50.0
}

/**
 * Run one complete evolution generation.
 *
 * @return Pair of (evolved population, top fitness score)
 */
suspend fun runGeneration(
    population: List<Genome>,
    cursor: borg.trikeshed.miniduck.MiniCursor,
    initialCapital: Double = 10_000.0,
    eliteCount: Int = 2,
    mutationRate: Double = 0.1,
    random: Random = Random,
): Pair<List<Genome>, Double> {
    val fitness = evaluatePopulation(population, cursor, initialCapital)
    val evolved = evolvePopulation(population, fitness, eliteCount, mutationRate, random = random)
    val topFitness = fitness.maxOrNull() ?: 0.0
    return evolved to topFitness
}

private fun Random.nextGaussian(): Double {
    // Box-Muller transform
    val u1 = nextDouble(1e-10, 1.0)
    val u2 = nextDouble(1e-10, 1.0)
    return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * kotlin.math.PI * u2)
}
