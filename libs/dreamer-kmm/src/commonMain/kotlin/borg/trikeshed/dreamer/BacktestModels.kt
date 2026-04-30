package borg.trikeshed.dreamer

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.miniduck.MiniCursor
import borg.trikeshed.miniduck.getValue
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.at
import kotlin.math.sqrt

/**
 * Back-test input derived from a single kline bar.
 *
 * [PortfolioInput] is what the [TradingEngine] consumes on each cycle:
 * a list of [PortfolioRow] one per symbol, with current price and quantity.
 *
 * The adapter [klineBarToPortfolioInput] converts a DocRowVec row
 * (projected from a KlineBlock/MiniCursor) into this form.
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
    val cycles: List<CycleResult>,
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
    val finalEquity = cycles.lastOrNull()?.totalValue ?: initialCapital
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
 * Convert a single MiniCursor row (a DocRowVec kline bar) into a [PortfolioInput].
 *
 * The row must have keys: symbol, openTime, quantity, price.
 * For back-testing with a single symbol, quantity is derived from
 * initial capital / initial price on the first bar, then held constant.
 *
 * @param cursor   MiniCursor of kline bars (DocRowVec rows)
 * @param barIndex index of the bar to convert
 * @param currentQuantity quantity of the asset held (fixed for back-test simulation)
 */
fun klineBarToPortfolioInput(
    cursor: MiniCursor,
    barIndex: Int,
    currentQuantity: Double,
): PortfolioInput {
    val row = cursor.at(barIndex)
    val symbol: String = row.stringValue("symbol", "UNKNOWN")
    val openTime: Long = row.longValue("openTime")
    val price: Double = row.doubleValue("close").takeIf{ it > 0.0 } ?: row.doubleValue("open")
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
 * Convert a MiniCursor row at [barIndex] into a [PortfolioInput].
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
 * @param cursor   MiniCursor of kline bars (DocRowVec rows)
 * @param barIndex index of the bar row to convert
 * @param holdings map of symbol → quantity (used to compute value)
 */
fun multiSymbolKlineToPortfolioInput(
    cursor: MiniCursor,
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
 * @param cursor    MiniCursor of kline bars (DocRowVec rows, possibly interleaved symbols)
 * @param barIndex reference bar index; all rows with the same openTime are collected
 * @param holdings  map of symbol → quantity
 */
fun allSymbolsAtBar(
    cursor: MiniCursor,
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
 * Project a MiniCursor of kline rows into a flat [List] of close prices.
 * Convenience for building price arrays for metrics.
 */
fun closesFromCursor(cursor: MiniCursor): List<Double> {
    val n = cursor.size
    return List(n) { i: Int ->
        val row = cursor.at(i)
        row.doubleValue("close").takeIf { it > 0.0 } ?: row.doubleValue("open")
    }
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
            sortinoRatio = 0.0,
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

    val downsideVariance = if (returns.isNotEmpty()) {
        returns.map { if (it < 0.0) it * it else 0.0 }.average()
    } else 0.0
    val downsideDeviation = sqrt(downsideVariance)
    // Annualized Sortino, using zero as the minimum acceptable cycle return.
    val sortinoRatio = if (downsideDeviation > 0.0) (meanReturn / downsideDeviation) * sqrt(252.0) else 0.0

    // Max drawdown
    var peak = initialCapital
    var peakIndex = -1
    var maxDrawdown = 0.0
    var maxDrawdownTicks = 0
    for ((index, cycle) in cycles.withIndex()) {
        if (cycle.totalValue > peak) {
            peak = cycle.totalValue
            peakIndex = index
        } else {
            val drawdown = (peak - cycle.totalValue) / peak
            if (drawdown > maxDrawdown) {
                maxDrawdown = drawdown
                maxDrawdownTicks = index - peakIndex
            }
        }
    }

    val totalHarvested = cycles.sumOf { it.harvestedAmount }
    val totalTrades = cycles.count { it.anyTradesThisCycle }
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
 * @param cursor    MiniCursor of kline bars (DocRowVec rows, from KlineBlock.asCursor())
 * @param engine    pre-configured [TradingEngine] (genome + mode + capital)
 * @param initialCapital  starting portfolio value in USD
 * @param onCycle   optional callback invoked after each tick with the cycle result
 * @return [BacktestResult] with all cycles and aggregate metrics
 */
suspend fun simulateTicks(
    cursor: MiniCursor,
    engine: TradingEngine,
    initialCapital: Double,
    onCycle: (CycleResult) -> Unit = {},
): BacktestResult {
    val n = cursor.size
    if (n == 0) {
        return BacktestResult(
            symbol = "",
            initialCapital = initialCapital,
            cycles = emptyList(),
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

    val cycles = mutableListOf<CycleResult>()

    for (i in 0 until n) {
        val input = klineBarToPortfolioInput(cursor, i, currentQuantity = initialQuantity)
        val rows = portfolioInputToRows(input)

        // Current holdings value is sum of all row values
        var holdingsValue = 0.0
        rows.forEach { holdingsValue += it.Value }
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
        cycles.add(cycle)
        onCycle(cycle)
    }

    val closes = closesFromCursor(cursor)
    val metrics = computeBacktestMetrics(cycles, initialCapital, closes)

    return BacktestResult(
        symbol = symbol,
        initialCapital = initialCapital,
        cycles = cycles,
        metrics = metrics,
    )
}
 public fun emptyBacktestResult(genome: Genome, initialCapital: Double): BacktestResult =
    BacktestResult(
        symbol = "",
        initialCapital = initialCapital,
        cycles = emptyList(),
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
 public fun RowVec.stringValue(name: String, default: String): String =
    getValue(name) as? String ?: default
 public fun RowVec.longValue(name: String): Long = when (val value = getValue(name)) {
    is Long -> value
    is Number -> value.toLong()
    is String -> value.toLongOrNull() ?: 0L
    else -> 0L
}
 public fun RowVec.doubleValue(name: String): Double = when (val value = getValue(name)) {
    is Double -> value
    is Number -> value.toDouble()
    is String -> value.toDoubleOrNull() ?: 0.0
    else -> 0.0
}
 public fun RowVec.intValue(name: String): Int = when (val value = getValue(name)) {
    is Int -> value
    is Number -> value.toInt()
    is String -> value.toIntOrNull() ?: 0
    else -> 0
}
