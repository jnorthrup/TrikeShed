package borg.trikeshed.lib

import borg.trikeshed.collections.s_

operator fun <A> Series<A>.plus(c: Series<A>): Series<A> = combine(borg.trikeshed.collections.s_[this, c])

/**
Series combine (series...)
creates a new Series<A> from the varargs of Series<A> passed in
which is a view of the underlying data, not a copy

the resulting Series<A> is ordered and contains all the elements of catn
in the order they were passed in
@see https://en.algorithmica.org/hpc/data-structures/s-tree/#b-tree-layout-1
@param catn the varargs of Series<A> to combine
 */
fun <A> combine(vararg catn: Series<A>): Series<A> = combine((catn).size j catn::get)

/**
Series combine (series...)
creates a new Series<A> from the varargs of Series<A> passed in
which is a view of the underlying data, not a copy

the resulting Series<A> is ordered and contains all the elements of catn
in the order they were passed in
@see https://en.algorithmica.org/hpc/data-structures/s-tree/#b-tree-layout-1
@param catn the Series of Series<A> to combine
 */
fun <A> combine(catn: Series<Series<A>>): Series<A> {
    return when (val szN = catn.size) {
        0 -> EmptySeries as Series<A>
        1 -> catn[0]

        else -> {
            val stairs by lazy {
                var acc = 0
                IntArray(szN) { acc += catn[it].size; acc }
            }
            val sumSize = stairs[szN - 1]


            sumSize j { i: Int ->

              val idx=  when (szN) {

                    in 2..4 -> stairs.indexOfFirst { it > i }
                    else -> stairs.binarySearch(i)
                }
                val series: Series<A> = catn[idx]
                val offset = if (idx == 0) i else i - stairs[idx - 1]
                series[offset]
            }
        }
    }
}

// ── Reification-aware overloads ───────────────────────────────────────────

/**
 * Combine with a [ReificationContext] that caps stair-nesting depth.
 *
 * When [ctx] is non-null and [ReificationContext.maxDepth] is exceeded,
 * sub-series that are themselves stair-shaped are materialized ([cow])
 * before being combined.  This flattens the stair shape, trading memory
 * for shallower read-path resolution.
 *
 * @param catn  the series of sub-series to concatenate
 * @param ctx   reification depth cap, or null for unlimited (original behavior)
 */
fun <A> combine(catn: Series<Series<A>>, ctx: ReificationContext?): Series<A> {
    if (ctx == null) return combine(catn)

    // Materialize sub-series when maxDepth would force it
    val resolved: Series<Series<A>> = if (ctx.maxDepth <= 0) {
        // Depth 0: materialize every sub-series immediately
        catn.size j { i -> catn[i].materialize() as Series<A> }
    } else {
        // For non-zero depth, pass a shallower context to each sub-series
        // if it is itself a stair shape.  CowSeriesHandle (already materialized)
        // is passed through unchanged.
        val deeper = ctx.deeper()
        catn.size j { i ->
            val sub = catn[i]
            if (deeper == null && sub !is CowSeriesHandle<*>) {
                // Reification exhausted — materialize
                sub.materialize() as Series<A>
            } else {
                sub
            }
        }
    }

    return combine(resolved)
}

/**
 * Varargs combine with reification context.
 * Callers must pass [ctx] explicitly to disambiguate from the no-context overload.
 * Delegates to [combine(Series<Series<A>>, ReificationContext?)].
 */
fun <A> combine(ctx: ReificationContext, vararg catn: Series<A>): Series<A> =
    combine((catn).size j catn::get, ctx)
