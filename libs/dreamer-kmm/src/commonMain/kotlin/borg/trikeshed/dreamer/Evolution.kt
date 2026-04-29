package borg.trikeshed.dreamer

import borg.trikeshed.couch.kline.TimeSpan

/** One genome's replay output and scalar score for evolutionary search. */
data class GenomeEvaluation(
    val genome: Genome,
    val result: BacktestResult,
    val fitness: Double,
)

/**
 * Stochastic fitness scalar over a back-test result.
 * Rewards return, Sharpe, and downside-volatility-adjusted Sortino while penalizing drawdown.
 */
fun computeStochasticFitness(result: BacktestResult): Double =
    result.metrics.totalReturn +
        result.metrics.sharpeRatio +
        result.metrics.sortinoRatio -
        result.metrics.maxDrawdown

/**
 * Minimal stochastic fitness scalar over a back-test result.
 * Rewards return and risk-adjusted upside while penalizing drawdown.
 */
fun fitnessFromResult(result: BacktestResult): Double = computeStochasticFitness(result)

/** Deterministic one-point crossover over sorted genome keys. */
fun crossoverGenome(left: Genome, right: Genome): Genome {
    val out = DoubleArray(Genome.WIDTH)
    val pivot = Genome.WIDTH / 2
    for (i in 0 until Genome.WIDTH) {
        out[i] = if (i < pivot) left.doubles[i] else right.doubles[i]
    }

    val child = Genome(out)
    val keys = (left.backing.keys + right.backing.keys).distinct().sorted()
    val keyPivot = keys.size / 2
    keys.forEachIndexed { index, key ->
        val source = if (index < keyPivot) left else right
        val fallback = if (index < keyPivot) right else left
        child[key] = source.backing[key] ?: fallback.backing[key] ?: source[key] ?: fallback[key]
    }
    return child
}

/** Copy a genome and apply additive numeric deltas for mutation/search steps. */
fun mutateGenome(parent: Genome, deltas: Map<String, Double>): Genome {
    val mutant = parent.copyGenome()
    deltas.forEach { (key, delta) ->
        val current = mutant[key]
        mutant[key] = when (current) {
            is Number -> current.toDouble() + delta
            else -> delta
        }
    }
    return mutant
}

/** Strongest stochastic back-test evaluations first, preserving evaluation records. */
fun rankEvaluationsByFitness(evaluations: List<GenomeEvaluation>): List<GenomeEvaluation> =
    evaluations.sortedByDescending { it.fitness }

/**
 * Build the next stochastic generation from evaluated parents.
 * The fittest genome is kept as elite; remaining slots are crossover children of the top parents.
 */
fun evolvePopulation(
    evaluations: List<GenomeEvaluation>,
    mutationDeltas: Map<String, Double> = emptyMap(),
): List<Genome> {
    val ranked = rankEvaluationsByFitness(evaluations)
    if (ranked.isEmpty()) return emptyList()
    if (ranked.size == 1) return listOf(ranked[0].genome)

    val elite = ranked[0].genome
    val mate = ranked[1].genome
    val next = mutableListOf(elite)
    while (next.size < ranked.size) {
        next += mutateGenome(crossoverGenome(elite, mate), mutationDeltas)
    }
    return next
}

/** Replay each genome over the same archive data and produce evaluation records. */
suspend fun evaluatePopulation(
    genomes: List<Genome>,
    csvText: String,
    symbol: String,
    timespan: TimeSpan,
    initialCapital: Double,
): List<GenomeEvaluation> = genomes.map { genome ->
    val result = SimulationReplay(
        genome = genome,
        mode = Mode.SHADOW,
        initialCapital = initialCapital,
    ).replayCsv(csvText, symbol, timespan)
    GenomeEvaluation(
        genome = genome,
        result = result,
        fitness = fitnessFromResult(result),
    )
}
