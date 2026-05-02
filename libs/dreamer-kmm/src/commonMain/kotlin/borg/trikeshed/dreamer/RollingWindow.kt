package borg.trikeshed.dreamer

import kotlin.math.max

/**
 * Result of a rolling window back-test.
 *
 * Splits a kline series into overlapping windows and runs a full
 * [simulateTicks] simulation on each window independently. Produces
 * one [BacktestReport] per window and an [aggregateReport] across all windows.
 *
 * This is the foundation for walk-forward validation: train on one window,
 * test on the next, and measure out-of-sample performance.
 *
 * @property windowSize    bars per window
 * @property stepSize      bars to advance between windows
 * @property windowCount   number of windows produced
 * @property reports       one [BacktestReport] per window
 * @property aggregate     rolled-up aggregate across all windows
 */
data class RollingWindowResult(
    val windowSize: Int,
    val stepSize: Int,
    val windowCount: Int,
    val reports: List<BacktestReport>,
    val aggregate: BacktestAggregateReport,
)

/**
 * Run a rolling window backtest over a list of [Kline] bars.
 *
 * Creates overlapping windows of [windowSize] bars, advancing by [stepSize]
 * bars between windows. Each window runs an independent [simulateTicks]
 * simulation with a fresh [TradingEngine].
 *
 * @param klines           historical bars (original order)
 * @param genome           trading engine configuration
 * @param initialCapital   starting portfolio value per window
 * @param windowSize       number of bars per window
 * @param stepSize         bars to advance between windows (default = windowSize for non-overlapping)
 * @param mode             engine mode (default SHADOW)
 * @return [RollingWindowResult] with per-window reports and aggregate
 */
suspend fun rollingWindowBacktest(
    klines: List<Kline>,
    genome: Genome,
    initialCapital: Double,
    windowSize: Int,
    stepSize: Int = windowSize,
    mode: Mode = Mode.SHADOW,
): RollingWindowResult {
    require(klines.isNotEmpty()) { "klines must not be empty" }
    require(windowSize > 0) { "windowSize must be > 0" }
    require(stepSize > 0) { "stepSize must be > 0" }

    val reports = mutableListOf<BacktestReport>()
    var start = 0
    while (start + windowSize <= klines.size) {
        val windowKlines = klines.subList(start, start + windowSize)
        val result = klinesToBacktestResult(windowKlines, genome, initialCapital, mode)
        reports.add(result.toBacktestReport())
        start += stepSize
    }

    val aggregate = aggregateReports(reports)
    return RollingWindowResult(
        windowSize = windowSize,
        stepSize = stepSize,
        windowCount = reports.size,
        reports = reports,
        aggregate = aggregate,
    )
}

/**
 * Compute the number of windows that [rollingWindowBacktest] would produce
 * for given data size, window size, and step size.
 */
fun windowCount(dataSize: Int, windowSize: Int, stepSize: Int = windowSize): Int {
    if (dataSize < windowSize || windowSize <= 0 || stepSize <= 0) return 0
    var count = 0
    var start = 0
    while (start + windowSize <= dataSize) {
        count++
        start += stepSize
    }
    return count
}
