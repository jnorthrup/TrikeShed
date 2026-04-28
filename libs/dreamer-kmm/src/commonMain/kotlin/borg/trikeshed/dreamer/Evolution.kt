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
