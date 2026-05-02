package borg.trikeshed.dreamer

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.doubleValue
import borg.trikeshed.cursor.longValue
import borg.trikeshed.cursor.stringValue
import borg.trikeshed.cursor.at
import borg.trikeshed.lib.*
import kotlin.math.sqrt

/**
 * Back-test input derived from a single kline bar.

 *
 * [PortfolioInput] is what the [TradingEngine] consumes on each cycle:
 * a list of [PortfolioRow] one per symbol, with current price and quantity.
 *
 * The adapter [klineBarToPortfolioInput] converts a cursor row
 * projected from a [KlineBlock] into this form.
 */
data class PortfolioInput(
    val symbol: String,
    val openTime: Long,
    val quantity: Double,
    val price: Double,
    val value: Double,
)

/**
 * Result of a single back-test cycle tick.
 *
 * [CycleResult] aggregates what happened in one step: cash, holdings value,
 * whether any trades fired, the harvested amount, and the new engine state.
 */
data class CycleResult(
    val tick: Int,
    val openTime: Long,
    val cashBalance: Double,
    val holdingsValue: Double,
    val totalValue: Double,
    val anyTradesThisCycle: Boolean,
    val harvestedAmount: Double,
    val tradedSymbols: List<String>,
    val rebalanceScheduled: Boolean,
    val engineSnapshot: Map<String, Any?>,
)

/**
 * Aggregate back-test metrics computed over all ticks.
 */
data class BacktestMetrics(
    val totalTicks: Int,
    val totalReturn: Double,        // pct: (final - initial) / initial
    val sharpeRatio: Double,        // annualized: mean(ret) / std(ret) * sqrt(252)
    val sortinoRatio: Double = 0.0, // annualized: mean(ret) / downside deviation * sqrt(252)
    val maxDrawdown: Double,        // pct: max peak - trough / peak
    val maxDrawdownTicks: Int,      // ticks in drawdown
    val totalHarvested: Double,
    val totalTrades: Int,
    val avgHarvestPerTick: Double,
)

/**
 * Full back-test result: time-series of cycle results plus aggregate metrics.
 */
data class BacktestResult(
    val symbol: String,
    val initialCapital: Double,
    val cycles: Series<CycleResult>,
    val metrics: BacktestMetrics,
)

/** Stable scalar report over a [BacktestResult] for back-test summaries. */
data class BacktestReport(
    val symbol: String,
    val initialCapital: Double,
    val finalEquity: Double,
    val totalReturn: Double,
    val sharpeRatio: Double,
    val sortinoRatio: Double,
    val maxDrawdown: Double,
    val maxDrawdownTicks: Int,
    val totalTrades: Int,
    val totalHarvested: Double,
    val totalTicks: Int,
)

/** Build a stable summary layer from a back-test result plus its aggregate metrics. */
fun BacktestResult.toBacktestReport(): BacktestReport {
    val finalEquity = if (cycles.isNotEmpty()) cycles.last().totalValue else initialCapital
    return BacktestReport(
        symbol = symbol,
        initialCapital = initialCapital,
        finalEquity = finalEquity,
        totalReturn = metrics.totalReturn,
        sharpeRatio = metrics.sharpeRatio,
        sortinoRatio = metrics.sortinoRatio,
        maxDrawdown = metrics.maxDrawdown,
        maxDrawdownTicks = metrics.maxDrawdownTicks,
        totalTrades = metrics.totalTrades,
        totalHarvested = metrics.totalHarvested,
        totalTicks = metrics.totalTicks,
    )
}

/**
 * Convert a single cursor row (a kline bar) into a [PortfolioInput].
 *
 * The row must have keys: symbol, openTime, quantity, price.
 * For back-testing with a single symbol, quantity is derived from
 * initial capital / initial price on the first bar, then held constant.
 *
 * @param cursor   cursor of kline bars (row projections)
 * @param barIndex index of the bar to convert
 * @param currentQuantity quantity of the asset held (fixed for back-test simulation)
 */
