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
    val keys = (left.backing.keys + right.backing.keys).distinct().sorted()
    val pivot = keys.size / 2
    val child = Genome(mutableMapOf())
    keys.forEachIndexed { index, key ->
        val source = if (index < pivot) left else right
        val fallback = if (index < pivot) right else left
        child[key] = source.backing[key] ?: fallback.backing[key]
    }
    return child
}

/** Copy a genome and apply additive numeric deltas for mutation/search steps. */
fun mutateGenome(parent: Genome, deltas: Map<String, Double>): Genome {
    val mutant = Genome(parent.backing.toMutableMap())
    deltas.forEach { (key, delta) ->
        val current = mutant.backing[key]
        mutant[key] = when (current) {
            is Number -> current.toDouble() + delta
            else -> delta
        }
    }
    return mutant
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
