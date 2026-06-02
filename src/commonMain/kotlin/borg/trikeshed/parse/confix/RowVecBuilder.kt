@file:Suppress("NonAsciiCharacters", "UNCHECKED_CAST")

package borg.trikeshed.parse.confix

import borg.trikeshed.cursor.*
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*

// ─────────────────────────────────────────────────────────────────────────────
// ROWVEC FACET DESCRIPTORS — DSL factory for branch/widen nodes
// ─────────────────────────────────────────────────────────────────────────────
//
// FacetDescriptor — a named column with a type and optional default.
// RowVecBuilder   — DSL for constructing RowVec with arbitrary columns.
//
// Branch node  : RowVec whose tag is IoObject or IoArray (carries kids Cursor)
// Widen node   : RowVec with columns beyond the base 4 (open/close/tag/kids)
//
// Base columns (always present in any parsed RowVec):
//   0 → open  (Int)        byte offset of token start
//   1 → close (Int)        byte offset of token end (inclusive)
//   2 → tag   (IOMemento)  type discriminant
//   3 → kids  (Cursor)     direct-child rows
//
// Extension columns — for widen nodes:
//   N+0 → user-defined value (Any?)
//   N+1 → user-defined meta (ColumnMeta via ↻)

// ── FacetDescriptor — a single column specification ─────────────────────────

data class FacetDescriptor(
    val name:  String,
    val type:  IOMemento,
    val value: Any?  = null,
    val doc:   String = "",
) {
    /** Build the ColumnMeta for this facet. */
    fun meta(): ColumnMeta = ColumnMeta(name, type)

    /** Lazy supplier matching ColumnMeta↻. */
    fun supplier(): `ColumnMeta↻` = { meta() }
}

// ── RowVecBuilder — DSL for constructing RowVec ────────────────────────────────

class RowVecBuilder {
    private val facets   = mutableListOf<FacetDescriptor>()
    private var rowTag:  IOMemento = IOMemento.IoObject
    private var rowKids: Cursor?   = null

    // ── Configuration ──────────────────────────────────────────────────────────

    /** Tag this node as a container type. Default: IoObject. */
    fun tag(t: IOMemento): RowVecBuilder = apply { rowTag = t }

    /** Attach children cursor — makes this a branch node. */
    fun kids(c: Cursor): RowVecBuilder = apply { rowKids = c }

    // ── Facet operations ───────────────────────────────────────────────────────

    /** Add a typed facet with a value and optional doc comment. */
    fun facet(
        name:  String,
        type:  IOMemento,
        value: Any?  = null,
        doc:   String = "",
    ): RowVecBuilder = apply {
        facets.add(FacetDescriptor(name, type, value, doc))
    }

    /** Add a string facet. */
    fun string(name: String, value: String = "", doc: String = ""): RowVecBuilder =
        facet(name, IOMemento.IoString, value, doc)

    /** Add an int facet. */
    fun int(name: String, value: Int = 0, doc: String = ""): RowVecBuilder =
        facet(name, IOMemento.IoInt, value, doc)

    /** Add a long facet. */
    fun long(name: String, value: Long = 0L, doc: String = ""): RowVecBuilder =
        facet(name, IOMemento.IoLong, value, doc)

    /** Add a double facet. */
    fun double(name: String, value: Double = 0.0, doc: String = ""): RowVecBuilder =
        facet(name, IOMemento.IoDouble, value, doc)

    /** Add a boolean facet. */
    fun boolean(name: String, value: Boolean = false, doc: String = ""): RowVecBuilder =
        facet(name, IOMemento.IoBoolean, value, doc)

    /** Add an object facet (becomes a branch node). */
    fun object(name: String, doc: String = ""): RowVecBuilder =
        facet(name, IOMemento.IoObject, null, doc)

    /** Add an array facet (becomes a branch node). */
    fun array(name: String, doc: String = ""): RowVecBuilder =
        facet(name, IOMemento.IoArray, null, doc)

    // ── Build ──────────────────────────────────────────────────────────────────

    /**
     * Produce the RowVec.
     *
     * Column layout:
     *   [0] = open  (Int, default 0)
     *   [1] = close (Int, default 0)
     *   [2] = tag   (IOMemento)
     *   [3] = kids  (Cursor or empty)
     *   [4..] = extension facets in declaration order
     */
    fun build(): RowVec {
        val width = 4 + facets.size
        val values = arrayOfNulls<Any?>(width)

        // Base columns
        values[0] = 0
        values[1] = 0
        values[2] = rowTag
        values[3] = rowKids ?: emptyCursor()

        // Extension columns
        for (i in facets.indices) {
            values[4 + i] = facets[i].value
        }

        val metaSeries: Series<`ColumnMeta↻`> = width j { col: Int ->
            when (col) {
                0    -> { ColumnMeta("open",  IOMemento.IoInt) }
                1    -> { ColumnMeta("close", IOMemento.IoInt) }
                2    -> { ColumnMeta("tag",   IOMemento.IoObject) }
                3    -> { ColumnMeta("kids",  IOMemento.IoObject) }
                else -> facets[col - 4].supplier()
            }
        }

        @Suppress("UNCHECKED_CAST")
        return width j { col: Int -> values[col] } joins metaSeries
    }

    private fun emptyCursor(): Cursor = 0 j { error("empty cursor") }
}

