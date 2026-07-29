package borg.trikeshed.graph.query

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.lib.DoubleSeries
import borg.trikeshed.cursor.RowVec

/**
 * Executes queries against a Cursor dataset.
 */
class QueryEngine(private val data: Cursor) {

    private val columnIndices: Map<String, Int> by lazy {
        val map = mutableMapOf<String, Int>()
        if (data.a > 0) {
            val firstRow: RowVec = data.b(0)
            // Traverse in reverse to maintain original 'break' semantics (first match wins)
            for (i in (firstRow.a - 1) downTo 0) {
                map[firstRow.b(i).b().name.toString()] = i
            }
        }
        map
    }

    /**
     * Extracts a numeric column by name into a primitive-backed DoubleSeries.
     * Throws IllegalArgumentException if the column does not exist.
     */
    fun extractDoubleColumn(columnName: String): DoubleSeries {
        val series = DoubleSeries()
        if (data.a == 0) return series
        
        val colIdx = columnIndices[columnName]
        require(colIdx != null) { "Column '$columnName' not found" }
        
        for (i in 0 until data.a) {
            val row = data.b(i)
            val cell = if (row is borg.trikeshed.cursor.ReifiedSplitSeries2<*, *>) {
                row.leftSeries.b(colIdx)
            } else {
                row.b(colIdx).a
            }
            val value = when (cell) {
                is Double -> cell
                is Number -> cell.toDouble()
                else -> 0.0 // Handle non-numeric or null safely
            }
            series.append(value)
        }
        return series
    }
}
