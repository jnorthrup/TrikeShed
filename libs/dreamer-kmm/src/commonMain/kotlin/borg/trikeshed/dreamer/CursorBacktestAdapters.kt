package borg.trikeshed.dreamer

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.miniduck.DocRowVec
import borg.trikeshed.miniduck.MiniCursor

// Helper: infer an IOMemento type for a value (coarse heuristics)
private fun inferIOMementoFor(value: Any?): IOMemento = when (value) {
    is Double -> IOMemento.IoDouble
    is Float -> IOMemento.IoFloat
    is Long -> IOMemento.IoLong
    is Int -> IOMemento.IoInt
    is Boolean -> IOMemento.IoBoolean
    is Char -> IOMemento.IoString
    is CharSequence -> IOMemento.IoString
    is ByteArray -> IOMemento.IoByteArray
    null -> IOMemento.IoNothing
    else -> IOMemento.IoString
}

// Convert a DocRowVec (miniduck) to a columnar RowVec (cursor.RowVec)
private fun docRowVecToRowVec(doc: DocRowVec): RowVec {
    val values = doc.cells.toSeries()
    val meta = doc.keys.size j { idx: Int -> { borg.trikeshed.cursor.ColumnMeta(doc.keys[idx], inferIOMementoFor(doc.cells[idx])) } }
    return values.j(meta)
}

// Convert a MiniCursor of DocRowVec into a columnar Cursor
fun miniCursorToColumnar(mini: MiniCursor): Cursor = mini.size j { i: Int ->
    val row = (mini at i) as DocRowVec
    docRowVecToRowVec(row)
}

// Main cursor-based simulateTicks: operates on borg.trikeshed.cursor.Cursor
suspend fun simulateTicks(
    cursor: Cursor,
    engine: TradingEngine,
    initialCapital: Double,
    onCycle: (CycleResult) -> Unit = {},
): BacktestResult {
    val n = cursor.size
    if (n == 0) return BacktestResult(symbol = "", initialCapital = initialCapital, cycles = emptyList(), metrics = BacktestMetrics(0, 0.0, 0.0, 0.0, 0.0, 0, 0.0, 0))

    // Resolve common column indices once
    val symbolIdx = cursor.meta("symbol")[0]
    val openTimeIdx = cursor.meta("openTime")[0]
    val openIdx = cursor.meta("open")[0]
    val closeIdx = cursor.meta("close")[0]

    val firstRow: RowVec = cursor.at(0)
    val symbol: String = (firstRow[symbolIdx] as? String) ?: "UNKNOWN"
    val firstClose: Double = (firstRow[closeIdx] as? Double) ?: (firstRow[openIdx] as? Double) ?: 1.0
    val initialQuantity = if (firstClose > 0.0) initialCapital / firstClose else 0.0

    val cycles = mutableListOf<CycleResult>()

    for (i in 0 until n) {
        val row = cursor.at(i)
        val openTime: Long = (row[openTimeIdx] as? Long) ?: 0L
        val price: Double = (row[closeIdx] as? Double) ?: (row[openIdx] as? Double) ?: 0.0

        val input = PortfolioInput(
            symbol = symbol,
            openTime = openTime,
            quantity = initialQuantity,
            price = price,
            value = initialQuantity * price,
        )

        val portfolioRows = portfolioInputToRows(input)
        val engineResult = engine.update(
            portfolioSummary = portfolioRows,
            api = null,
            cashBalanceIn = engine.cashBalance,
            holdingDetails = null,
        )

        val holdingsValue = initialQuantity * price
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
            rebalanceScheduled = engine.rebalanceState.isNotEmpty(),
            engineSnapshot = engine.getStateSnapshot(),
        )
        cycles.add(cycle)
        onCycle(cycle)
    }

    // Build close prices series for metrics
    val closeList = List(n) { idx: Int ->
        val r = cursor.at(idx)
        (r[closeIdx] as? Double) ?: (r[openIdx] as? Double) ?: 0.0
    }

    val metrics = computeBacktestMetrics(cycles, initialCapital, closeList)

    return BacktestResult(
        symbol = symbol,
        initialCapital = initialCapital,
        cycles = cycles,
        metrics = metrics,
    )
}

// Backwards-compatible shim for existing MiniCursor-based callers
suspend fun simulateTicksMiniCursor(
    cursor: MiniCursor,
    engine: TradingEngine,
    initialCapital: Double,
    onCycle: ((CycleResult) -> Unit)? = null,
): BacktestResult = simulateTicks(miniCursorToColumnar(cursor), engine, initialCapital, onCycle ?: {})