fun klineBarToPortfolioInput(
    cursor: Cursor,
    barIndex: Int,
    currentQuantity: Double,
): PortfolioInput {
    val row = cursor.at(barIndex)
    val symbol: String = row.stringValue("symbol", "UNKNOWN")
    val openTime: Long = row.longValue("openTime")
    val price: Double = row.doubleValue("close").takeIf { it > 0.0 } ?: row.doubleValue("open")
    val value = currentQuantity * price
    return PortfolioInput(
        symbol = symbol,
        openTime = openTime,
        quantity = currentQuantity,
        price = price,
        value = value,
    )
}

/**
 * Build a list of [PortfolioRow] for the trading engine update call,
 * from a single [PortfolioInput].
 */
fun portfolioInputToRows(input: PortfolioInput): List<PortfolioRow> = listOf(
    PortfolioRow(
        Symbol = input.symbol,
        Quantity = input.quantity,
        Price = input.price,
        Value = input.value,
    )
)

/**
 * Convert a cursor row at [barIndex] into a [PortfolioInput].
 *
 * This is the single-symbol accessor for a flat cursor where each row is one
 * symbol's bar. For the current simulation model, each barIndex gives one
 * symbol's bar at that position. The cursor is expected to be filtered to a
 * single symbol for the standard back-test path; [klineBarToPortfolioInput]
 * is the preferred helper there.
 *
 * For true multi-symbol cursors (interleaved or multi-row per bar), use
 * [allSymbolsAtBar] to collect all symbol rows sharing the same openTime.
 *
 * @param cursor   cursor of kline bars (row projections)
 * @param barIndex index of the bar row to convert
 * @param holdings map of symbol → quantity (used to compute value)
 */
fun multiSymbolKlineToPortfolioInput(
    cursor: Cursor,
    barIndex: Int,
    holdings: Map<String, Double>,
): List<PortfolioInput> = allSymbolsAtBar(cursor, barIndex, holdings)

/**
 * Collect all [PortfolioInput] rows at the same bar position (openTime) as [barIndex].
 *
 * Scans the cursor to find all rows sharing the same [openTime] as the row at
 * [barIndex], returning one [PortfolioInput] per distinct symbol found.
 * Use this for true multi-symbol back-tests where the cursor holds rows for
 * multiple symbols at the same timestamp.
 *
 * @param cursor    cursor of kline bars (possibly interleaved symbols)
 * @param barIndex reference bar index; all rows with the same openTime are collected
 * @param holdings  map of symbol → quantity
 */
fun allSymbolsAtBar(
    cursor: Cursor,
    barIndex: Int,
    holdings: Map<String, Double>,
): List<PortfolioInput> {
    val refRow = cursor.at(barIndex)
    val refOpenTime: Long = refRow.longValue("openTime")

    return (0 until cursor.size).mapNotNull { i ->
        val row = cursor.at(i)
        if (row.longValue("openTime") != refOpenTime) return@mapNotNull null
        val symbol: String = row.stringValue("symbol", "UNKNOWN")
        val price: Double = row.doubleValue("close").takeIf { it > 0.0 } ?: row.doubleValue("open")
        val quantity: Double = holdings[symbol] ?: 0.0
        PortfolioInput(
            symbol = symbol,
            openTime = refOpenTime,
            quantity = quantity,
            price = price,
            value = quantity * price,
        )
    }.distinctBy { it.symbol }
}

/**
 * Project a cursor of kline rows into a flat [List] of close prices.
 * Convenience for building price arrays for metrics.
 */
fun closesFromCursor(cursor: Cursor): Series<Double> =
    cursor α { row ->
        row.doubleValue("close").takeIf { it > 0.0 } ?: row.doubleValue("open")
    }

/**
 * Compute aggregate [BacktestMetrics] from cycle results.
 */
