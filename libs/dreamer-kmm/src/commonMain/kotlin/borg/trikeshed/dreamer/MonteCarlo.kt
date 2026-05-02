package borg.trikeshed.dreamer

import kotlin.random.Random

/**
 * Result of a Monte Carlo permutation back-test.
 *
 * Runs the original backtest on historical bar order, then shuffles
 * the bar sequence N times and re-runs the simulation on each permutation.
 * The resulting distribution tests whether the strategy's returns are
 * statistically significant or could arise from random bar ordering.
 *
 * @property permutations  number of shuffled simulations run
 * @property originalReturn  total return from the original (unshuffled) bar order
 * @property originalSharpe  Sharpe ratio from the original bar order
 * @property meanReturn   mean total return across all permutations
 * @property medianReturn median total return across all permutations
 * @property p5Return     5th percentile of permuted returns
 * @property p95Return    95th percentile of permuted returns
 * @property pValue       fraction of permuted returns >= original return
 */
data class MonteCarloResult(
    val permutations: Int,
    val originalReturn: Double,
    val originalSharpe: Double,
    val meanReturn: Double,
    val medianReturn: Double,
    val p5Return: Double,
    val p95Return: Double,
    val pValue: Double,
)

/**
 * Run a single backtest simulation from a list of [Kline] bars.
 *
 * Creates a [KlineBlock], seals it, and runs [simulateTicks] with a fresh
 * [TradingEngine] configured from the given [genome] and [mode].
 *
 * @param klines          bars to simulate (order matters)
 * @param genome          trading engine configuration
 * @param initialCapital  starting portfolio value
 * @param mode            engine mode (default SHADOW)
 * @return [BacktestResult] with cycles and aggregate metrics
 */
suspend fun klinesToBacktestResult(
    klines: List<Kline>,
    genome: Genome,
    initialCapital: Double,
    mode: Mode = Mode.SHADOW,
): BacktestResult {
    val timespan = klines.first().timespan
    val block = KlineBlock.mutable(timespan)
    klines.forEach { block.append(it) }
    block.seal()
    val engine = TradingEngine(genome, mode, initialCapital = initialCapital)
    return simulateTicks(block.asCursor(), engine, initialCapital)
}

/**
 * Run a Monte Carlo permutation backtest.
 *
 * Executes the original backtest on the given [klines] in order, then
 * shuffles the bar sequence [numPermutations] times (using [seed] for
 * reproducibility) and runs a simulation on each permutation.
 *
 * The returned [MonteCarloResult] contains the original return/Sharpe,
 * the distribution of permuted returns (mean, median, p5, p95), and a
 * p-value: the fraction of permuted returns that meet or exceed the
 * original return.
 *
 * A low p-value (e.g. < 0.05) suggests the strategy captures genuine
 * signal rather than noise; a high p-value suggests the returns are
 * indistinguishable from random bar ordering.
 *
 * @param klines           historical bars (original order)
 * @param genome           trading engine configuration
 * @param initialCapital   starting portfolio value
 * @param numPermutations  number of shuffled simulations (default 100)
 * @param seed             random seed for reproducibility
 * @param mode             engine mode (default SHADOW)
 */
suspend fun monteCarloPermutationBacktest(
    klines: List<Kline>,
    genome: Genome,
    initialCapital: Double,
    numPermutations: Int = 100,
    seed: Int = 42,
    mode: Mode = Mode.SHADOW,
): MonteCarloResult {
    require(numPermutations >= 1) { "numPermutations must be >= 1" }
    require(klines.isNotEmpty()) { "klines must not be empty" }

    val random = Random(seed)

    // Run original (unshuffled) simulation
    val originalResult = klinesToBacktestResult(klines, genome, initialCapital, mode)
    val originalReport = originalResult.toBacktestReport()

    // Run permuted simulations
    val permutedReturns = DoubleArray(numPermutations)
    for (i in 0 until numPermutations) {
        val shuffled = klines.shuffled(random)
        val result = klinesToBacktestResult(shuffled, genome, initialCapital, mode)
        permutedReturns[i] = result.toBacktestReport().totalReturn
    }

    // Compute distribution statistics
    permutedReturns.sort()
    val pValue = permutedReturns.count { it >= originalReport.totalReturn }.toDouble() / numPermutations

    return MonteCarloResult(
        permutations = numPermutations,
        originalReturn = originalReport.totalReturn,
        originalSharpe = originalReport.sharpeRatio,
        meanReturn = permutedReturns.average(),
        medianReturn = permutedReturns[numPermutations / 2],
        p5Return = permutedReturns[(numPermutations * 0.05).toInt().coerceIn(0, numPermutations - 1)],
        p95Return = permutedReturns[(numPermutations * 0.95).toInt().coerceIn(0, numPermutations - 1)],
        pValue = pValue,
    )
}
