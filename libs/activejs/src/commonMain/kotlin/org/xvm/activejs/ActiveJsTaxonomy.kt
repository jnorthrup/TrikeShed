package org.xvm.activejs

import kotlinx.serialization.Serializable
import org.xvm.activejs.ccek.TaxonomyObserver
import org.xvm.activejs.ccek.getTaxonomyObserver
import kotlinx.coroutines.CoroutineContext

/**
 * ActiveJsTaxonomy — Confix-based Facetted ClassFile browse/registry for multiplatform targets.
 *
 * Provides a pure Kotlin implementation with expect/actual for platform-specific internals.
 * The pointcut events are fed via TypedefResolutionSeries journal, enabling reactive Confix SAX emission.
 *
 * Coordinate row shape (from classfile-coordinate-pointcuts.md):
 *   symbolName, ownerType, methodOrField, classfileCoord, cpIndex,
 *   descriptor, xvmTypeInfo, pointcutKind, poolId
 *
 * Facets extend the JVM PointcutFacet with JS-specific tags:
 *   - WasmModule, JsModule, JsPromise, JsTypedArray, JsObject, JsFunction
 *
 * Architecture:
 *   - ActiveJsTaxonomy (multiplatform registry) ← LivePointcutCursor
 *   - Live pointcut events → TypedefResolutionSeries → Confix SAX stream
 *   - Cursor views are lazy RowVec projections (Series.view boundary iteration)
 *   - CCEK SPI bus for zero-copy fanout of taxonomy updates via ObserverDelegateRegistration facet
 */
class ActiveJsTaxonomy {

    // ── Registry ────────────────────────────────────────────────────────────

    internal var rows: MutableSeries<CoordinateRow> = 
        ChunkedMutableSeries<CoordinateRow>() as MutableSeries<CoordinateRow>

    val size: Int get() = rows.a

    fun register(row: CoordinateRow, context: CoroutineContext = kotlinx.coroutines.currentCoroutineContext()) {
        rows.add(row)
        // Fire CCEK observer notification via TaxonomyObserver SPI
        context.getTaxonomyObserver()?.onRowRegistered(row)
    }

    fun rowAt(index: Int): CoordinateRow = rows.b(index)

    fun lookupByPoolId(poolId: Int): CoordinateRow? {
        for (i in 0 until rows.a) {
            val row = rows.b(i)
            if (row.poolId == poolId) return row
        }
        return null
    }

    /** Cold filter: returns a lazy Series view of matching rows. */
    fun filterByKindSeries(kind: Int): Series<CoordinateRow> = liveSeries(
        count = { rows.count { it.pointcutKind == kind } },
        access = { idx ->
            var seen = 0
            for (i in 0 until rows.a) {
                val r = rows.b(i)
                if (r.pointcutKind == kind) {
                    if (seen == idx) return@liveSeries r
                    seen++
                }
            }
            throw IndexOutOfBoundsException()
        }
    )

    /** Cold filter: returns a lazy Series view of matching rows. */
    fun filterByOwnerSeries(owner: String): Series<CoordinateRow> = liveSeries(
        count = { rows.count { it.ownerType == owner } },
        access = { idx ->
            var seen = 0
            for (i in 0 until rows.a) {
                val r = rows.b(i)
                if (r.ownerType == owner) {
                    if (seen == idx) return@liveSeries r
                    seen++
                }
            }
            throw IndexOutOfBoundsException()
        }
    )

    /** Cold filter: returns a lazy Series view of matching rows. */
    fun filterByFacetSeries(facet: ActiveJsFacet): Series<CoordinateRow> = liveSeries(
        count = { rows.count { it.activeJsFacet == facet } },
        access = { idx ->
            var seen = 0
            for (i in 0 until rows.a) {
                val r = rows.b(i)
                if (r.activeJsFacet == facet) {
                    if (seen == idx) return@liveSeries r
                    seen++
                }
            }
            throw IndexOutOfBoundsException()
        }
    )

    /** @Deprecated("Use filterByKindSeries for cold path, or CCEK observer for hot path") */
    fun filterByKind(kind: Int): ActiveJsTaxonomy {
        val sub = ActiveJsTaxonomy()
        for (i in 0 until rows.a) {
            val row = rows.b(i)
            if (row.pointcutKind == kind) sub.register(row)
        }
        return sub
    }

    /** @Deprecated("Use filterByOwnerSeries for cold path, or CCEK observer for hot path") */
    fun filterByOwner(owner: String): ActiveJsTaxonomy {
        val sub = ActiveJsTaxonomy()
        for (i in 0 until rows.a) {
            val row = rows.b(i)
            if (row.ownerType == owner) sub.register(row)
        }
        return sub
    }

    // ── Confix Integration ──────────────────────────────────────────────────

