package borg.trikeshed.lib

import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * Reification budget for lazy stair-shaped series composition.
 *
 * [maxDepth] is the highest local staircase depth a context-aware combine may
 * return.  When an entrant would push the merge beyond that bound, the entrant
 * is copied into local ArrayList backing and re-enters the merge as depth 0.
 */

inline class ReificationContext(val maxDepth: Int) {
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

/**
 * Local shape evidence for context-aware combine.
 *
 * Depth 0 is a local leaf.  Depth 1 is a staircase over leaves.  Higher values
 * are staircases over staircases.  Plain [Series] stays unmarked so the
 * no-context combine path remains a lazy skirmish view.
 */
interface StaircaseSeries<T> : Series<T> {
    val staircaseDepth: Int
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
 * Combine with a [ReificationContext] that caps local stair-nesting depth.
 *
 * Plain [Series] entrants are unknown shape at this boundary, so they are
 * copied into ArrayList-backed depth-0 leaves.  Marked entrants may remain lazy
 * while their depth fits under the next staircase.  Entrants that would exceed
 * [ctx] are copied back to depth 0 before merging.
 *
 * @param catn  the series of sub-series to concatenate
 * @param ctx   reification depth cap
 */
fun <A> combine(catn: Series<Series<A>>, ctx: ReificationContext): Series<A> {
    return when (val szN = catn.size) {
        0 -> ArrayListSeries(ArrayList())
        1 -> catn[0].boundedEntrant(ctx.maxDepth)
        else -> {
            if (ctx.isExhausted) return catn.flattenToArrayListSeries()

            val children = ArrayList<Series<A>>(szN)
            val childDepthLimit = ctx.maxDepth - 1
            var maxChildDepth = 0
            var i = 0
            while (i < szN) {
                val child = catn[i].boundedEntrant(childDepthLimit)
                children.add(child)
                maxChildDepth = maxOf(maxChildDepth, (child as? StaircaseSeries<*>)?.staircaseDepth ?: 0)
                i++
            }
            StaircaseCombinedSeries(children, maxChildDepth + 1)
        }
    }
}

/**
 * Varargs combine with reification context.
 * Callers must pass [ctx] explicitly to disambiguate from the no-context overload.
 * Delegates to [combine(Series<Series<A>>, ReificationContext)].
 */
fun <A> combine(ctx: ReificationContext, vararg catn: Series<A>): Series<A> =
    combine((catn).size j catn::get, ctx)

private class ArrayListSeries<A>(
    private val values: ArrayList<A>,
) : StaircaseSeries<A> {
    override val staircaseDepth: Int get() = 0
    override val a: Int get() = values.size
    override val b: (Int) -> A = { i -> values[i] }
}

private class StaircaseCombinedSeries<A>(
    private val children: ArrayList<Series<A>>,
    override val staircaseDepth: Int,
) : StaircaseSeries<A> {
    private val stairs: IntArray by lazy {
        var acc = 0
        IntArray(children.size) { i ->
            acc += children[i].size
            acc
        }
    }

    private val sumSize: Int by lazy { stairs[children.size - 1] }

    override val a: Int get() = sumSize
    override val b: (Int) -> A = { i ->
        val idx = when (children.size) {
            in 2..4 -> stairs.indexOfFirst { it > i }
            else -> stairs.firstIndexGreaterThan(i)
        }
        val offset = if (idx == 0) i else i - stairs[idx - 1]
        children[idx][offset]
    }
}

private fun <A> Series<A>.boundedEntrant(maxDepth: Int): Series<A> =
    if (this is StaircaseSeries<*> && staircaseDepth <= maxDepth) this else reifyToArrayListSeries()

private fun <A> Series<A>.reifyToArrayListSeries(): StaircaseSeries<A> {
    val values = ArrayList<A>(size)
    var i = 0
    while (i < size) {
        values.add(this[i])
        i++
    }
    return ArrayListSeries(values)
}

private fun <A> Series<Series<A>>.flattenToArrayListSeries(): StaircaseSeries<A> {
    val values = ArrayList<A>()
    var seriesIndex = 0
    while (seriesIndex < size) {
        val series = this[seriesIndex]
        var valueIndex = 0
        while (valueIndex < series.size) {
            values.add(series[valueIndex])
            valueIndex++
        }
        seriesIndex++
    }
    return ArrayListSeries(values)
}

private fun IntArray.firstIndexGreaterThan(value: Int): Int {
    var low = 0
    var high = size - 1
    while (low < high) {
        val mid = (low + high) ushr 1
        if (this[mid] > value) high = mid else low = mid + 1
    }
    return low
}
