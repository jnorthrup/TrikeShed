package org.xvm.cursor

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.j

/**
 * FacetReifier: dispatch table for facet-driven reification.
 * Mirrors the dispatch table in TODO.md.
 */
object FacetReifier {

    fun reify(facet: PointcutFacet, value: Any?): Any? = when (facet) {
        PointcutFacet.SymbolName,
        PointcutFacet.TypeInfo,
        PointcutFacet.ClassfileCoordinate,
        PointcutFacet.XvmCoordinate,
        PointcutFacet.StringPool -> when (value) {
            is SymbolRef -> value.value
            is Int -> StringPool.resolve(value)
            else -> value
        }
        PointcutFacet.ChildRows -> when (value) {
            is Lazy<*> -> value.value
            else -> value
        }
        PointcutFacet.Wireproto -> when (value) {
            is MemSegment -> value // raw segment; caller decodes via WireCodec
            else -> value
        }
        else -> value
    }
}

/**
 * ConfixFacade — walks a RowVec without forcing lazy children.
 *
 * walk(row) — returns WalkResult with reifiedCells() and forcedAnyChild().
 * reifyRow(row) — returns a ReifiedRow where scalar cells are reified but children stay lazy.
 */
object ConfixFacade {

    fun walk(row: RowVec): WalkResult {
        val reified = mutableListOf<Pair<Int, Any?>>()

        for (col in 0 until row.a) {
            val cell = row.b(col)
            val ref = cell.b() as? ColumnMetaRef ?: continue

            when (ref.facet) {
                PointcutFacet.ChildRows -> { /* do NOT force */ }
                else -> reified.add(col to cell.a)
            }
        }

        return WalkResult(reified, false)
    }

    fun reifyRow(row: RowVec): ReifiedRow {
        val cells = Array<Any?>(row.a) { null }
        val lazyForcedFlags = BooleanArray(row.a)

        for (col in 0 until row.a) {
            val cell = row.b(col)
            val ref = cell.b() as? ColumnMetaRef
            val facet = ref?.facet ?: PointcutFacet.Unfaceted

            cells[col] = when (facet) {
                PointcutFacet.ChildRows -> cell.a // keep Lazy, not forced
                else -> FacetReifier.reify(facet, cell.a)
            }
            lazyForcedFlags[col] = false
        }

        return ReifiedRow(row.a, cells, lazyForcedFlags, row)
    }

    class WalkResult(
        private val cells: List<Pair<Int, Any?>>,
        private val anyChildForced: Boolean,
    ) {
        fun reifiedCells(): List<Pair<Int, Any?>> = cells
        fun forcedAnyChild(): Boolean = anyChildForced
    }

    class ReifiedRow(
        val size: Int,
        private val cells: Array<Any?>,
        private val forcedFlags: BooleanArray,
        private val source: RowVec,
    ) {
        fun b(col: Int): RowVecCell = source.b(col)

        /** Returns true if ANY lazy cell in the given column range was forced. */
        fun anyLazyForced(range: IntRange): Boolean = range.any { forcedFlags[it] }
    }
}
