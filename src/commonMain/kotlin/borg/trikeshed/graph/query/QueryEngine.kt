package borg.trikeshed.graph.query

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.lib.DoubleSeries
import borg.trikeshed.cursor.RowVec

/**
 * Executes queries against a Cursor dataset.
 */
class QueryEngine(private val data: Cursor) {
    /**
     * Extracts a numeric column by name into a primitive-backed DoubleSeries.
     * Throws IllegalArgumentException if the column does not exist.
     */
    fun extractDoubleColumn(columnName: String): DoubleSeries {
        val series = DoubleSeries()
        if (data.a == 0) return series
        
        // Find column index
        val firstRow: RowVec = data.b(0)
        var colIdx = -1
        for (i in 0 until firstRow.a) {
            if (firstRow.b(i).b().name == columnName) {
                colIdx = i
                break
            }
        }
        
        require(colIdx != -1) { "Column '$columnName' not found" }
        
        for (i in 0 until data.a) {
            val cell = data.b(i).b(colIdx).a
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
