package borg.trikeshed.dreamer

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.miniduck.DocRowVec
import borg.trikeshed.miniduck.MiniCursor
import borg.trikeshed.miniduck.at
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
    val row: DocRowVec = (cursor at barIndex) as DocRowVec
    val symbol: String = row["symbol"] as? String ?: "UNKNOWN"
    val openTime: Long = row["openTime"] as? Long ?: 0L
    val price: Double = row["close"] as? Double ?: row["open"] as? Double ?: 0.0
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
): List<PortfolioInput> {
    val row: DocRowVec = (cursor at barIndex) as DocRowVec
    val symbol: String = row["symbol"] as? String ?: "UNKNOWN"
    val openTime: Long = row["openTime"] as? Long ?: 0L
    val price: Double = row["close"] as? Double ?: row["open"] as? Double ?: 0.0
    val quantity: Double = holdings[symbol] ?: 0.0
    return listOf(
        PortfolioInput(
            symbol = symbol,
            openTime = openTime,
            quantity = quantity,
            price = price,
            value = quantity * price,
        )
    )
}

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
    val refRow: DocRowVec = (cursor at barIndex) as DocRowVec
    val refOpenTime: Long = refRow["openTime"] as? Long ?: 0L

    return (0 until cursor.size).mapNotNull { i ->
        val row: DocRowVec = (cursor at i) as DocRowVec
        if ((row["openTime"] as? Long) != refOpenTime) return@mapNotNull null
        val symbol: String = row["symbol"] as? String ?: "UNKNOWN"
        val price: Double = row["close"] as? Double ?: row["open"] as? Double ?: 0.0
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
        val row: DocRowVec = (cursor at i) as DocRowVec
        row["close"] as? Double ?: row["open"] as? Double ?: 0.0
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
