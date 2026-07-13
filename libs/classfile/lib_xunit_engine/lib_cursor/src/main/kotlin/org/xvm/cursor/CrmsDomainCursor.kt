package org.xvm.cursor

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/**
 * Factory for CRMS domain cursors: reflectively maps bean Series -> Cursor,
 * dispatching facet assignment via ReflectiveFacet conventions.
 *
 * Also owns the child facet registry and the event ring factory.
 */
object CrmsDomainCursor {

    /**
     * Build a Cursor from a Series of beans using ReflectiveFacet reflection.
     * Each bean becomes a RowVec; the column schema is derived from the first element's type.
     */
    fun <T : Any> from(beans: Series<T>): Cursor {
        if (beans.a == 0) return emptyCursor()
        return beans.a j { rowIdx: Int -> ReflectiveFacet.toRowVec(beans.b(rowIdx)) }
    }

    /**
     * Returns the "child row" facet for a named branch column.
     * Convention:
     *   constants / methods / fields   -> ClassfileTaxonomy
     *   edges                          -> EdgeTaxonomy
     *   events                         -> SynapsePhilum
     */
    fun childFacet(columnName: String): PointcutFacet = when (columnName) {
        "constants", "methods", "fields" -> PointcutFacet.ClassfileTaxonomy
        "edges" -> PointcutFacet.EdgeTaxonomy
        "events" -> PointcutFacet.SynapsePhilum
        else -> PointcutFacet.ChildRows
    }

    /** Allocate a packed event ring of the given capacity. */
    fun eventRing(capacity: Int): EventRing = EventRing(capacity)
}

// ── EventRing ─────────────────────────────────────────────────────────────────

/**
 * Packed event ring: stores events as (nanoTime, opcode, xvmCoordinate, symbolId, wireproto).
 * asCursor() returns a Cursor over the appended events; each row is a RowVec with faceted cells.
 */
class EventRing(capacity: Int) {

    private data class EventRecord(
        val nanoTime: Long,
        val opcode: Int,
        val xvmCoordinate: Int,
        val symbolId: Int,
        val wireproto: MemSegment,
    )

    private val buf = ArrayDeque<EventRecord>(capacity)
    private val cap = capacity

    fun append(opcode: Int, nanoTime: Long, xvmCoordinate: Int, symbolId: Int, bytes: ByteArray) {
        if (buf.size >= cap) buf.removeFirst()
        buf.addLast(EventRecord(nanoTime, opcode, xvmCoordinate, symbolId, MemSegment(bytes)))
    }

    fun asCursor(): Cursor {
        val snapshot = buf.toList()
        return snapshot.size j { idx: Int -> eventRowVec(snapshot[idx]) }
    }

    private companion object {
        val nanoRef = ColumnMetaRef(0, "nanoTime", "Long", PointcutFacet.VmStats)
        val opcodeRef = ColumnMetaRef(1, "opcode", "Int", PointcutFacet.PointcutKind)
        val xvmRef = ColumnMetaRef(2, "xvmCoordinate", "Int", PointcutFacet.XvmCoordinate)
        val symRef = ColumnMetaRef(3, "symbolId", "Int", PointcutFacet.SymbolName)
        val wireRef = ColumnMetaRef(4, "wireproto", "MemSegment", PointcutFacet.Wireproto)

        fun eventRowVec(r: EventRecord): RowVec = 5 j { col: Int ->
            when (col) {
                0 -> cell(r.nanoTime, nanoRef)
                1 -> cell(r.opcode, opcodeRef)
                2 -> cell(r.xvmCoordinate, xvmRef)
                3 -> cell(r.symbolId, symRef)
                4 -> cell(r.wireproto, wireRef)
                else -> throw IndexOutOfBoundsException(col)
            }
        }
    }
}

// ── helpers ───────────────────────────────────────────────────────────────────

internal fun emptyCursor(): Cursor = 0 j { _: Int -> throw IndexOutOfBoundsException() }
