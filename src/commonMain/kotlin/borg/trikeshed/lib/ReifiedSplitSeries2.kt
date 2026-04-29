@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.lib

import kotlin.jvm.JvmName

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

class ReifiedSplitSeries2<A, B>(val leftSeries: Series<A>, val rightSeries: Series<B>) : Series2<A, B> {

    override val a: Int get() = minOf(leftSeries.a, rightSeries.a)
    override val b: (Int) -> Join<A, B>
        get() = { i -> leftSeries[i] j rightSeries[i] }

    // ── Column selection ──────────────────────────────────────────────

    /** Select a subset of columns by index, returning a new split-series.
     *  Zero per-cell Join allocation — values/metas indexed directly. */
    fun select(vararg indices: Int): ReifiedSplitSeries2<A, B> =
        ReifiedSplitSeries2(
            indices.size j { leftSeries[indices[it]] },
            indices.size j { rightSeries[indices[it]] },
        )

    /** Select a single column's value.  Zero allocation — direct Series index. */
    fun valueAt(col: Int): A = leftSeries[col]

    /** Select a single column's meta/right value. */
    fun rightAt(col: Int): B = rightSeries[col]

    // ── Structure-preserving transforms ──────────────────────────────────

    /** Transform left values, preserving split structure. */
    inline fun <C> mapLeft(crossinline f: (A) -> C): ReifiedSplitSeries2<C, B> =
        ReifiedSplitSeries2(
            leftSeries.size j { f(leftSeries[it]) },
            rightSeries,
        )

    /** Transform right values, preserving split structure. */
    inline fun <C> mapRight(crossinline f: (B) -> C): ReifiedSplitSeries2<A, C> =
        ReifiedSplitSeries2(
            leftSeries,
            rightSeries.size j { f(rightSeries[it]) },
        )

    /** Swap left/right halves. */
    fun swap(): ReifiedSplitSeries2<B, A> =
        ReifiedSplitSeries2(rightSeries, leftSeries)

    companion object {
        operator fun <A, B> invoke(it: Series2<A, B>) = ReifiedSplitSeries2(it.left, it.right);

        @JvmName("invokeTwinSeries")
        fun <A> invoke(twin: Twin<Series<A>>): ReifiedSplitSeries2<A, A> = ReifiedSplitSeries2(twin.a, twin.b)
        @JvmName("invokeSeriesTwin")
        fun <A> invoke(twin: Series<Twin<A>>): ReifiedSplitSeries2<A, A> =
            ReifiedSplitSeries2(twin.right, twin.left)

        fun <A, B> Series2<A, B>.reify(): ReifiedSplitSeries2<A, B> = ReifiedSplitSeries2<A, B>(this).reify()
        @JvmName("reifyTwinSeries")
        fun <A> Twin<Series<A>>.reify(): ReifiedSplitSeries2<A, A> = ReifiedSplitSeries2(this.a, this.b)
        @JvmName("reifySeriesTwin")
        fun <A> Series<Twin<A>>.reify(): ReifiedSplitSeries2<A, A> = ReifiedSplitSeries2(this.right, this.left)
    }
}
