package borg.trikeshed.dreamer

import borg.trikeshed.lib.*

/**
 * Comprehensive back-test diagnostic report combining all analysis dimensions.
 *
 * A [ComprehensiveBacktestReport] aggregates:
 * - Basic report from simulateTicks
 * - Equity curve metrics (win rate, profit factor, Calmar, etc.)
 * - Monte Carlo permutation test results (if requested)
 * - Walk-forward out-of-sample evaluation (if requested)
 *
 * This is the top-level report for a full stochastic back-test analysis.
 */
data class ComprehensiveBacktestReport(
    val report: BacktestReport,
    val equityMetrics: EquityMetrics,
    val monteCarlo: MonteCarloResult? = null,
    val walkForward: WalkForwardResult? = null,
)

/**
 * Equity curve metrics extracted from the cycle series.
 */
data class EquityMetrics(
    val winRate: Double,
    val profitFactor: Double,
    val maxConsecutiveLosses: Int,
    val avgDrawdown: Double,
    val calmarRatio: Double,
)

/**
 * Extract [EquityMetrics] from a [BacktestResult]'s cycle series.
 */
fun BacktestResult.equityMetrics(initialCapital: Double = this.initialCapital): EquityMetrics =
    EquityMetrics(
        winRate = cycles.winRate(),
        profitFactor = cycles.profitFactor(),
        maxConsecutiveLosses = cycles.maxConsecutiveLosses(),
        avgDrawdown = cycles.avgDrawdown(),
        calmarRatio = cycles.calmarRatio(initialCapital),
    )

/**
 * Run a comprehensive back-test analysis.
 *
 * Executes [simulateTicks] and then layers on equity curve analysis,
 * optional Monte Carlo permutation testing, and optional walk-forward
 * validation.
 *
 * @param klines           historical bars
 * @param genome           trading engine configuration
 * @param initialCapital   starting portfolio value
 * @param monteCarlo       run Monte Carlo permutation test (null = skip)
 * @param walkForward      run walk-forward validation (null = skip)
 * @param mode             engine mode
 * @return [ComprehensiveBacktestReport] with all analysis dimensions
 */
suspend fun comprehensiveBacktest(
    klines: List<Kline>,
    genome: Genome,
    initialCapital: Double,
    monteCarlo: MonteCarloConfig? = null,
    walkForward: WalkForwardConfig? = null,
    mode: Mode = Mode.SHADOW,
): ComprehensiveBacktestReport {
    val result = klinesToBacktestResult(klines, genome, initialCapital, mode)
    val report = result.toBacktestReport()
    val eqMetrics = result.equityMetrics(initialCapital)

    val mcResult = monteCarlo?.let { config ->
        monteCarloPermutationBacktest(
            klines = klines,
            genome = genome,
            initialCapital = initialCapital,
            numPermutations = config.numPermutations,
            seed = config.seed,
            mode = mode,
        )
    }

    val wfResult = walkForward?.let { config ->
        walkForwardValidation(
            klines = klines,
            genome = genome,
            initialCapital = initialCapital,
            windowSize = config.windowSize,
            stepSize = config.stepSize,
            mode = mode,
        )
    }

    return ComprehensiveBacktestReport(
        report = report,
        equityMetrics = eqMetrics,
        monteCarlo = mcResult,
        walkForward = wfResult,
    )
}

data class MonteCarloConfig(
    val numPermutations: Int = 100,
    val seed: Int = 42,
)

data class WalkForwardConfig(
    val windowSize: Int,
    val stepSize: Int = windowSize,
)
