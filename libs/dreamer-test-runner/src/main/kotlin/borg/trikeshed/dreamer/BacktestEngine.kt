package borg.trikeshed.dreamer

import borg.trikeshed.couch.kline.Kline
import borg.trikeshed.couch.kline.TimeSpan
import borg.trikeshed.miniduck.DocRowVec
import borg.trikeshed.miniduck.MiniCursor
import borg.trikeshed.miniduck.at
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import kotlin.math.sqrt

/**
 * Back-test simulation engine.
 *
 * Runs a [TradingEngine] in SHADOW mode over a [MiniCursor] of kline bars,
 * calling [TradingEngine.update] on each bar and recording [CycleResult] ticks.
 *
 * Architecture (dreamer-kmm):
 *   Binance archive data
 *     → KlineCsvParser → KlineBlock → Cursor (BinanceCursor / MiniCursor)
 *     → klineBarToPortfolioInput → PortfolioInput
 *     → runCycle(TradingEngine.update) → CycleResult
 *     → BacktestResult + BacktestMetrics (back-test report)
 *
 * Usage:
 * ```kotlin
 * val cursor: MiniCursor = klineSource.fetchCursor()
 * val engine = TradingEngine(defaultGenome(), Mode.SHADOW, initialCapital = 10_000.0)
 * val result = simulateTicks(cursor, engine, initialCapital)
 * println(result.metrics.sharpeRatio)
 * ```
 *
 * @param cursor         MiniCursor of kline bars (DocRowVec rows with openTime, open, high, low, close, volume)
 * @param engine         pre-configured [TradingEngine] in SHADOW mode
 * @param initialCapital starting cash balance
 * @param onCycle        optional callback invoked after each tick with the [CycleResult]
 */
suspend fun simulateTicks(
    cursor: MiniCursor,
    engine: TradingEngine,
    initialCapital: Double,
    onCycle: ((CycleResult) -> Unit)? = null,
): BacktestResult {
    val n = cursor.size
    if (n == 0) return emptyBacktestResult(engine.genome, initialCapital)

    // Determine the symbol from the first row
    val firstRow: DocRowVec = (cursor at 0) as DocRowVec
    val symbol: String = firstRow["symbol"] as? String ?: "UNKNOWN"

    // Derive initial quantity from capital and first close price
    val initialPrice: Double = firstRow["close"] as? Double ?: firstRow["open"] as? Double ?: 0.0
    val initialQuantity = if (initialPrice > 0.0) initialCapital / initialPrice else 0.0

    // Build initial holdings map
    val holdings = mutableMapOf<String, Holding>(symbol to Holding(initialQuantity))

    // Build the list of close prices for metrics
    val closePrices = closesFromCursor(cursor)

    val cycles = mutableListOf<CycleResult>()

    for (i in 0 until n) {
        val row: DocRowVec = (cursor at i) as DocRowVec
        val openTime: Long = row["openTime"] as? Long ?: 0L
        val price: Double = row["close"] as? Double ?: row["open"] as? Double ?: 0.0

        // Build PortfolioInput for this bar
        val input = PortfolioInput(
            symbol = symbol,
            openTime = openTime,
            quantity = initialQuantity,
            price = price,
            value = initialQuantity * price,
        )

        // Compute holdings value
        val holdingsValue = initialQuantity * price
        val cashBalance = engine.cashBalance

        // Run the engine cycle (synchronous — SHADOW mode)
        val portfolioRows = portfolioInputToRows(input)
        val engineResult = engine.update(
            portfolioSummary = portfolioRows,
            api = null,
            cashBalanceIn = cashBalance,
            holdingDetails = holdings,
        )

        val snapshot = engine.getStateSnapshot()
        val totalValue = engine.cashBalance + holdingsValue

        val cycle = CycleResult(
            tick = i,
            openTime = openTime,
            cashBalance = engine.cashBalance,
            holdingsValue = holdingsValue,
            totalValue = totalValue,
            anyTradesThisCycle = engineResult.anyTradesThisCycle,
            harvestedAmount = engineResult.harvestedAmount,
            tradedSymbols = engineResult.tradedSymbols,
            rebalanceScheduled = engine.rebalanceState.containsKey(symbol),
            engineSnapshot = snapshot,
        )
        cycles.add(cycle)
        onCycle?.invoke(cycle)
    }

    // Build metrics
    val metrics = computeBacktestMetrics(cycles, initialCapital, closePrices)

    return BacktestResult(
        symbol = symbol,
        initialCapital = initialCapital,
        cycles = cycles,
        metrics = metrics,
    )
}

