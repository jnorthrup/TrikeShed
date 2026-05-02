package borg.trikeshed.dreamer

/**
 * Result of a walk-forward validation backtest.
 *
 * Pairs consecutive windows: train on window N, test on window N+1.
 * Each test window uses a fresh engine (no state leakage from training).
 *
 * This is the gold standard for backtest validation: it measures
 * out-of-sample performance on data the strategy was NOT trained on.
 *
 * @property windowSize       bars per window
 * @property stepSize         bars between window starts
 * @property pairCount        number of train/test pairs produced
 * @property trainReports     one [BacktestReport] per training window
 * @property testReports      one [BacktestReport] per test window (out-of-sample)
 * @property trainAggregate   aggregate across all training windows
 * @property testAggregate    aggregate across all test windows (out-of-sample)
 */
data class WalkForwardResult(
    val windowSize: Int,
    val stepSize: Int,
    val pairCount: Int,
    val trainReports: List<BacktestReport>,
    val testReports: List<BacktestReport>,
    val trainAggregate: BacktestAggregateReport,
    val testAggregate: BacktestAggregateReport,
)

/**
 * Run a walk-forward validation backtest.
 *
 * Splits [klines] into overlapping windows of [windowSize] bars advancing
 * by [stepSize]. For each consecutive pair (N, N+1):
 * - Window N is the **training** window (in-sample)
 * - Window N+1 is the **testing** window (out-of-sample)
 *
 * Both windows run with a fresh [TradingEngine] configured from [genome].
 * The test window's performance is the out-of-sample metric.
 *
 * @param klines           historical bars (original order)
 * @param genome           trading engine configuration
 * @param initialCapital   starting portfolio value per window
 * @param windowSize       bars per window
 * @param stepSize         bars to advance between windows (default = windowSize)
 * @param mode             engine mode (default SHADOW)
 * @return [WalkForwardResult] with train/test reports and aggregates
 */
suspend fun walkForwardValidation(
    klines: List<Kline>,
    genome: Genome,
    initialCapital: Double,
    windowSize: Int,
    stepSize: Int = windowSize,
    mode: Mode = Mode.SHADOW,
): WalkForwardResult {
    require(klines.isNotEmpty()) { "klines must not be empty" }
    require(windowSize > 0) { "windowSize must be > 0" }
    require(stepSize > 0) { "stepSize must be > 0" }

    // Collect all window slices
    val windows = mutableListOf<List<Kline>>()
    var start = 0
    while (start + windowSize <= klines.size) {
        windows.add(klines.subList(start, start + windowSize))
        start += stepSize
    }

    // Pair consecutive windows: (train=0, test=1), (train=1, test=2), ...
    val trainReports = mutableListOf<BacktestReport>()
    val testReports = mutableListOf<BacktestReport>()

    for (i in 0 until windows.size - 1) {
        val trainResult = klinesToBacktestResult(windows[i], genome, initialCapital, mode)
        trainReports.add(trainResult.toBacktestReport())

        val testResult = klinesToBacktestResult(windows[i + 1], genome, initialCapital, mode)
        testReports.add(testResult.toBacktestReport())
    }

    return WalkForwardResult(
        windowSize = windowSize,
        stepSize = stepSize,
        pairCount = trainReports.size,
        trainReports = trainReports,
        testReports = testReports,
        trainAggregate = aggregateReports(trainReports),
        testAggregate = aggregateReports(testReports),
    )
}
