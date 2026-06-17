package borg.trikeshed.forge.transport

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.parse.confix.*
import kotlinx.serialization.Serializable

// ============================================================================
// JQUERY-STYLE JSELEMENT API — ConfixCell as transport unit
// ============================================================================

/**
 * JsElement = ConfixCell = Join<RowVec, Series<Byte>>
 * Zero-copy transport unit with lazy reification.
 * 
 * Path resolution mirrors jQuery:
 *   $element.find("signals").eq(3).children().filter(".metric")
 *   → cell["signals"][3].cellKids.filter { it.tag == "metric" }
 */

// Extension: jQuery-style find by key
operator fun ConfixCell.get(key: String): ConfixCell? =
    row.step(key, src)?.let { it j src }

// Extension: jQuery-style eq by index
operator fun ConfixCell.get(idx: Int): ConfixCell? =
    row.step(idx)?.let { it j src }

// Extension: children() → Series<ConfixCell>
val ConfixCell.children: Series<ConfixCell>
    get() = row.kids.size j { row.kids[it] j src }

// Extension: parent() → ConfixCell? (requires back-reference, not in base)
val ConfixCell.parent: ConfixCell?
    get() = null  // Would need parent ref in RowVec

// Extension: find(selector) — recursive descendant search
fun ConfixCell.find(path: JsPath): ConfixCell? =
    cellGetAt(*path.map { it.key?.let { it } ?: it.idx?.let { it } ?: error("bad path") }.toTypedArray())

// Extension: filter children by facet tag
fun ConfixCell.filterFacet(facet: String, exclude: Boolean = false): Series<ConfixCell> =
    children.filter { it.row.facetTag() == facet xor exclude }

// Extension: filter children by predicate
fun ConfixCell.filter(pred: (ConfixCell) -> Boolean): Series<ConfixCell> =
    children.filter(pred)

// Extension: map over children
fun <R> ConfixCell.mapChildren(transform: (ConfixCell) -> R): Series<R> =
    children.map(transform)

// Extension: first child
val ConfixCell.first: ConfixCell?
    get() = children.firstOrNull()

// Extension: last child
val ConfixCell.last: ConfixCell?
    get() = children.lastOrNull()

// Extension: eq(index) — jQuery .eq()
fun ConfixCell.eq(idx: Int): ConfixCell? =
    if (idx in 0 until children.size) children[idx] else null

// Extension: slice(start, end) — jQuery .slice()
fun ConfixCell.slice(start: Int, end: Int): Series<ConfixCell> =
    children.slice(start.coerceAtLeast(0), end.coerceAtMost(children.size))

// Extension: reify to typed value (lazy parse)
fun <T> ConfixCell.asType(): T? = reify() as? T

// Extension: reify to String
fun ConfixCell.asString(): String? = reify() as? String

// Extension: reify to Number
fun ConfixCell.asNumber(): Number? = reify() as? Number

// Extension: reify to Boolean
fun ConfixCell.asBoolean(): Boolean? = reify() as? Boolean

// Extension: reify to Map (for objects)
fun ConfixCell.asMap(): Map<String, Any?>? {
    val obj = reify()
    return if (obj is Map<*, *>) obj as Map<String, Any?> else null
}

// Extension: reify to List (for arrays)
fun ConfixCell.asList(): List<Any?>? {
    val obj = reify()
    return if (obj is List<*>) obj else null
}

// ============================================================================
// ROWVEC FACET TAG — for filtering
// ============================================================================

/** Extract facet tag from RowVec (column 0 by convention) */
fun RowVec.facetTag(): String? =
    (this[0] as? String)?.takeIf { it.isNotBlank() }

// ============================================================================
// CONFIX DOC → FORGE TRANSPORT — root accessors
// ============================================================================

/** Get Forge root cursor: c["forge"] */
val ConfixDoc.forgeRoot: ConfixCell? get() = docAt("forge")

/** Get files cursor: c["forge"]["files"] */
val ConfixDoc.filesCursor: Cursor? get() = forgeRoot?.children

/** Get snapshots cursor: c["forge"]["snapshots"] */
val ConfixDoc.snapshotsCursor: Cursor? get() = docAt("forge", "snapshots")?.children

/** Get workflows cursor: c["forge"]["workflows"] */
val ConfixDoc.workflowsCursor: Cursor? get() = docAt("forge", "workflows")?.children

/** Get executions cursor: c["forge"]["executions"] */
val ConfixDoc.executionsCursor: Cursor? get() = docAt("forge", "executions")?.children

/** Get LCNC grid cursor: c["lcnc"]["grid"] */
val ConfixDoc.lcncGridCursor: Cursor? get() = docAt("lcnc", "grid")?.children

// ============================================================================
// DSL BUILDER — type-safe pipeline construction using root project's CursorOp
// ============================================================================

/**
 * Type-safe DSL for building CursorPipeline (root project's CursorOp algebra).
 * 
 * Usage:
 * val pipeline = cursorPipeline {
 *     path("forge", "files")         // c["forge"]["files"]
 *     filterFacet("active")          // .filterFacet("active")
 *     project("id", "path", "updatedAt")
 *     range(0, 50)                   // take 50
 * }
 * 
 * Uses borg.trikeshed.cursor.CursorOp from root project.
 */
@kotlin.jvm.JvmName("cursorPipeline")
fun cursorPipeline(builder: CursorPipelineBuilder.() -> Unit): CursorPipeline {
    val b = CursorPipelineBuilder()
    b.builder()
    return b.build()
}

class CursorPipelineBuilder {
    private val ops = mutableListOf<CursorOp>()
    
    /** Navigate path: path("forge", "files") = c["forge"]["files"] */
    fun path(vararg segments: String) {
        for (p in segments) ops.add(CursorOp.PathStep.Key(p))
    }
    
