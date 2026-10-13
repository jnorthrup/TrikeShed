package borg.trikeshed.cursor

import borg.trikeshed.lib.*

/** Row ordinals, independent of the physical grouping of columns in a store. */
typealias RowIndex = Series<Int>

/**
 * One read generation of a cursor, or one occurrence of a child cursor in it.
 * The owner must keep the source stable for this scope's lifetime. Reopen a root
 * scope after mutation; this is provenance, not a copy or a persistence format.
 * Two roots are distinct even when their cursors or content hashes are equal.
 */
class CursorScope internal constructor(
    val source: Cursor,
    val parent: CursorScope?,
    val row: Int,
    val column: Int,
) {
    constructor(source: Cursor) : this(source, null, -1, -1)

    fun index(): CursorIndex = CursorIndex(this, source.size j { it })

    override fun equals(other: Any?): Boolean = this === other ||
        (other is CursorScope && parent != null && parent == other.parent && row == other.row && column == other.column)

    override fun hashCode(): Int = parent?.let { (it.hashCode() * 31 + row) * 31 + column } ?: super.hashCode()
}

/** A lazy row view with ordinals in its source scope, not in its last selection. */
class CursorIndex internal constructor(val scope: CursorScope, val ordinals: RowIndex) {
    val size: Int get() = ordinals.size
    val cursor: Cursor get() = size j { row -> scope.source[sourceRow(row)] }

    fun sourceRow(row: Int): Int {
        require(row in 0 until size) { "Row outside index" }
        return ordinals[row]
    }

    /** Freeze only the ordinal vector; composing selections never copies cells. */
    fun select(rows: RowIndex): CursorIndex {
        val composed = IntArray(rows.size) { sourceRow(rows[it]) }
        return CursorIndex(scope, composed.size j composed::get)
    }

    /** Independent key indexes coexist; grouping an indexed view refines that view. */
    fun groups(vararg columns: Int): Series<CursorIndex> {
        val groups = cursor.groupClusters(columns)
        return groups α { select(it.size j it::get) }
    }

    /** AND selection: retain the left index's order and multiplicity. */
    fun intersect(other: CursorIndex): CursorIndex {
        require(scope == other.scope) { "Cannot combine different cursor scopes or generations" }
        val members = other.ordinals.view.toHashSet()
        return CursorIndex(scope, ordinals.filter { it in members })
    }

    /**
     * Descent changes ordinal space. The domain supplies a typed child accessor:
     * an IoArray cell need not be a Cursor, so generic code must not guess a cast.
     * The accessor must resolve the named column in this stable source generation.
     */
    fun descend(row: Int, column: Int, child: (RowVec) -> Cursor): CursorIndex {
        val ordinal = sourceRow(row)
        val value = scope.source[ordinal]
        require(column in 0 until value.size) { "Child column outside row" }
        return CursorScope(child(value), scope, ordinal, column).index()
    }
}