fun computeBacktestMetrics(
    cycles: Series<CycleResult>,
    initialCapital: Double,
    closePrices: Series<Double>,
    annualizationFactor: Double = sqrt(252.0),
): BacktestMetrics {
    if (cycles.isEmpty()) {
        return BacktestMetrics(
            totalTicks = 0,
            totalReturn = 0.0,
            sharpeRatio = 0.0,
            sortinoRatio = 0.0,
            maxDrawdown = 0.0,
            maxDrawdownTicks = 0,
            totalHarvested = 0.0,
            totalTrades = 0,
            avgHarvestPerTick = 0.0,
        )
    }

    val totalTicks = cycles.size
    val finalTotal = if (cycles.isNotEmpty()) cycles.last().totalValue else initialCapital
    val totalReturn = if (initialCapital > 0.0) (finalTotal - initialCapital) / initialCapital else 0.0

    // Daily/cycle returns for Sharpe
    val returns: Series<Double> = cycles.zipWithNext() α { (prev, curr) ->
        if (prev.totalValue > 0.0) (curr.totalValue - prev.totalValue) / prev.totalValue else 0.0
    }

    val meanReturn = if (returns.isNotEmpty()) {
        var sum = 0.0
        returns.view.forEach { sum += it }
        sum / returns.size
    } else 0.0
    val variance = if (returns.size > 1) {
        var sumSq = 0.0
        returns.view.forEach { val diff = it - meanReturn; sumSq += diff * diff }
        sumSq / returns.size
    } else 0.0
    val stdReturn = sqrt(if (variance < 0) 0.0 else variance)
    // Annualized Sharpe using provided annualization factor (default sqrt(252) for daily bars)
    val sharpeRatio = if (stdReturn > 0.0 && meanReturn.isFinite()) (meanReturn / stdReturn) * annualizationFactor else 0.0

    val downsideVariance = if (returns.isNotEmpty()) {
        var sumDownSq = 0.0
        returns.view.forEach { if (it < 0.0) sumDownSq += it * it }
        sumDownSq / returns.size
    } else 0.0
    val downsideDeviation = sqrt(if (downsideVariance < 0) 0.0 else downsideVariance)
    // Annualized Sortino, using zero as the minimum acceptable cycle return.
    val sortinoRatio = if (downsideDeviation > 0.0 && meanReturn.isFinite()) (meanReturn / downsideDeviation) * annualizationFactor else 0.0

    // Max drawdown
    var peak = initialCapital
    var peakIndex = -1
    var maxDrawdown = 0.0
    var maxDrawdownTicks = 0
    cycles.view.forEachIndexed { index, cycle ->
        if (cycle.totalValue > peak) {
            peak = cycle.totalValue
            peakIndex = index
        } else if (peak > 0.0) {
            val drawdown = (peak - cycle.totalValue) / peak
            if (drawdown > maxDrawdown) {
                maxDrawdown = drawdown
                maxDrawdownTicks = index - peakIndex
            }
        }
    }

    var totalHarvested = 0.0
    var totalTrades = 0
    cycles.view.forEach {
        totalHarvested += it.harvestedAmount
        if (it.anyTradesThisCycle) totalTrades++
    }
    val avgHarvestPerTick = if (totalTicks > 0) totalHarvested / totalTicks else 0.0

    return BacktestMetrics(
        totalTicks = totalTicks,
        totalReturn = totalReturn,
        sharpeRatio = sharpeRatio,
        sortinoRatio = sortinoRatio,
        maxDrawdown = maxDrawdown,
        maxDrawdownTicks = maxDrawdownTicks,
        totalHarvested = totalHarvested,
        totalTrades = totalTrades,
        avgHarvestPerTick = avgHarvestPerTick,
    )
}