    /** Filter by facet tag: filterFacet("active") or filterFacet("debug", exclude = true) */
    fun filterFacet(facet: String, exclude: Boolean = false) {
        ops.add(CursorOp.FilterFacet(facet, exclude))
    }
    
    /** Select specific row indices: select(3, 2, 1) */
    fun select(vararg indices: Int) {
        ops.add(CursorOp.SelectIndices(indices.toList()))
    }
    
    /** Navigate to child by key: pathKey("signals") */
    fun pathKey(key: String) {
        ops.add(CursorOp.PathStep.Key(key))
    }
    
    /** Navigate to child by array index: pathIndex(3) */
    fun pathIndex(idx: Int) {
        ops.add(CursorOp.PathStep.Idx(idx))
    }
    
    /** Project columns: project("id", "value") */
    fun project(vararg columns: String) {
        ops.add(CursorOp.ProjectColumns(columns.toList()))
    }
    
    /** Range selection: range(5, 15) = cursor[5 until 15] */
    fun range(start: Int, endExclusive: Int) {
        ops.add(CursorOp.Range(start, endExclusive))
    }
    
    /** Take first n: take(50) = range(0, 50) */
    fun take(n: Int) {
        range(0, n)
    }
    
    /** Compose pipelines sequentially: then { ... } */
    fun then(builder: CursorPipelineBuilder.() -> Unit) {
        val nested = CursorPipelineBuilder()
        nested.builder()
        for (op in nested.ops) {
            if (ops.isNotEmpty()) {
                ops.add(CursorOp.Then(ops.removeLast(), op))
            } else {
                ops.add(op)
            }
        }
    }
    
    fun build(): CursorPipeline = ops.toList()
}

// ============================================================================
// JSPATH LITERALS — compile-time path construction
// ============================================================================

/**
 * JsPath literal syntax for compile-time paths.
 * 
 * Usage:
 * val path = jsPath { +"forge" +"files" +3 +"metadata" }
 * val cell = confixDoc.executePath(path)
 */
@kotlin.jvm.JvmName("jsPath")
fun jsPath(builder: JsPathBuilder.() -> Unit): JsPath {
    val b = JsPathBuilder()
    b.builder()
    return b.build()
}

class JsPathBuilder {
    private val elements = mutableListOf<JsPathElement>()
    
    /** String key step */
    operator fun String.unaryPlus() = elements.add(keyOf(this))
    
    /** Int index step */
    operator fun Int.unaryPlus() = elements.add(idxOf(this))
    
    fun build(): JsPath = elements.size j { elements[it] }
}

// Infix for path composition
infix fun JsPath.then(other: JsPath): JsPath =
    (size + other.size) j { if (it < size) this[it] else other[it - size] }

infix fun JsPath.then(key: String): JsPath =
    (size + 1) j { if (it < size) this[it] else keyOf(key) }

infix fun JsPath.then(idx: Int): JsPath =
    (size + 1) j { if (it < size) this[it] else idxOf(idx) }

// ============================================================================
// TRANSPORT SERIALIZATION — kotlinx.serialization for ConfixCell
// ============================================================================

@Serializable
data class ConfixCellWire(
    /** RowVec as columnar arrays for transport */
    val columns: Map<String, List<Any?>>,
    /** Raw bytes for zero-copy reification on receiver */
    val src: ByteArray,
    /** Column metadata for schema reconstruction */
    val meta: List<ColumnMetaWire>
)

@Serializable
data class ColumnMetaWire(
    val name: String,
    val type: String,  // IOMemento name
    val child: ColumnMetaWire? = null
)

/** Encode ConfixCell for wire transport (includes raw bytes) */
fun ConfixCell.toWire(): ConfixCellWire {
    val cols = mutableMapOf<String, List<Any?>>()
    val meta = mutableListOf<ColumnMetaWire>()
    
    // Walk RowVec columns via meta supplier
    val metaSupplier = this[1] as `ColumnMeta↻`
    val colMeta = metaSupplier()
    val kids = this[3] as Cursor
    
    // Build column arrays
    for (i in 0 until kids.size) {
        val child = kids[i]
        val childMetaSupplier = child[1] as `ColumnMeta↻`
        val cm = childMetaSupplier()
        val name = cm.name.toString()
        val values = mutableListOf<Any?>()
        
        // This would iterate rows in real impl
        // For single cell, just the value
        values.add(child[0])
        
        cols[name] = values
        meta.add(ColumnMetaWire(name, cm.type.name, cm.child?.let { toWireRecursive(it) }))
    }
    
    return ConfixCellWire(
        columns = cols,
        src = src.toByteArray(),
        meta = meta
    )
}

private fun ColumnMeta.toWireRecursive(meta: ColumnMeta): ColumnMetaWire =
    ColumnMetaWire(meta.name.toString(), meta.type.name, meta.child?.let { toWireRecursive(it) })

/** Decode from wire */
fun ConfixCellWire.toConfixCell(): ConfixCell {
    // Reconstruct RowVec from columns + meta
    // This is a simplified version — real impl rebuilds full cursor structure
    TODO("Full wire decode requires RowVec reconstruction from columnar data")
}

// ============================================================================
// PURE HELPERS
// ============================================================================

private fun <T> Series<T>.slice(start: Int, end: Int): Series<T> = {
    val s = start.coerceAtMost(size)
    val e = end.coerceAtMost(size).coerceAtLeast(s)
    (e - s) j { this[it + s] }
}()

// Type aliases from kernel
typealias `ColumnMeta↻` = () -> ColumnMeta
typealias RowVec = Series2<Any?, `ColumnMeta↻`>