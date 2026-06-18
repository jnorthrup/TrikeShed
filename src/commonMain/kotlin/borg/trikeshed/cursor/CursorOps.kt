package borg.trikeshed.cursor

import borg.trikeshed.lib.*

/**
 * CursorOps — cursor combinators with explicit dispatch.
 * 
 * Uses explicit function names instead of operator overloading to avoid
 * return-type ambiguity. The double-dispatch pattern is:
 *   - .col(i)  -> column selection -> Cursor
 *   - .row(i)  -> row access -> RowVec
 *   - .range(i..j) -> range view -> Cursor
 *   - .select(*cols) -> column projection -> Cursor
 *   - .select(*names) -> column projection by name -> Cursor
 * 
 * No operator overloading on get() to avoid return-type ambiguity.
 * Uses .b(i) for index access on Series/Cursor (no operator get on Join).
 */
object CursorOps {

    // Helper to create Join with explicit function type (noinline to fix lambda inference)
    private inline fun <A, B> join(a: A, noinline f: (Int) -> B): Join<A, (Int) -> B> = a j f

    // ── Selection ───────────────────────────────────────────────────

    /** Range view — composition, not control flow. */
    fun Cursor.range(range: IntRange): Cursor =
        join(range.count(), fun(i: Int): RowVec = this.b(range.first + i))

    /** Column projection by ordinal indices — reorders / projects columns. */
    fun Cursor.select(vararg cols: Int): Cursor {
        val self = this
        return join(size, fun(row: Int): RowVec {
            val rv = self.b(row)
            return join(cols.size, fun(c: Int): Any? = rv.b(cols[c]))
        })
    }

    /** Single column selection — returns new Cursor with that column only. */
    fun Cursor.col(col: Int): Cursor = select(col)

    /** Column projection by vararg — cursor.col(1, 2, 3) returns new Cursor with those columns. */
    fun Cursor.cols(vararg cols: Int): Cursor = select(*cols)

    /** Row access — explicit, NOT via indexing. Use .row(i) or .b(i). */
    infix fun Cursor.row(index: Int): RowVec = this.b(index)

    /** Column projection by name. */
    fun Cursor.select(vararg names: CharSequence): Cursor {
        val self = this
        val firstRow = self.b(0)
        val nameToIdx = mutableMapOf<CharSequence, Int>()
        for (c in 0 until firstRow.size) {
            val meta = firstRow.b(c).b()
            nameToIdx[meta.name] = c
        }
        val indices = names.map { name: CharSequence ->
            nameToIdx[name] ?: error("Column '$name' not found")
        }.toIntArray()
        return select(*indices)
    }

    /** Column projection by name — alias. */
    fun Cursor.col(vararg names: CharSequence): Cursor = select(*names)

    /** Column exclusion by name. */
    fun Cursor.without(name: CharSequence): Cursor {
        val self = this
        val firstRow = self.b(0)
        val indices = (0 until firstRow.size).filter { c: Int ->
            firstRow.b(c).b().name != name
        }.toIntArray()
        return select(*indices)
    }

    // ── Widening (along columns) ────────────────────────────────────

    /** Join two cursors side-by-side — widens along columns. */
    fun join(left: Cursor, right: Cursor): Cursor =
        Join(minOf(left.size, right.size), fun(row: Int): RowVec = {
            val lr = left.b(row); val rr = right.b(row)
            return join(lr.size + rr.size, fun(c: Int): Any? = 
                if (c < lr.size) lr.b(c) else rr.b(c - lr.size)
            )
        })

    // ── Concatenation (along rows) ──────────────────────────────────

    /** Combine two cursors top-to-bottom — concatenates along rows. */
    fun combine(top: Cursor, bottom: Cursor): Cursor =
        Join(top.size + bottom.size, fun(row: Int): RowVec = 
            if (row < top.size) top.b(row) else bottom.b(row - top.size)
        )

    // ── Projection ──────────────────────────────────────────────────

    /** Cursor α — lazy map over rows. */
    inline infix fun <C> Cursor.α(crossinline xform: (RowVec) -> C): Series<C> =
        Join(size, fun(i: Int): C = xform(this@Cursor.b(i)))

    // ── Head / Tail ─────────────────────────────────────────────────

    /** First row. */
    val Cursor.head: RowVec get() = this@Cursor.b(0)

    /** All rows except the first. */
    val Cursor.tail: Cursor get() = this@Cursor = this@Cursor.range(1..<size)

    /** Column metadata series from the first row. */
    val Cursor.meta: Series<ColumnMeta>
        get(): Series<ColumnMeta> {
            val row = this@Cursor.b(0)
            return Join(row.size, fun(c: Int): ColumnMeta = row.b(c).b())
        }

    /** Column names. */
    val Cursor.columnNames: Series<CharSequence>
        get() = meta α { it: ColumnMeta -> it.name }

    /** Column count. */
    val Cursor.width: Int get() = this@Cursor.b(0).size
}