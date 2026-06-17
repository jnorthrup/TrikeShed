package borg.trikeshed.lcnc

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*

/**
 * Empirical algebraic representation of an LCNC data grid.
 * It linearizes the N+1 problem loops seen in traditional UI frameworks.
 * A Grid is exactly a Cursor: indexed, columnar, sliceable.
 */
class LcncGrid(
    val cursor: Cursor,
    val srcDocs: Series<ConfixDoc>
) {
    val rowCount: Int get() = cursor.size

    // Column projection: linear array map, no reflection.
    fun projectColumns(indices: IntArray): LcncGrid {
        val newCursor: Cursor = cursor.size j { r ->
            val row = cursor[r]
            // We widen the selection into a new RowVec.
            // In a real impl, we'd compose a new FacetedRow here over the indices.
            row
        }
        return LcncGrid(newCursor, srcDocs)
    }

    // Lazy formula column projection
    fun addFormulaColumn(formula: (RowVec, Series<Byte>) -> Any?): LcncGrid {
        // Pure map over Cursor elements
        val newCursor: Cursor = cursor.size j { r ->
            val row = cursor[r]
            // compute value = formula(row)
            // append to row facets
            row
        }
        return LcncGrid(newCursor, srcDocs)
    }

    // O(1) pagination via range view composition
    fun page(start: Int, end: Int): LcncGrid {
        val count = end - start
        val newCursor: Cursor = count j { i -> cursor[start + i] }
        val newDocs: Series<ConfixDoc> = count j { i -> srcDocs[start + i] }
        return LcncGrid(newCursor, newDocs)
    }
}
