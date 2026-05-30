package borg.trikeshed.cursor

import borg.trikeshed.lib.*

// ── Cursor combinators ──────────────────────────────────────────
//
// Selection, projection, widening, concatenation.
// All pure — transforms read like projections, selections, joins, and ranges.

// ── Selection ───────────────────────────────────────────────────

/** Row by index. */
operator fun Cursor.get(i: Int): RowVec = b(i)

/** Range view — composition, not control flow. */
operator fun Cursor.get(range: IntRange): Cursor =
    range.count() j { i -> this[range.first + i] }

/** Column projection by ordinal indices — reorders / projects columns. */
fun Cursor.select(vararg cols: Int): Cursor =
    size j { row ->
        val rv = this[row]
        cols.size j { c -> rv[cols[c]] }
    }

/** Column projection by name. */
fun Cursor.select(vararg names: CharSequence): Cursor {
    // Resolve name→index from first row's metadata
    val firstRow = this[0]
    val nameToIdx = mutableMapOf<CharSequence, Int>()
    for (c in 0 until firstRow.size) {
        val meta = firstRow[c].b()
        nameToIdx[meta.name] = c
    }
    val indices = names.map { name ->
        nameToIdx[name] ?: error("Column '$name' not found")
    }.toIntArray()
    return select(*indices)
}

/** Column exclusion by name. */
operator fun Cursor.minus(name: CharSequence): Cursor {
    val firstRow = this[0]
    val indices = (0 until firstRow.size).filter { c ->
        firstRow[c].b().name != name
    }.toIntArray()
    return select(*indices)
}

// ── Widening (along columns) ────────────────────────────────────

/** Join two cursors side-by-side — widens along columns. */
fun join(left: Cursor, right: Cursor): Cursor =
    minOf(left.size, right.size) j { row ->
        val lr = left[row]; val rr = right[row]
        (lr.size + rr.size) j { c ->
            if (c < lr.size) lr[c] else rr[c - lr.size]
        }
    }

// ── Concatenation (along rows) ──────────────────────────────────

/** Combine two cursors top-to-bottom — concatenates along rows. */
fun combine(top: Cursor, bottom: Cursor): Cursor =
    (top.size + bottom.size) j { row ->
        if (row < top.size) top[row] else bottom[row - top.size]
    }

// ── Projection ──────────────────────────────────────────────────

/** Cursor α — lazy map over rows. */
inline infix fun <C> Cursor.α(crossinline xform: (RowVec) -> C): Series<C> =
    size j { i -> xform(this[i]) }

// ── Head / Tail ─────────────────────────────────────────────────

/** First row. */
val Cursor.head: RowVec get() = this[0]

/** All rows except the first. */
val Cursor.tail: Cursor get() = this[1..< size]

// ── Meta access ─────────────────────────────────────────────────

/** Column metadata series from the first row. */
val Cursor.meta: Series<ColumnMeta>
    get() {
        val row = this[0]
        return row.size j { c -> row[c].b() }
    }

/** Column names. */
val Cursor.columnNames: Series<CharSequence>
    get() = meta α { it.name }

/** Column count. */
val Cursor.width: Int get() = this[0].size
