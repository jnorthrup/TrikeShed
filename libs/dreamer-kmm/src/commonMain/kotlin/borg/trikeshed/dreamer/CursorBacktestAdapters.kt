package borg.trikeshed.dreamer

import borg.trikeshed.cursor.Cursor

fun miniCursorToColumnar(mini: Cursor): Cursor = mini

// Backwards-compatible shim for existing MiniCursor-based callers
suspend fun simulateTicksMiniCursor(
    cursor: Cursor,
    engine: TradingEngine,
    initialCapital: Double,
    onCycle: ((CycleResult) -> Unit)? = null,
): BacktestResult = simulateTicks(miniCursorToColumnar(cursor), engine, initialCapital, onCycle ?: {})