/**
 * Run a full back-test simulation over a MiniCursor of kline bars.
 *
 * Iterates each bar in the cursor, converts it to a [PortfolioInput] via
 * [klineBarToPortfolioInput], feeds it to the [TradingEngine], records the
 * resulting [CycleResult], and computes aggregate [BacktestMetrics] at the end.
 *
 * @param cursor    cursor of kline bars, from [KlineBlock.asCursor]
 * @param engine    pre-configured [TradingEngine] (genome + mode + capital)
 * @param initialCapital  starting portfolio value in USD
 * @param onCycle   optional callback invoked after each tick with the cycle result
 * @return [BacktestResult] with all cycles and aggregate metrics
 */
suspend fun simulateTicks(
    cursor: Cursor,
    engine: TradingEngine,
    initialCapital: Double,
    annualizationFactor: Double = sqrt(252.0),
    onCycle: (CycleResult) -> Unit = {},
): BacktestResult {
    val n = cursor.size
    if (n == 0) {
        return BacktestResult(
            symbol = "",
            initialCapital = initialCapital,
            cycles = emptySeries(),
            metrics = BacktestMetrics(
                totalTicks = 0,
                totalReturn = 0.0,
                sharpeRatio = 0.0,
                sortinoRatio = 0.0,
                maxDrawdown = 0.0,
                maxDrawdownTicks = 0,
                totalHarvested = 0.0,
                totalTrades = 0,
                avgHarvestPerTick = 0.0,
            ),
        )
    }

    // Derive symbol from the first bar
    val firstRow = cursor.at(0)
    val symbol: String = firstRow.stringValue("symbol", "UNKNOWN")

    // Derive initial quantity from initial capital / first close price
    val firstClose: Double = firstRow.doubleValue("close").takeIf { it > 0.0 } ?: firstRow.doubleValue("open").takeIf { it > 0.0 } ?: 1.0
    val initialQuantity = initialCapital / firstClose
    // Treat initialCapital as total starting portfolio value. Pre-fund holdings and adjust engine cash to avoid double-counting.
    val initialHoldingsValue = initialQuantity * firstClose
    engine.cashBalance = initialCapital - initialHoldingsValue

    val cycleArray = arrayOfNulls<CycleResult>(n)

    for (i in 0 until n) {
        val input = klineBarToPortfolioInput(cursor, i, currentQuantity = initialQuantity)
        val rows = portfolioInputToRows(input)

        // Current holdings value: original allocation + engine reinvested positions
        var holdingsValue = 0.0
        rows.forEach { holdingsValue += it.Value }
        // Add market value of reinvested positions tracked by the engine
        val price = input.price
        for ((sym, holding) in engine.holdings) {
            holdingsValue += holding.rawQuantity * price
        }
        val totalValue = holdingsValue + engine.cashBalance

        val result = engine.update(
            portfolioSummary = rows,
            api = null,
            cashBalanceIn = engine.cashBalance,
            holdingDetails = null,
        )

        val row = cursor.at(i)
        val openTime: Long = row.longValue("openTime")

        val cycle = CycleResult(
            tick = i,
            openTime = openTime,
            cashBalance = engine.cashBalance,
            holdingsValue = holdingsValue,
            totalValue = totalValue,
            anyTradesThisCycle = result.anyTradesThisCycle,
            harvestedAmount = result.harvestedAmount,
            tradedSymbols = result.tradedSymbols,
            rebalanceScheduled = engine.rebalanceState.isNotEmpty(),
            engineSnapshot = engine.getStateSnapshot(),
        )
        cycleArray[i] = cycle
        onCycle(cycle)
    }

    // Every slot is written by the loop above; build non-null array explicitly
    @Suppress("UNCHECKED_CAST")
    val nonNull = Array(n) { cycleArray[it]!! }
    val cycles: Series<CycleResult> = nonNull.toSeries()
    val closes = closesFromCursor(cursor)
    val metrics = computeBacktestMetrics(cycles, initialCapital, closes, annualizationFactor)

    return BacktestResult(
        symbol = symbol,
        initialCapital = initialCapital,
        cycles = cycles,
        metrics = metrics,
    )
}
/**
 * Run a full multi-symbol back-test simulation over a cursor of kline bars.
 *
 * Unlike [simulateTicks] which assumes one bar per tick (single-symbol),
 * this groups bars by `openTime` so each tick may have multiple symbol rows.
 *
 * @param cursor         MiniCursor of kline bars (possibly interleaved symbols)
 * @param engine         pre-configured [TradingEngine]
 * @param initialCapital starting portfolio value in USD
 * @param initialHoldings map of symbol → quantity to seed holdings
 * @param onCycle        optional callback invoked after each tick
 * @return [BacktestResult] with symbol set to "MULTI" and aggregate metrics
 */
