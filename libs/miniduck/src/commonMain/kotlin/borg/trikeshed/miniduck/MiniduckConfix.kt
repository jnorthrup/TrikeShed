package borg.trikeshed.miniduck

import borg.trikeshed.parse.confix.ConfixIndex
import borg.trikeshed.parse.confix.ConfixIndexK
import borg.trikeshed.lib.get
import borg.trikeshed.cursor.Cursor

class MiniduckConfix(val index: ConfixIndex) {
    fun getCursor(): Cursor {
        // ConfixIndex is defined as FacetedRow<Any> in Confix.kt but ConfixIndexK is the actual key type
        // Use unchecked cast to work around the generic parameter discrepancy
        @Suppress("UNCHECKED_CAST")
        val facetedIndex = index as borg.trikeshed.lib.FacetedRow<ConfixIndexK<*>>
        return facetedIndex[ConfixIndexK.TreeCursor] as Cursor
    }
}
