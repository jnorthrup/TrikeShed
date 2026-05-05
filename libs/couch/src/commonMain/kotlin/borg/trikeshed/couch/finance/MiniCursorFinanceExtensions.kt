/**
 * Financial domain extensions for MiniCursor.
 *
 * Ported from dreamer-kmm TrikeShedMiniDuckBridge to couch.finance.
 * This file contains ONLY generic financial operations (price cursors, series extraction).
 * Domain-specific trading logic (Genome, SimulationResult) stays in dreamer-kmm.
 */
package borg.trikeshed.couch.finance

import borg.trikeshed.collections.s_
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.miniduck.KeyedRowVec
import borg.trikeshed.cursor.getValue
import borg.trikeshed.miniduck.toRowVec
import borg.trikeshed.cursor.at
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size

/**
 * Convert a DoubleArray to a MiniCursor with a single price column.
 *
 * @param closeColumn The column name for the price data (default: "close")
 */
fun DoubleArray.toPriceCursor(closeColumn: String = "close"): Cursor =
    size j { index ->
        KeyedRowVec(
            keys = s_[closeColumn],
            cells = s_[this[index] as Any?],
        ).toRowVec()
    }

/**
 * Extract a Double series from a MiniCursor by column name.
 *
 * @param column The column name to extract (default: "close")
 * @return A Series<Double> with the column values, or Double.NaN for missing/invalid values
 */
fun Cursor.doubleSeries(column: String = "close"): Series<Double> =
    size j { index ->
        when (val value = at(index).getValue(column)) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull() ?: Double.NaN
            else -> Double.NaN
        }
    }