suspend fun simulateMultiSymbolTicks(
    cursor: Cursor,
    engine: TradingEngine,
    initialCapital: Double,
    initialHoldings: Map<String, Double> = emptyMap(),
    annualizationFactor: Double = sqrt(252.0),
    onCycle: (CycleResult) -> Unit = {},
): BacktestResult {
    val n = cursor.size
    if (n == 0) {
        return BacktestResult(
            symbol = "MULTI",
            initialCapital = initialCapital,
            cycles = emptySeries(),
            metrics = BacktestMetrics(
                totalTicks = 0,
                totalReturn = 0.0,
                sharpeRatio = 0.0,
                sortinoRatio = 0.0,
                maxDrawdown = 0.0,
                maxDrawdownTicks = 0,
                totalHarvested = 0.0,
                totalTrades = 0,
                avgHarvestPerTick = 0.0,
            ),
        )
    }

    // Collect unique openTimes in order, and build an index of first row for each
    val openTimes = linkedSetOf<Long>()
    val firstRowAtTime = linkedMapOf<Long, Int>()
    for (i in 0 until n) {
        val row = cursor.at(i)
        val ot = row.longValue("openTime")
        if (ot !in openTimes) {
            openTimes.add(ot)
            firstRowAtTime[ot] = i
        }
    }

    // Seed holdings: allocate initial capital equally across found symbols
    val symbols = (0 until n).map { cursor.at(it).stringValue("symbol", "UNKNOWN") }.toSet()
    val holdings = if (initialHoldings.isNotEmpty()) initialHoldings.toMutableMap() else {
        val perSymbol = initialCapital / symbols.size
        val result = mutableMapOf<String, Double>()
        for (i in 0 until n) {
            val row = cursor.at(i)
            val sym = row.stringValue("symbol", "UNKNOWN")
            if (sym !in result) {
                val price = row.doubleValue("close").takeIf { it > 0.0 } ?: row.doubleValue("open").coerceAtLeast(1.0)
                result[sym] = perSymbol / price
            }
        }
        result
    }

    // Adjust engine cash: subtract holdings value to avoid double-counting.
    // Only count the first occurrence of each symbol to avoid double-counting
    // when the cursor has interleaved rows for multiple symbols.
    var initialHoldingsValue = 0.0
    val seenSymbols = mutableSetOf<String>()
    for (i in 0 until n) {
        val row = cursor.at(i)
        val sym = row.stringValue("symbol", "UNKNOWN")
        if (sym in seenSymbols) continue
        seenSymbols += sym
        val price = row.doubleValue("close").takeIf { it > 0.0 } ?: row.doubleValue("open").coerceAtLeast(1.0)
        val qty = holdings[sym] ?: 0.0
        initialHoldingsValue += qty * price
    }
    engine.cashBalance = initialCapital - initialHoldingsValue

    val tickCount = openTimes.size
    val cycleArray = arrayOfNulls<CycleResult>(tickCount)
    val timeList = openTimes.toList()

    for (tick in 0 until tickCount) {
        val openTime = timeList[tick]
        val barIndex = firstRowAtTime[openTime] ?: tick
        val inputs = multiSymbolKlineToPortfolioInput(cursor, barIndex, holdings)
        val rows = inputs.flatMap(::portfolioInputToRows)

        // Holdings from cursor represent the original allocation.
        // Engine holdings accumulate from reinvest buys — add their market value on top.
        var holdingsValue = 0.0
        rows.forEach { holdingsValue += it.Value }
        for ((sym, holding) in engine.holdings) {
            val price = inputs.find { it.symbol == sym }?.price ?: continue
            holdingsValue += holding.rawQuantity * price
        }
        val totalValue = holdingsValue + engine.cashBalance

        val result = engine.update(
            portfolioSummary = rows,
            api = null,
            cashBalanceIn = engine.cashBalance,
            holdingDetails = null,
        )

        val cycle = CycleResult(
            tick = tick,
            openTime = openTime,
            cashBalance = engine.cashBalance,
            holdingsValue = holdingsValue,
            totalValue = totalValue,
            anyTradesThisCycle = result.anyTradesThisCycle,
            harvestedAmount = result.harvestedAmount,
            tradedSymbols = result.tradedSymbols,
            rebalanceScheduled = engine.rebalanceState.isNotEmpty(),
            engineSnapshot = engine.getStateSnapshot(),
        )
        cycleArray[tick] = cycle
        onCycle(cycle)
    }

    // Every slot is written by the loop above; build non-null array explicitly
    @Suppress("UNCHECKED_CAST")
    val nonNull = Array(tickCount) { cycleArray[it]!! }
    val cycles: Series<CycleResult> = nonNull.toSeries()
    val closes = closesFromCursor(cursor)
    val metrics = computeBacktestMetrics(cycles, initialCapital, closes, annualizationFactor)

    return BacktestResult(
        symbol = "MULTI",
        initialCapital = initialCapital,
        cycles = cycles,
        metrics = metrics,
    )
}

 public fun emptyBacktestResult(genome: Genome, initialCapital: Double): BacktestResult =
    BacktestResult(
        symbol = "",
        initialCapital = initialCapital,
        cycles = emptySeries(),
        metrics = BacktestMetrics(
            totalTicks = 0,
            totalReturn = 0.0,
            sharpeRatio = 0.0,
            sortinoRatio = 0.0,
            maxDrawdown = 0.0,
            maxDrawdownTicks = 0,
            totalHarvested = 0.0,
            totalTrades = 0,
            avgHarvestPerTick = 0.0,
        ),
    )

