@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.lib

// ============================================================================
// ReifiedSplitSeries2 — concrete Series2 that stores two Series directly.
//
// Unlike the typealias Series2<A,B> = Series<Join<A,B>> (which constructs
// a Join per indexed access via zip), this stores leftSeries and rightSeries
// separately.  The b: (Int) -> Join<A,B> property still constructs Joins on
// demand for interface compatibility, but hot paths that know their concrete
// type can bypass Join allocation by accessing .left or .right directly.
//
// Use rowVec.left for value-only columnar scans (fromCursor, GroupBy).
// Use rowVec.right for meta-only scans (Cursor.meta already does this).
// ============================================================================

class ReifiedSplitSeries2<A, B>(
    val leftSeries: Series<A>,
    val rightSeries: Series<B>
) : Series2<A, B> {

    override val a: Int get() = minOf(leftSeries.a, rightSeries.a)

    override val b: (Int) -> Join<A, B>
        get() = { i -> leftSeries[i] j rightSeries[i] }
}

// ============================================================================
// Factory — promote any Series2 to a ReifiedSplitSeries2 by materializing
// its left and right projections.  One-time cost; pays back in hot loops.
// ============================================================================

fun <A, B> Series2<A, B>.reify(): ReifiedSplitSeries2<A, B> =
    ReifiedSplitSeries2(this.left, this.right)
