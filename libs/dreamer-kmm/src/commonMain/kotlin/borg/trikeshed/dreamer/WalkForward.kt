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

/**
 * Result of an evolved walk-forward validation.
 *
 * Like [WalkForwardResult] but each pair's test window uses the *evolved champion genome*
 * from the preceding training window — true out-of-sample validation with adaptive parameters.
 *
 * @property championGenomes the champion genome for each train window pair
 */
data class EvolvedWalkForwardResult(
    val windowSize: Int,
    val stepSize: Int,
    val pairCount: Int,
    val trainReports: List<BacktestReport>,
    val testReports: List<BacktestReport>,
    val trainAggregate: BacktestAggregateReport,
    val testAggregate: BacktestAggregateReport,
    val championGenomes: List<Genome>,
)

/**
 * Run an evolved walk-forward validation: true out-of-sample with adaptive parameters.
 *
 * For each consecutive window pair (N, N+1):
 * 1. Window N klines → build [KlineBlock] → [HarnessReplayInput]
 * 2. Run [StochasticBagSpanTrainer] for [trainingGenerations] on window N
 * 3. Take the **evolved champion genome** from the trainer
 * 4. Test that champion on window N+1 via [klinesToBacktestResult]
 *
 * This is the gold standard for stochastic strategy validation: it measures
 * whether the evolved genome generalizes to unseen data.
 *
 * @param klines              historical bars (original order, single symbol)
 * @param initialCapital      starting portfolio value per window
 * @param windowSize          bars per window
 * @param stepSize            bars to advance between windows
 * @param trainingConfig      stochastic training configuration (population, span, seed)
 * @param trainingGenerations number of evolutionary generations per window
 * @param mode                engine mode
 * @return [EvolvedWalkForwardResult] with evolved champions and out-of-sample reports
 */
suspend fun evolvedWalkForwardValidation(
    klines: List<Kline>,
    initialCapital: Double,
    windowSize: Int,
    stepSize: Int = windowSize,
    trainingConfig: StochasticTrainingConfig,
    trainingGenerations: Int = 3,
    mode: Mode = Mode.SHADOW,
): EvolvedWalkForwardResult {
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

    if (windows.size < 2) {
        return EvolvedWalkForwardResult(
            windowSize = windowSize,
            stepSize = stepSize,
            pairCount = 0,
            trainReports = emptyList(),
            testReports = emptyList(),
            trainAggregate = aggregateReports(emptyList()),
            testAggregate = aggregateReports(emptyList()),
            championGenomes = emptyList(),
        )
    }

    val trainReports = mutableListOf<BacktestReport>()
    val testReports = mutableListOf<BacktestReport>()
    val championGenomes = mutableListOf<Genome>()

    for (i in 0 until windows.size - 1) {
        val trainKlines = windows[i]

        // Build HarnessReplayInput from training window klines
        // Group by symbol to handle multi-symbol klines
        val bySymbol = trainKlines.groupBy { it.symbol }
        val inputs = bySymbol.map { (symbol, symbolKlines) ->
            val timespan = symbolKlines.first().timespan
            val key = klineSeriesKey(
                base = symbol.removeSuffix("USDT"),
                quote = "USDT",
                timespan = timespan,
            )
            val block = KlineBlock.mutable(timespan)
            symbolKlines.forEach { block.append(it) }
            HarnessReplayInput(key, block.seal())
        }

        // Evolve a champion on the training window
        val trainer = StochasticBagSpanTrainer(
            config = trainingConfig,
            inputs = inputs,
        )
        val trainingResult = trainer.runMultiGenerationTraining(trainingGenerations)
        val champion = trainingResult.championGenome
        championGenomes += champion

        // Training report: champion genome on training window
        val trainResult = klinesToBacktestResult(trainKlines, champion, initialCapital, mode)
        trainReports += trainResult.toBacktestReport()

        // Test report: evolved champion on the NEXT (unseen) window
        val testKlines = windows[i + 1]
        val testResult = klinesToBacktestResult(testKlines, champion, initialCapital, mode)
        testReports += testResult.toBacktestReport()
    }

    return EvolvedWalkForwardResult(
        windowSize = windowSize,
        stepSize = stepSize,
        pairCount = trainReports.size,
        trainReports = trainReports,
        testReports = testReports,
        trainAggregate = aggregateReports(trainReports),
        testAggregate = aggregateReports(testReports),
        championGenomes = championGenomes,
    )
}