/**
 * Aggregate summary across multiple back-test runs (e.g. multi-symbol, multi-gen, multi-timespan).
 *
 * Averages Sharpe/Sortino, sums ticks/trades/harvested, weights returns by initial capital,
 * and reports the max single-run drawdown and best/worst returns.
 */
data class BacktestAggregateReport(
    val runCount: Int,
    val totalTicks: Int,
    val avgTotalReturn: Double,     // capital-weighted mean return
    val avgSharpeRatio: Double,     // arithmetic mean
    val avgSortinoRatio: Double,    // arithmetic mean
    val maxDrawdown: Double,        // worst single-run drawdown
    val maxDrawdownTicks: Int,      // ticks of worst drawdown
    val totalTrades: Int,           // sum across all runs
    val totalHarvested: Double,      // sum across all runs
    val bestReturn: Double,          // best single-run return
    val worstReturn: Double,        // worst single-run return
    val bestGenome: Map<String, Any?>? = null,  // genome of best run, if available
)

/**
 * Roll up one or more [BacktestReport] into an aggregate summary.
 * Pass [bestGenome] to carry forward the champion genome from the best run.
 */
fun aggregateReports(
    reports: List<BacktestReport>,
    bestGenome: Map<String, Any?>? = null,
): BacktestAggregateReport {
    if (reports.isEmpty()) {
        return BacktestAggregateReport(
            runCount = 0,
            totalTicks = 0,
            avgTotalReturn = 0.0,
            avgSharpeRatio = 0.0,
            avgSortinoRatio = 0.0,
            maxDrawdown = 0.0,
            maxDrawdownTicks = 0,
            totalTrades = 0,
            totalHarvested = 0.0,
            bestReturn = 0.0,
            worstReturn = 0.0,
            bestGenome = bestGenome,
        )
    }
    if (reports.size == 1) {
        val r = reports[0]
        return BacktestAggregateReport(
            runCount = 1,
            totalTicks = r.totalTicks,
            avgTotalReturn = r.totalReturn,
            avgSharpeRatio = r.sharpeRatio,
            avgSortinoRatio = r.sortinoRatio,
            maxDrawdown = r.maxDrawdown,
            maxDrawdownTicks = r.maxDrawdownTicks,
            totalTrades = r.totalTrades,
            totalHarvested = r.totalHarvested,
            bestReturn = r.totalReturn,
            worstReturn = r.totalReturn,
            bestGenome = bestGenome,
        )
    }

    val totalTicks = reports.sumOf { it.totalTicks }
    val totalTrades = reports.sumOf { it.totalTrades }
    val totalHarvested = reports.sumOf { it.totalHarvested }

    // Capital-weighted average return (each run weighted by its initial capital)
    val totalCapital = reports.sumOf { it.initialCapital }
    val avgTotalReturn = if (totalCapital > 0.0) {
        reports.sumOf { it.totalReturn * it.initialCapital } / totalCapital
    } else 0.0

    val avgSharpeRatio = reports.map { it.sharpeRatio }.average()
    val avgSortinoRatio = reports.map { it.sortinoRatio }.average()

    val worst = reports.minByOrNull { it.totalReturn }!!
    val best = reports.maxByOrNull { it.totalReturn }!!

    val maxDrawdownReport = reports.maxByOrNull { it.maxDrawdown }!!
    return BacktestAggregateReport(
        runCount = reports.size,
        totalTicks = totalTicks,
        avgTotalReturn = avgTotalReturn,
        avgSharpeRatio = avgSharpeRatio,
        avgSortinoRatio = avgSortinoRatio,
        maxDrawdown = maxDrawdownReport.maxDrawdown,
        maxDrawdownTicks = maxDrawdownReport.maxDrawdownTicks,
        totalTrades = totalTrades,
        totalHarvested = totalHarvested,
        bestReturn = best.totalReturn,
        worstReturn = worst.totalReturn,
        bestGenome = bestGenome,
    )
}

