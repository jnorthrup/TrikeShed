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
 *     → BinanceCsvParser → KlineBlock → asCursor() → MiniCursor
 *     → klineBarToPortfolioInput → PortfolioInput
 *     → runCycle(TradingEngine.update) → CycleResult
 *     → BacktestResult + BacktestMetrics (back-test report)
 *
 * For multi-block cursors (BinanceCursor), use [simulateTicksFromCursor].
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

/**
 * Adapts a [Cursor] (e.g. [BinanceCursor]) to a [MiniCursor] for use with [simulateTicks].
 *
 * Scans the cursor once to collect all rows into memory as DocRowVec instances,
 * then presents them as a flat MiniCursor.  Use this when running a back-test
 * over multi-block Binance archive data where [BinanceCursor] spans many sealed
 * KlineBlocks.
 *
 * @param cursor     any Cursor whose rows implement [borg.trikeshed.miniduck.MiniRowVec]
 * @param rowCount   total number of rows (from cursor.rowCount())
 */
fun adaptCursorToMiniCursor(
    cursor: borg.trikeshed.miniduck.exec.Cursor,
    rowCount: Int,
): MiniCursor {
    // Collect all rows into a snapshot list.
    // BinanceCursor.row returns a MiniRowVecRowAccessor whose `row` property
    // is the underlying MiniRowVec (always DocRowVec for kline data).
    val rows = mutableListOf<DocRowVec>()
    while (cursor.next()) {
        val accessor = cursor.row
        // Named cast to our internal wrapper (fast path for BinanceCursor and test helpers)
        val mr = (accessor as? MiniRowVecRowAccessor)?.row ?: continue
        @Suppress("UNCHECKED_CAST")
        if (mr is DocRowVec) rows.add(mr)
    }
    cursor.close()
    return rows.size j { i: Int -> rows[i] }
}

/**
 * RowAccessor wrapper that holds a MiniRowVec.
 * BinanceCursor returns this from its `row` property.
 * Marked internal so it can be matched in adaptCursorToMiniCursor.
 */
internal class MiniRowVecRowAccessor(
    val row: borg.trikeshed.miniduck.MiniRowVec,
) : borg.trikeshed.miniduck.exec.RowAccessor {
    override fun get(index: Int): Any? = row.get(index)
    override fun get(name: String): Any? = (row as? DocRowVec)?.get(name)
}

/**
 * Back-test simulation over a multi-block [Cursor] such as [BinanceCursor].
 *
 * Draw-through:
 *   BinanceCursor(blocks) → adaptCursorToMiniCursor → MiniCursor
 *     → simulateTicks → BacktestResult
 *
 * @param cursor           flat cursor over sealed KlineBlocks (e.g. BinanceCursor)
 * @param engine           pre-configured [TradingEngine] in SHADOW mode
 * @param initialCapital   starting cash balance
 * @param rowCount         total row count (call cursor.rowCount() before passing)
 * @param onCycle          optional per-tick callback
 */
suspend fun simulateTicksFromCursor(
    cursor: borg.trikeshed.miniduck.exec.Cursor,
    engine: TradingEngine,
    initialCapital: Double,
    rowCount: Int,
    onCycle: ((CycleResult) -> Unit)? = null,
): BacktestResult {
    val mini = adaptCursorToMiniCursor(cursor, rowCount)
    return simulateTicks(mini, engine, initialCapital, onCycle)
}
