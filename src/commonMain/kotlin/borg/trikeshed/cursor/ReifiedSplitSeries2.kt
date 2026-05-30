package borg.trikeshed.cursor

import borg.trikeshed.lib.*

// ── ReifiedSplitSeries2 ────────────────────────────────────────
//
// The hot-path optimization: column-major storage inside the row-major
// RowVec typealias. Zero Join allocation for value-only columnar scans.

/**
 * Stores left/right Series separately. [b] still constructs Joins on demand,
 * but [leftSeries], [rightSeries] bypass the Join entirely for columnar access.
 */
class ReifiedSplitSeries2<A, B>(
    val leftSeries: Series<A>,
    val rightSeries: Series<B>,
) : Join<Int, (Int) -> Join<A, B>> {
    override val a: Int get() = leftSeries.size
    override val b: (Int) -> Join<A, B> = { i -> leftSeries[i] j rightSeries[i] }
}

// ── RowVec hot-path accessors ───────────────────────────────────

/**
 * Values series — zero allocation when the concrete type is ReifiedSplitSeries2.
 * Falls back to α-conversion otherwise.
 */
val RowVec.values: Series<Any?>
    get() = (this as? ReifiedSplitSeries2<*, *>)?.leftSeries as? Series<Any?>
        ?: this α { it.a }

/**
 * Metadata series — zero allocation when the concrete type is ReifiedSplitSeries2.
 * Falls back to α-conversion otherwise.
 */
val RowVec.metas: Series<`ColumnMeta↻`>
    get() = (this as? ReifiedSplitSeries2<*, *>)?.rightSeries as? Series<`ColumnMeta↻`>
        ?: this α { it.b }

/**
 * Resolve metadata at column index.
 */
fun RowVec.meta(col: Int): ColumnMeta = this[col].b()
