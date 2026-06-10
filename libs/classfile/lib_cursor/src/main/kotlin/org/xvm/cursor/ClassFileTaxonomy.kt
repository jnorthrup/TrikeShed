package org.xvm.cursor

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.ChunkedMutableSeries
import borg.trikeshed.lib.MutableSeries
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.joins
import borg.trikeshed.lib.size
import borg.trikeshed.lib.get
import borg.trikeshed.parse.confix.emptyCursor
import borg.trikeshed.parse.confix.widenNode
import borg.trikeshed.parse.confix.FacetDescriptor
import borg.trikeshed.parse.confix.saxWalk

/**
 * ClassFileTaxonomy — Confix-based Facetted ClassFile browse/registry.
 *
 * Ingests minimal coordinate rows from classfile scans and exposes them as:
 *   - a typed registry (register / rowAt / lookupByPoolId / filterBy*)
 *   - a Cursor projection with PointcutFacet-tagged columns for
 *     Confix/TrikeShed lazy navigation.
 */
class ClassFileTaxonomy {

    /**
     * Minimal wire-friendly coordinate row.
     * Fields are primitive at the batch boundary — strings live in the pool.
     */
    data class CoordinateRow(
        val symbolName: String,       // "owner.method" or "owner.field"
        val ownerType: String,        // class/type name
        val methodOrField: String,    // method or field name
        val classfileCoord: String,   // "owner#method" or similar coordinate
        val cpIndex: Int,             // constant-pool index (-1 if unavailable)
        val descriptor: String,       // JVM descriptor or signature
        val xvmTypeInfo: String,      // XVM type / org.xtc evidence, or ""
        val pointcutKind: Int,        // opcode byte (0x10..0xA8)
        val poolId: Int,              // stable intern-pool / hash id
    )

    // ── Registry ──────────────────────────────────────────────────────────

    // Using an Observer delegate to explicitly receive events upon mutation
    var rows: MutableSeries<CoordinateRow> by kotlin.properties.Delegates.observable(
        ChunkedMutableSeries<CoordinateRow>() as MutableSeries<CoordinateRow>
    ) { _, old, new ->
        // Fired whenever the backing series is reassigned (e.g., to PointcutMutableSeries)
        // Log/Audit the replacement:
        if (old !== new) {
            println("ClassFileTaxonomy.rows delegate updated from ${old::class.simpleName} to ${new::class.simpleName}")
        }
    }

    val size: Int get() = rows.size

    fun register(row: CoordinateRow) {
        rows.add(row)
    }

    fun rowAt(index: Int): CoordinateRow = rows[index]

    fun lookupByPoolId(poolId: Int): CoordinateRow? {
        for (i in 0 until rows.size) {
            val row = rows[i]
            if (row.poolId == poolId) return row
        }
        return null
    }

    fun filterByKind(kind: Int): ClassFileTaxonomy {
        val sub = ClassFileTaxonomy()
        for (i in 0 until rows.size) {
            val row = rows[i]
            if (row.pointcutKind == kind) sub.register(row)
        }
        return sub
    }

    fun filterByOwner(owner: String): ClassFileTaxonomy {
        val sub = ClassFileTaxonomy()
        for (i in 0 until rows.size) {
            val row = rows[i]
            if (row.ownerType == owner) sub.register(row)
        }
        return sub
    }

    fun toBlackboardEntries(): List<borg.trikeshed.parse.confix.BlackBoardEntry> {
        val list = mutableListOf<borg.trikeshed.parse.confix.BlackBoardEntry>()
        for (i in 0 until rows.size) {
            val r = rows[i]
            val json = """
                {
                  "symbolName": "${r.symbolName}",
                  "ownerType": "${r.ownerType}",
                  "methodOrField": "${r.methodOrField}",
                  "classfileCoord": "${r.classfileCoord}",
                  "cpIndex": ${r.cpIndex},
                  "descriptor": "${r.descriptor}",
                  "xvmTypeInfo": "${r.xvmTypeInfo}",
                  "pointcutKind": ${r.pointcutKind},
                  "poolId": ${r.poolId}
                }
            """.trimIndent()
            val doc = borg.trikeshed.parse.confix.confixDoc(json)
            list.add(borg.trikeshed.parse.confix.BlackBoardEntry(doc, borg.trikeshed.parse.confix.ConfixRole.OBSERVATION))
        }
        return list
    }

    fun emitSaxEvents(consumer: java.util.function.Consumer<borg.trikeshed.parse.confix.SaxEvent>) {
        val docList = toBlackboardEntries()
        for (entry in docList) {
            entry.doc.a.saxWalk { event ->
                consumer.accept(event)
            }
        }
    }

    // ── Cursor projection ─────────────────────────────────────────────────

    fun asCursor(): Cursor {
        return rows.size j { idx ->
            val r = rows[idx]
            toRowVec(r)
        }
    }

    companion object {
        val SCHEMA_REFS = listOf(
            ColumnMetaRef(0, "symbolName", "String", PointcutFacet.SymbolName),
            ColumnMetaRef(1, "ownerType", "String", PointcutFacet.TypeInfo),
            ColumnMetaRef(2, "methodOrField", "String", PointcutFacet.SymbolName),
            ColumnMetaRef(3, "classfileCoord", "String", PointcutFacet.ClassfileCoordinate),
            ColumnMetaRef(4, "cpIndex", "Int", PointcutFacet.XvmCoordinate),
            ColumnMetaRef(5, "descriptor", "String", PointcutFacet.Unfaceted),
            ColumnMetaRef(6, "xvmTypeInfo", "String", PointcutFacet.XvmCoordinate),
            ColumnMetaRef(7, "pointcutKind", "Int", PointcutFacet.Unfaceted),
            ColumnMetaRef(8, "poolId", "Int", PointcutFacet.StringPool)
        )

        fun toRowVec(r: CoordinateRow): RowVec {
            val values: Array<Any?> = arrayOf(
                0, 0, IOMemento.IoObject, emptyCursor(),
                r.symbolName, r.ownerType, r.methodOrField, r.classfileCoord,
                r.cpIndex, r.descriptor, r.xvmTypeInfo, r.pointcutKind, r.poolId
            )
            val total = values.size
            return (total j { col: Int -> values[col] }) joins (total j { col: Int ->
                when (col) {
                    0    -> { -> ColumnMeta("open",  IOMemento.IoInt) }
                    1    -> { -> ColumnMeta("close", IOMemento.IoInt) }
                    2    -> { -> ColumnMeta("tag",   IOMemento.IoObject) }
                    3    -> { -> ColumnMeta("kids",  IOMemento.IoObject) }
                    else -> { -> SCHEMA_REFS[col - 4] }
                }
            })
        }
    }
}

// ── Extension Methods to keep API backward compatible with existing tests ──

fun Cursor.rowAt(index: Int): RowVec = b(index)
val Cursor.size: Int get() = this.a


fun Cursor.columnMeta(name: String): ColumnMetaRef {
    if (a == 0) error("empty cursor")
    val firstRow = b(0)
    for (i in 4 until firstRow.a) {
        val meta = firstRow.b(i).b()
        if (meta is ColumnMetaRef && meta.name == name) return meta
    }
    error("unknown column: $name")
}

operator fun RowVec.get(name: String): Any? {
    for (i in 0 until a) {
        val meta = b(i).b()
        if (meta is ColumnMeta && meta.name == name) return b(i).a
    }
    return null
}