/**
 * Build an aggregate report from a list of [GenomeEvaluation] by extracting
 * each genome's [BacktestResult] and rolling it up.
 */
fun aggregateEvaluations(
    evaluations: List<GenomeEvaluation>,
    bestGenome: Map<String, Any?>? = null,
): BacktestAggregateReport {
    val reports = evaluations.map { it.result.toBacktestReport() }
    val championGenome = evaluations.maxByOrNull { it.fitness }?.genome?.backing
    return aggregateReports(reports, bestGenome ?: championGenome)
}

/**
 * Convert a [HarnessRunResult] (RealtimeHarness pipeline) into a [BacktestResult].
 *
 * This bridges the two simulation pipelines:
 * - **BacktestModels path**: CSV → KlineBlock → Cursor → simulateTicks → BacktestResult
 * - **RealtimeHarness path**: KlineBlock → replay → HarnessRunResult
 *
 * The adapter maps each [HarnessCycle] to a [CycleResult] and then runs
 * [computeBacktestMetrics] over the resulting series, producing a result
 * that can feed into [aggregateReports], [aggregateEvaluations], and [toBacktestReport].
 *
 * @param harnessRun  the [HarnessRunResult] from [RealtimeHarness.replay]
 * @param symbol      symbol label for the result (defaults to "MULTI")
 * @param initialCapital  starting capital used in the harness run
 */
