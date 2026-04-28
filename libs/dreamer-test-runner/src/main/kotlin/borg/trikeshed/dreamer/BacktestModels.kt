package borg.trikeshed.dreamer

import borg.trikeshed.couch.kline.Kline
import borg.trikeshed.couch.kline.TimeSpan
import borg.trikeshed.miniduck.DocRowVec
import borg.trikeshed.miniduck.MiniCursor
import borg.trikeshed.miniduck.at
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size

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
 * Convert a MiniCursor of multi-symbol kline bars (one symbol per row)
 * into a [PortfolioInput] for a given bar index.
 *
 * Used when the cursor contains per-bar close prices for all symbols in the portfolio.
 */
fun multiSymbolKlineToPortfolioInput(
    cursor: MiniCursor,
    barIndex: Int,
    holdings: Map<String, Double>,
): List<PortfolioInput> {
    val row: DocRowVec = (cursor at barIndex) as DocRowVec
    // The cursor may have multiple rows per bar if symbols are interleaved.
    // We assume the cursor is already filtered to one symbol for the simple case.
    // For multi-symbol: each row in the cursor is one symbol's bar.
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