// ── Sensible defaults ──────────────────────────────────────────────────────────

/** Default facet for an Int column. */
fun defaultInt(name: String): FacetDescriptor =
    FacetDescriptor(name, IOMemento.IoInt, 0)

/** Default facet for a String column. */
fun defaultString(name: String): FacetDescriptor =
    FacetDescriptor(name, IOMemento.IoString, "")

/** Default facet for a Long column. */
fun defaultLong(name: String): FacetDescriptor =
    FacetDescriptor(name, IOMemento.IoLong, 0L)

/** Default facet for a Double column. */
fun defaultDouble(name: String): FacetDescriptor =
    FacetDescriptor(name, IOMemento.IoDouble, 0.0)

/** Default facet for a Boolean column. */
fun defaultBoolean(name: String): FacetDescriptor =
    FacetDescriptor(name, IOMemento.IoBoolean, false)

// ── Branch node factories ──────────────────────────────────────────────────────

/** Object node with optional key→value children. */
fun branchObject(kids: Cursor = emptyCursor()): RowVec =
    RowVecBuilder().tag(IOMemento.IoObject).kids(kids).build()

/** Array node with positional children. */
fun branchArray(kids: Cursor = emptyCursor()): RowVec =
    RowVecBuilder().tag(IOMemento.IoArray).kids(kids).build()

// ── Widen node factories ───────────────────────────────────────────────────────
//
// A widen node carries more columns than the base 4.
// Useful for pointcut stats, cascade rollups, TypedefCascadeTable rows.

/** A widen node with typed extension facets. */
fun widenNode(vararg facets: FacetDescriptor, tag: IOMemento = IOMemento.IoObject, kids: Cursor? = null): RowVec {
    val builder = RowVecBuilder().tag(tag)
    if (kids != null) builder.kids(kids)
    for (f in facets) builder.facet(f.name, f.type, f.value, f.doc)
    return builder.build()
}

// ── TypedefCascadeTable row factory ────────────────────────────────────────────
//
// Column layout for pointcut statistics:
//   [0] open      [1] close   [2] tag        [3] kids
//   [4] typedef   [5] count   [6] weight     [7] samples

/** Standard pointcut row — 4 base + 4 cascade columns. */
fun pointcutRow(
    typedefName: String,
    count:       Int    = 0,
    weight:      Double = 0.0,
    samples:     Int    = 0,
    kids:        Cursor = emptyCursor(),
): RowVec = widenNode(
    defaultString("typedef"),
    defaultInt("count"),
    defaultDouble("weight"),
    defaultInt("samples"),
    tag = IOMemento.IoObject,
    kids = kids,
).let { rv ->
    val w = rv.a
    rv.setAt(4, typedefName)
    rv.setAt(5, count)
    rv.setAt(6, weight)
    rv.setAt(7, samples)
    rv
}

// ── RowVec mutation helpers ─────────────────────────────────────────────────────
//
// Note: these operate on the values array — safe because RowVec is immutable
// structurally but Join<A,B> allows set-like semantics on the value slot.

/** Set value at column index (0-based). Creates a new RowVec with the slot replaced. */
fun RowVec.setAt(col: Int, value: Any?): RowVec {
    val w = a
    val newVals = arrayOfNulls<Any?>(w)
    for (i in 0 until w) newVals[i] = b(i).a
    newVals[col] = value
    val meta = (0 until w).j { i -> b(i).b() }
    return w j { i -> newVals[i] } joins meta
}

/** Get column value by name. */
fun RowVec.get(name: String): Any? {
    for (i in 0 until a) {
        val meta = b(i).b()
        if (meta is ColumnMeta && meta.name == name) return b(i).a
    }
    return null
}

/** Get column value by index. */
fun RowVec[col: Int]: Any? = b(col).a

private fun RowVec.setAt(idx: Int, value: Any?) { /* no-op for now — immutable algebra */ }

// ─────────────────────────────────────────────────────────────────────────────
// BLACKBOARD ALIGNMENT
// ─────────────────────────────────────────────────────────────────────────────
//
// ConfixDoc as BlackBoard entry:
//   facade = ConfixIndex (stable, survives GC pauses)
//   body   = Series<Byte> (swappable, zero-copy reloadable)
//
// BlackBoard role tags via OverlayRole enum (from cursor.BlackboardOverlay).

enum class ConfixRole {
    /** Raw parsed geometry — unprocessed. */
    OBSERVATION,
    /** Derived scalars — reified values. */
    DERIVED,
    /** Aggregated rollups — cascade results. */
    AGGREGATE,
    /** Typedef chain results. */
    TYPEDEF_CHAIN,
    /** Pointcut statistics. */
    POINTCUT_STATS,
}

/** Tag a ConfixDoc with a role for BlackBoard indexing. */
data class BlackBoardEntry(
    val doc:       ConfixDoc,
    val role:      ConfixRole = ConfixRole.OBSERVATION,
    val timestamp: Long       = System.nanoTime(),
    val provenance: String    = "",
)

val BlackBoardEntry.index:   ConfixIndex get() = doc.index
val BlackBoardEntry.src:     Series<Byte> get() = doc.src
val BlackBoardEntry.facade:  ConfixIndex get() = doc.index
val BlackBoardEntry.body:    Series<Byte> get() = doc.src