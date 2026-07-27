package borg.trikeshed.cursor

import borg.trikeshed.lib.DoubleSeries
import borg.trikeshed.lib.j
import borg.trikeshed.lib.leftIdentity
import borg.trikeshed.isam.meta.IOMemento

/**
 * Extracts a numeric column from a Cursor into a primitive-backed DoubleSeries to avoid boxing overhead.
 */
fun Cursor.extractDoubleSeries(columnIndex: Int): DoubleSeries {
    val series = DoubleSeries()
    for (i in 0 until this.a) {
        val cellValue = this.b(i).b(columnIndex).a
        val doubleValue = when (cellValue) {
            is Double -> cellValue
            is Number -> cellValue.toDouble()
            is String -> cellValue.toDoubleOrNull() ?: Double.NaN
            else -> Double.NaN
        }
        series.append(doubleValue)
    }
    return series
}

/**
 * Extracts a numeric column by name from a Cursor into a primitive-backed DoubleSeries.
 */
fun Cursor.extractDoubleSeries(columnName: CharSequence): DoubleSeries {
    if (this.a == 0) return DoubleSeries()
    val firstRow = this.b(0)
    var colIndex = -1
    for (c in 0 until firstRow.a) {
        if (firstRow.b(c).b().name == columnName) {
            colIndex = c
            break
        }
    }
    if (colIndex == -1) error("Column '$columnName' not found")
    return extractDoubleSeries(colIndex)
}

/**
 * Maps a primitive DoubleSeries back to a single-column Cursor.
 */
fun DoubleSeries.toCursor(columnName: CharSequence): Cursor {
    val size = this.size
    val meta = ColumnMeta(columnName, IOMemento.IoDouble).leftIdentity
    return size j { rowIndex ->
        val value = this[rowIndex]
        1 j { _ -> value j meta }
    }
}
