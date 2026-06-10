package org.xvm.activejs

import kotlinx.serialization.Serializable

/**
 * Expect class for MutableSeries (from borg.trikeshed.lib).
 */
expect class MutableSeries<T> {
    fun add(element: T)
    val a: Int
    val b: (Int) -> T
}

/**
 * Expect class for ChunkedMutableSeries.
 */
expect class ChunkedMutableSeries<T> : MutableSeries<T>

/**
 * Expect class for ColumnMeta (Join<CharSequence, Join<TypeMemento, ColumnMeta?>>).
 */
expect class ColumnMeta {
    val a: CharSequence
    val b: Join<TypeMemento, ColumnMeta?>
    constructor(name: String, memento: Any)
}

/**
 * Expect class for TypeMemento.
 */
expect class TypeMemento

/**
 * Expect function for `j` (Join factory).
 */
expect fun <A, B> j(a: A, b: B): Join<A, B>

/**
 * Expect function for `Join` constructor (Int, (Int) -> T) -> Series<T>.
 */
expect fun <T> Join(count: Int, access: (Int) -> T): Series<T>

/**
 * Expect class for ColumnMetaRef.
 */
expect class ColumnMetaRef(
    val ordinal: Int,
    val name: String,
    val typeName: String,
    val facet: PointcutFacet,
) {
    val activeJsFacet: ActiveJsFacet = ActiveJsFacet.Unfaceted
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
    val xvmTypeInfo: String,      // XVM type / org.xtc evidence, or ""
    val pointcutKind: Int,        // opcode byte (0x10..0xA8)
    val poolId: Int,              // stable intern-pool / hash id
    val activeJsFacet: ActiveJsFacet = ActiveJsFacet.Unfaceted, // JS/WASM runtime intent
)

/**
 * Multiplatform runtime intent facets for JS/WASM targets.
 * These extend the JVM PointcutFacet with tags relevant to
 * JavaScript and WebAssembly execution.
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
 * Live pointcut event structure matching the JVM wire format.
 */
data class PointcutEvent(
    val seq: Int,
    val nano: Long,
    val opcode: Int,
    val phase: String,
    val addr: Int,
    val method: String,
)

/**
 * Live query specification — composable filters for cold/reactive queries.
 */
data class LiveQuery(
    val kind: Int? = null,
    val facet: ActiveJsFacet? = null,
    val phase: String? = null,
    val ownerPattern: String? = null,
)

/**
 * Expect class for JVM-specific PointcutFacet.
 * Actual implementation in jvmMain delegates to org.xvm.cursor.PointcutFacet.
 */
expect enum class PointcutFacet {
    Unfaceted,
    SymbolName,
    TypeInfo,
    ClassfileCoordinate,
    XvmCoordinate,
    PointcutKind,
    StringPool,
    Wireproto,
    ChildRows,
    ConfixMeta,
    VmStats,
    ObserverDelegateRegistration,
    ReduxPhilum,
    SynapsePhilum,
    ClassfileTaxonomy,
    EdgeTaxonomy,
    CrmsDomain,
    XSrcFile,
}

/**
 * Expect class for JVM-specific TypedefResolutionSeries.
 * Journal-backed shared mutable state for type resolution.
 */
expect object TypedefResolutionSeries {
    fun record(poolId: Int, siteOrd: Int, className: String, coordination: String, success: Boolean): Long
    fun size(): Int
    fun reset()
}

/**
 * Expect class for JVM-specific ClassFileTaxonomy.
 * Registry of coordinate rows with Confix projection.
 */
expect class ClassFileTaxonomy {
    val size: Int
    fun register(row: Any) // CoordinateRow in JVM impl
    fun rowAt(index: Int): Any
    fun lookupByPoolId(poolId: Int): Any?
    fun filterByKind(kind: Int): ClassFileTaxonomy
    fun filterByOwner(owner: String): ClassFileTaxonomy
    fun asCursor(): Cursor
    fun toBlackboardEntries(): List<BlackBoardEntry>
    fun emitSaxEvents(consumer: (SaxEvent) -> Unit)
}

/**
 * Expect type for Cursor (Join<Int, (Int) -> RowVec>).
 */
expect class Cursor {
    val size: Int
    operator fun get(index: Int): RowVec
    fun columnMeta(name: String): ColumnMetaRef
}

/**
 * Expect type for RowVec (Join<Int, (Int) -> Any?>).
 */
expect class RowVec {
    val a: Int
    val b: (Int) -> Any?
}

/**
 * Expect class for BlackBoardEntry.
 */
expect class BlackBoardEntry(
    val doc: Any, // ConfixDoc
    val role: ConfixRole,
)

/**
 * Expect enum for ConfixRole.
 */
expect enum class ConfixRole {
    OBSERVATION,
    COMMAND,
    QUERY,
}

/**
 * Expect sealed class for SaxEvent.
 */
expect sealed class SaxEvent {
    data class Enter(val memento: Any, val offset: Int) : SaxEvent()
    data class Leave(val memento: Any, val offset: Int) : SaxEvent()
}

/**
 * Expect type for IOMemento.
 */
expect object IOMemento {
    val IoInt: Any
    val IoLong: Any
    val IoString: Any
    val IoObject: Any
    val IoArray: Any
}

/**
 * Expect function for confixDoc.
 */
expect fun confixDoc(json: String): Any

/**
 * Expect extension for saxWalk.
 */
expect fun Any.saxWalk(action: (SaxEvent) -> Unit)

/**
 * Expect function for liveSeries.
 */
expect fun <T> liveSeries(count: () -> Int, access: (Int) -> T): Series<T>

/**
 * Expect class for Series.
 */
expect class Series<T> {
    val a: Int
    val b: (Int) -> T
}

/**
 * Expect class for Join.
 */
expect class Join<A, B> {
    val a: A
    val b: B
}