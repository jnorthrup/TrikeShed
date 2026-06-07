package borg.trikeshed.miniduck

import borg.trikeshed.parse.confix.ConfixIndex
import borg.trikeshed.parse.confix.ConfixIndexK
import borg.trikeshed.lib.get
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.`ColumnMeta↻`
import borg.trikeshed.cursor.ColumnMeta

class MiniduckConfix(val index: ConfixIndex) {
    fun getCursor(): Cursor {
        @Suppress("UNCHECKED_CAST")
        val facetedIndex = index as borg.trikeshed.lib.FacetedRow<ConfixIndexK<*>>
        return facetedIndex[ConfixIndexK.TreeCursor] as Cursor
    }

    /**
     * Extracts a relational RowVec representation (values and ColumnMeta generators)
     * at the given local element id, applying type-operators over the underlying cursor.
     */
    fun extractRelationalRow(elementId: Int): RowVec {
        val cursor = getCursor()
        return cursor[elementId]
    }
}
