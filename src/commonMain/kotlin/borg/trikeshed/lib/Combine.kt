package borg.trikeshed.lib

import borg.trikeshed.collections.s_
import kotlin.jvm.JvmInline
import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * Reification budget for lazy stair-shaped series composition.
 *
 * [isExhausted] tells combine/join callers to materialize before adding
 * another lazy layer.  [deeper] is intentionally non-null so budget flow stays
 * explicit instead of using null as control state.
 */
@JvmInline
value class ReificationContext(val maxDepth: Int) {
    init { require(maxDepth >= 0) { "maxDepth must be non-negative" } }

    val isExhausted: Boolean get() = maxDepth == 0
    fun deeper(): ReificationContext = if (isExhausted) this else ReificationContext(maxDepth - 1)

    companion object {
        fun from(topology: CacheTopology): ReificationContext {
            val l1 = topology.l1DataBytes ?: return ReificationContext(Int.MAX_VALUE)
            if (l1 < 4096) return ReificationContext(0)
            return ReificationContext(log2(l1.toDouble() / 4096.0).roundToInt().coerceIn(0, 16))
        }
    }
}

operator fun <A> Series<A>.plus(c: A): Series<A> = combine(this, 1 j { c })
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
fun <A> combine(vararg catn: Series<A>): Series<A> = catn.run { combine(size j ::get) }

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

                val idx = when (szN) {

                    in 2..4 -> stairs.indexOfFirst { it > i }
                    else -> stairs.firstIndexGreaterThan(i)
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
 * When [ctx] is exhausted, sub-series are materialized ([cow]) before being
 * combined.  This flattens the stair shape, trading memory for shallower
 * read-path resolution.
 *
 * @param catn  the series of sub-series to concatenate
 * @param ctx   reification depth cap
 */
fun <A> combine(catn: Series<Series<A>>, ctx: ReificationContext): Series<A> {
    val resolved: Series<Series<A>> = if (ctx.isExhausted) {
        // Depth 0: materialize every sub-series immediately
        catn.size j { i -> catn[i].materialize() as Series<A> }
    } else {
        // If the child budget is exhausted, keep already-materialized series
        // and materialize lazy sub-series before adding this outer stair.
        val deeper = ctx.deeper()
        catn.size j { i ->
            val sub = catn[i]
            if (deeper.isExhausted && sub !is CowSeriesHandle<*>) {
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
 * Delegates to [combine(Series<Series<A>>, ReificationContext)].
 */
fun <A> combine(ctx: ReificationContext, vararg catn: Series<A>): Series<A> =
    combine((catn).size j catn::get, ctx)

private fun IntArray.firstIndexGreaterThan(value: Int): Int {
    var low = 0
    var high = size - 1
    while (low < high) {
        val mid = (low + high) ushr 1
        if (this[mid] > value) high = mid else low = mid + 1
    }
    return low
}