    fun toBlackboardEntries(): List<BlackBoardEntry> {
        val list = mutableListOf<BlackBoardEntry>()
        for (i in 0 until rows.a) {
            val r = rows.b(i)
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
                  "poolId": ${r.poolId},
                  "activeJsFacet": "${r.activeJsFacet}"
                }
            """.trimIndent()
            val doc = confixDoc(json)
            list.add(BlackBoardEntry(doc, ConfixRole.OBSERVATION))
        }
        return list
    }

    /** Lazy Series of BlackBoardEntry observations — cold path via Series.view. */
    fun toBlackboardEntriesSeries(): Series<BlackBoardEntry> = liveSeries(
        count = { rows.a },
        access = { idx ->
            val r = rows.b(idx)
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
                  "poolId": ${r.poolId},
                  "activeJsFacet": "${r.activeJsFacet}"
                }
            """.trimIndent()
            BlackBoardEntry(confixDoc(json), ConfixRole.OBSERVATION)
        }
    )

    fun emitSaxEvents(consumer: (SaxEvent) -> Unit) {
        val docList = toBlackboardEntries()
        for (entry in docList) {
            entry.doc.saxWalk { event ->
                consumer(event)
            }
        }
    }

    // ── Cursor Projection ───────────────────────────────────────────────────

    fun asCursor(): Cursor {
        return rows.a j { idx ->
            val r = rows.b(idx)
            toRowVec(r)
        }
    }

    fun cursorByFacet(facet: ActiveJsFacet): Cursor {
        val filtered = filterByFacetSeries(facet)
        return filtered.a j { idx -> toRowVec(filtered.b(idx)) }
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
            ColumnMetaRef(7, "pointcutKind", "Int", PointcutFacet.PointcutKind),
            ColumnMetaRef(8, "poolId", "Int", PointcutFacet.StringPool),
            ColumnMetaRef(9, "activeJsFacet", "String", PointcutFacet.ObserverDelegateRegistration, ActiveJsFacet.Unfaceted),
        )

        fun toRowVec(r: CoordinateRow): RowVec {
            val values: Array<Any?> = arrayOf(
                0, 0, 0, 0, // IoOpen, IoClose, IoTag, IoKids placeholders
                r.symbolName, r.ownerType, r.methodOrField, r.classfileCoord,
                r.cpIndex, r.descriptor, r.xvmTypeInfo, r.pointcutKind, r.poolId,
                r.activeJsFacet
            )
            val total = values.size
            return (total j { col: Int -> values[col] }) j (total j { col: Int ->
                when (col) {
                    0 -> { -> ColumnMeta("open", 0) }
                    1 -> { -> ColumnMeta("close", 0) }
                    2 -> { -> ColumnMeta("tag", "") }
                    3 -> { -> ColumnMeta("kids", "") }
                    else -> SCHEMA_REFS[col - 4]
                }
            })
        }
    }
}

/**
 * ActiveJS-specific facet extensions for JS/WASM targets.
 * These extend the JVM PointcutFacet with runtime intent tags
 * relevant to JavaScript and WebAssembly execution.
 */
enum class ActiveJsFacet {
    Unfaceted,
    WasmModule,
    JsModule,
    JsPromise,
    JsTypedArray,
    JsObject,
    JsFunction,
    JsAsyncIterator,
    JsProxy,
    JsWasmImport,
}

/**
 * Multiplatform coordinate row — wire-friendly, primitive fields at batch boundary.
 * Strings are interned via StringPool (poolId provides stable identity).
 */
@Serializable
data class CoordinateRow(
    val symbolName: String,       // "owner.method" or "owner.field"
    val ownerType: String,        // class/type name
    val methodOrField: String,    // method or field name
    val classfileCoord: String,   // "owner#method" or similar coordinate
    val cpIndex: Int,             // constant-pool index (-1 if unavailable)
    val descriptor: String,       // JVM descriptor or signature
     val pointcutKind: Int,        // opcode byte (0x10..0xA8)
    val poolId: Int,              // stable intern-pool / hash id
    val activeJsFacet: ActiveJsFacet = ActiveJsFacet.Unfaceted, // JS/WASM runtime intent
)

/**
 * ColumnMetaRef — extends ColumnMeta with ActiveJsFacet for
 * multiplatform runtime intent tagging.
 */
class ColumnMetaRef(
    val ordinal: Int,
    override val name: String,
    val typeName: String,
    val facet: PointcutFacet = PointcutFacet.Unfaceted,
    val activeJsFacet: ActiveJsFacet = ActiveJsFacet.Unfaceted,
) : ColumnMeta {
    override val a: CharSequence get() = name
    override val b: Join<TypeMemento, ColumnMeta?> get() = 0 j null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ColumnMetaRef) return false
        return ordinal == other.ordinal && name == other.name && typeName == other.typeName &&
               facet == other.facet && activeJsFacet == other.activeJsFacet
    }

    override fun hashCode(): Int = 31 * (31 * (31 * (31 * ordinal + name.hashCode()) + typeName.hashCode()) + facet.hashCode()) + activeJsFacet.hashCode()

    override fun toString(): String = "ColumnMetaRef($ordinal, $name, $typeName, $facet, $activeJsFacet)"
}