/**
 * Compute aggregate [BacktestMetrics] from cycle results.
 */
fun computeBacktestMetrics(
    cycles: List<CycleResult>,
    initialCapital: Double,
    closePrices: List<Double>,
): BacktestMetrics {
    if (cycles.isEmpty()) {
        return BacktestMetrics(
            totalTicks = 0,
            totalReturn = 0.0,
            sharpeRatio = 0.0,
            maxDrawdown = 0.0,
            maxDrawdownTicks = 0,
            totalHarvested = 0.0,
            totalTrades = 0,
            avgHarvestPerTick = 0.0,
        )
    }

    val totalTicks = cycles.size
    val finalTotal = cycles.lastOrNull()?.totalValue ?: initialCapital
    val totalReturn = if (initialCapital > 0.0) (finalTotal - initialCapital) / initialCapital else 0.0

    // Daily/cycle returns for Sharpe
    val returns = cycles.mapIndexed { i, c ->
        if (i == 0) 0.0
        else {
            val prev = cycles[i - 1].totalValue
            if (prev > 0.0) (c.totalValue - prev) / prev else 0.0
        }
    }.drop(1) // drop first zero

    val meanReturn = if (returns.isNotEmpty()) returns.average() else 0.0
    val variance = if (returns.size > 1) {
        returns.map { (it - meanReturn) * (it - meanReturn) }.average()
    } else 0.0
    val stdReturn = sqrt(variance)
    // Annualized Sharpe (252 trading days per year, assumes 1 bar = 1 day for now)
    val sharpeRatio = if (stdReturn > 0.0) (meanReturn / stdReturn) * sqrt(252.0) else 0.0

    // Max drawdown
    var peak = initialCapital
    var maxDrawdown = 0.0
    var maxDrawdownTicks = 0
    var currentDrawdownTicks = 0
    for (cycle in cycles) {
        if (cycle.totalValue > peak) {
            peak = cycle.totalValue
            currentDrawdownTicks = 0
        } else {
            val drawdown = (peak - cycle.totalValue) / peak
            if (drawdown > maxDrawdown) {
                maxDrawdown = drawdown
                maxDrawdownTicks = currentDrawdownTicks
            }
            currentDrawdownTicks++
        }
    }

    val totalHarvested = cycles.sumOf { it.harvestedAmount }
    val totalTrades = cycles.count { it.anyTradesThisCycle }
    val avgHarvestPerTick = if (totalTicks > 0) totalHarvested / totalTicks else 0.0

    return BacktestMetrics(
        totalTicks = totalTicks,
        totalReturn = totalReturn,
        sharpeRatio = sharpeRatio,
        maxDrawdown = maxDrawdown,
        maxDrawdownTicks = maxDrawdownTicks,
        totalHarvested = totalHarvested,
        totalTrades = totalTrades,
        avgHarvestPerTick = avgHarvestPerTick,
    )
}

private fun emptyBacktestResult(genome: Genome, initialCapital: Double): BacktestResult =
    BacktestResult(
        symbol = "",
        initialCapital = initialCapital,
        cycles = emptyList(),
        metrics = BacktestMetrics(
            totalTicks = 0,
            totalReturn = 0.0,
            sharpeRatio = 0.0,
            maxDrawdown = 0.0,
            maxDrawdownTicks = 0,
            totalHarvested = 0.0,
            totalTrades = 0,
            avgHarvestPerTick = 0.0,
        ),
    )