fun HarnessRunResult.toBacktestResult(
    symbol: String = "MULTI",
    initialCapital: Double,
): BacktestResult {
    if (cycles.isEmpty()) {
        return BacktestResult(
            symbol = symbol,
            initialCapital = initialCapital,
            cycles = emptySeries(),
            metrics = BacktestMetrics(
                totalTicks = 0,
                totalReturn = 0.0,
                sharpeRatio = 0.0,
                sortinoRatio = 0.0,
                maxDrawdown = 0.0,
                maxDrawdownTicks = 0,
                totalHarvested = 0.0,
                totalTrades = 0,
                avgHarvestPerTick = 0.0,
            ),
        )
    }

    val cycleResults: Series<CycleResult> = cycles.mapIndexed { index, cycle ->
        CycleResult(
            tick = index,
            openTime = cycle.frame.openTime,
            cashBalance = if (index == cycles.lastIndex) finalCash else 0.0,
            holdingsValue = cycle.totalValue - (if (index == cycles.lastIndex) finalCash else 0.0),
            totalValue = cycle.totalValue,
            anyTradesThisCycle = cycle.result.anyTradesThisCycle,
            harvestedAmount = cycle.result.harvestedAmount,
            tradedSymbols = cycle.result.tradedSymbols,
            rebalanceScheduled = false,
            engineSnapshot = emptyMap(),
        )
    }.toSeries()

    // Build a dummy closes series (1 entry per tick) from totalValue, since
    // HarnessRunResult doesn't carry raw price data. The closePrices param
    // in computeBacktestMetrics is currently unused, but we provide a same-length
    // series for API compatibility.
    val closes: Series<Double> = cycleResults α { it.totalValue }
    val metrics = computeBacktestMetrics(cycleResults, initialCapital, closes)

    return BacktestResult(
        symbol = symbol,
        initialCapital = initialCapital,
        cycles = cycleResults,
        metrics = metrics,
    )
}

/**
 * Convenience: convert a [HarnessRunResult] directly to a [BacktestReport],
 * bridging the RealtimeHarness path into the report aggregation pipeline.
 */
fun HarnessRunResult.toBacktestReport(
    symbol: String = "MULTI",
    initialCapital: Double,
): BacktestReport = toBacktestResult(symbol, initialCapital).toBacktestReport()

/**
 * Convert a [StochasticTrainingSnapshot] into a [BacktestReport].
 *
 * Since the snapshot carries aggregate scalars rather than the full equity curve,
 * the resulting report has simplified metrics: Sharpe and Sortino are set to 0
 * (insufficient data), while totalReturn and maxDrawdown are derived from the
 * snapshot's bestTotalValue and bestDrawdown fields.
 *
 * For the full equity-curve report, use the [HarnessRunResult.toBacktestReport]
 * adapter on the underlying harness run directly.
 */
fun StochasticTrainingSnapshot.toBacktestReport(
    symbol: String = "MULTI",
    initialCapital: Double,
): BacktestReport {
    val totalReturn = if (initialCapital > 0.0) (bestTotalValue - initialCapital) / initialCapital else 0.0
    return BacktestReport(
        symbol = symbol,
        initialCapital = initialCapital,
        finalEquity = bestTotalValue,
        totalTicks = totalCycles,
        totalReturn = totalReturn,
        sharpeRatio = 0.0,
        sortinoRatio = 0.0,
        maxDrawdown = bestDrawdown,
        maxDrawdownTicks = 0,
        totalTrades = bestTrades,
        totalHarvested = bestProfit.coerceAtLeast(0.0),
    )
}

/**
 * Aggregate multiple [StochasticTrainingSnapshot] runs into a single [BacktestAggregateReport].
 * Each snapshot is converted via [toBacktestReport] and then aggregated.
 */
fun aggregateTrainingSnapshots(
    snapshots: List<StochasticTrainingSnapshot>,
    initialCapital: Double,
    bestGenome: Map<String, Any?>? = null,
): BacktestAggregateReport {
    val reports = snapshots.map { it.toBacktestReport(initialCapital = initialCapital) }
    return aggregateReports(reports, bestGenome)
}
