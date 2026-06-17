package org.xvm.cursor

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/**
 * MetaSeries<Input, Output> — lazy codec/filter/projection from source domain to Cursor/RowVec.
 *
 * RED stub — compiles; goes green when cursor() is implemented to correctly filter+codec.
 */
class MetaSeries<Input, Output>(
    val filter: (Input) -> Boolean,
    val codec: (Input) -> Output,
    val refs: Series<ColumnMetaRef>,
) {
    fun cursor(source: Series<Input>): Cursor {
        // Collect matching rows
        val rows = mutableListOf<RowVec>()
        for (i in 0 until source.a) {
            val item = source.b(i)
            if (filter(item)) {
                @Suppress("UNCHECKED_CAST")
                rows.add(codec(item) as RowVec)
            }
        }
        return rows.size j { idx: Int -> rows[idx] }
    }
}

/** Classfile package filter -> RowVec taxonomy. RED stub. */
class ClassfileMetaSeries(val packagePrefix: String) {
    fun cursor(source: Series<String>): Cursor {
        val rows = mutableListOf<RowVec>()
        for (i in 0 until source.a) {
            val name = source.b(i)
            if (name.startsWith(packagePrefix)) rows.add(classfileRow(name))
        }
        return rows.size j { idx: Int -> rows[idx] }
    }
    private fun classfileRow(name: String): RowVec {
        val id = Symbols.symbol(name)
        val ref = ColumnMetaRef(0, "symbolName", "SymbolId", PointcutFacet.SymbolName)
        return 1 j { _: Int -> cell(id, ref) }
    }
}

/** MemSegment event filter by opcode range -> RowVec. RED stub. */
class EventMetaSeries(val opcodeRange: IntRange) {
    fun cursor(source: Series<MemSegment>): Cursor {
        val rows = mutableListOf<RowVec>()
        for (i in 0 until source.a) {
            val seg = source.b(i)
            val opcode = seg.bytes[0].toInt() and 0xFF
            if (opcode in opcodeRange) rows.add(eventRow(seg))
        }
        return rows.size j { idx: Int -> rows[idx] }
    }
    private fun eventRow(seg: MemSegment): RowVec {
        val ref = ColumnMetaRef(0, "wireproto", "MemSegment", PointcutFacet.Wireproto)
        return 1 j { _: Int -> cell(seg, ref) }
    }
}

/** JsonCell path glob filter -> RowVec. RED stub. */
class ConfixMetaSeries(val pathGlob: String) {
    private val prefix = pathGlob.trimEnd('*').trimEnd('/')
    fun cursor(source: Series<JsonCell>): Cursor {
        val rows = mutableListOf<RowVec>()
        for (i in 0 until source.a) {
            val cell = source.b(i)
            if (cell.path.startsWith(prefix)) rows.add(jsonRow(cell))
        }
        return rows.size j { idx: Int -> rows[idx] }
    }
    private fun jsonRow(jc: JsonCell): RowVec {
        val id = Symbols.symbol(jc.path)
        val ref = ColumnMetaRef(0, "confixPath", "SymbolId", PointcutFacet.ConfixMeta)
        return 1 j { _: Int -> cell(id, ref) }
    }
}

/** Opaque JSON cell type — placeholder for Confix JSON facade. RED stub. */
data class JsonCell(val path: String, val rawValue: Any?)

/** K-boundary: maps a Series of keys to a Series of RowVec. RED stub. */
class KBoundary(
    val name: SymbolRef,
    val keys: Series<String>,
    val row: (String) -> RowVec,
) {
    fun rows(): Series<RowVec> = keys.a j { idx: Int -> row(keys.b(idx)) }
}

/** Factory for RowVecs from K-boundary keys.
 *
 * Layout: col 0 = key (SymbolRef.id.raw), col 1 = key string (SymbolRef.id.raw),
 * col 2 = facet name (interned symbol id Int), col N+3 = remaining column refs.
 */
object KRowVecFactory {
    fun rowVec(key: SymbolRef, facet: PointcutFacet, columns: Series<ColumnMetaRef>): RowVec {
        val facetSymbolId = StringPool.intern(facet.name)
        val total = maxOf(3, columns.a)
        return total j { col: Int ->
            when (col) {
                0 -> cell(key.id.raw, columns.b(0))
                1 -> cell(key.id.raw, if (columns.a > 1) columns.b(1) else columns.b(0))
                2 -> {
                    val ref = ColumnMetaRef(2, "facetName", "SymbolId", PointcutFacet.SymbolName)
                    cell(facetSymbolId, ref)
                }
                else -> {
                    val idx = col - 3 + 3
                    val ref = if (idx < columns.a) columns.b(idx) else columns.b(0)
                    cell(key.id.raw, ref)
                }
            }
        }
    }
}

/** Collects a Series<RowVec> into a Series<Series<RowVec>> grouped by facet. RED stub. */
object MetaSeriesCollector {
    /** Wrap each row as its own single-row Series. Series<RowVec> -> Series<Series<RowVec>>. */
    fun collect(rows: Series<RowVec>): Series<Series<RowVec>> {
        return rows.a j { idx: Int -> 1 j { _: Int -> rows.b(idx) } }
    }
}

/** Column refs for XSrcFile facet domain. RED stub. */
// XSrcFileFacetFactory is defined in SyntheticPointcutRowVecFactory.kt
