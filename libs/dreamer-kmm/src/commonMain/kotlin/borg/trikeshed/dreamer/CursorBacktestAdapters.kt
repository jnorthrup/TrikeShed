package borg.trikeshed.dreamer

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.miniduck.MiniCursor

fun miniCursorToColumnar(mini: MiniCursor): Cursor = mini

// Backwards-compatible shim for existing MiniCursor-based callers
suspend fun simulateTicksMiniCursor(
    cursor: MiniCursor,
    engine: TradingEngine,
    initialCapital: Double,
    onCycle: ((CycleResult) -> Unit)? = null,
): BacktestResult = simulateTicks(miniCursorToColumnar(cursor), engine, initialCapital, onCycle ?: {})
