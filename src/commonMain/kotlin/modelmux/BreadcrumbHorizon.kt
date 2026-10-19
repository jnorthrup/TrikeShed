package modelmux

/**
 * The context cleaner of onegood1 (`breadcrumbs.py` ErrorRateTracker + HorizonManager, `token_allocator.py`):
 * a rolling window of turn overflows sets a band, and the band scales breadcrumb gains back or up.
 */
enum class BreadcrumbBand(val wire: String) { SCALE_UP("scale_up"), HOLD("hold"), SCALE_BACK("scale_back") }

/** Turn outcomes, oldest first once the ring wraps; an overflow is an estimated prompt above [BUDGET_TOKENS]. */
class BreadcrumbOverflow(val window: Int = WINDOW) {
    val outcomes = BooleanArray(window)
    var count = 0
    var next = 0

    init { require(window > 0) { "overflow window must be positive" } }

    fun record(overflow: Boolean) {
        outcomes[next] = overflow
        next = (next + 1) % window
        if (count < window) count++
    }

    val rate: Double get() {
        if (count == 0) return 0.0
        var hits = 0
        for (i in 0 until count) if (outcomes[i]) hits++
        return hits.toDouble() / count
    }

    val band: BreadcrumbBand get() = rate.let {
        if (it > HIGH) BreadcrumbBand.SCALE_BACK else if (it < LOW) BreadcrumbBand.SCALE_UP else BreadcrumbBand.HOLD
    }

    fun restore(from: BreadcrumbOverflow) {
        require(window == from.window) { "overflow window differs from checkpoint" }
        from.outcomes.copyInto(outcomes); count = from.count; next = from.next
    }

    /** Chronological '0'/'1' outcomes. */
    fun encode(): String = buildString {
        val start = if (count < window) 0 else next
        for (i in 0 until count) append(if (outcomes[(start + i) % window]) '1' else '0')
    }

    companion object {
        const val WINDOW = 50
        const val LOW = 0.02
        const val HIGH = 0.05
        /** onegood1 Agent.TOKEN_BUDGET, estimated at four characters per token. */
        const val BUDGET_TOKENS = 100_000L
        const val CHARS_PER_TOKEN = 4

        fun decode(outcomes: String, window: Int = WINDOW): BreadcrumbOverflow = BreadcrumbOverflow(window).also {
            require(outcomes.length <= window && outcomes.all { c -> c == '0' || c == '1' }) { "invalid breadcrumb overflow outcomes" }
            for (c in outcomes) it.record(c == '1')
        }
    }
}

/** HorizonManager's constants: scale-back drops crumbs past [DROP_AGE_MS] under [DROP_PRESENCE]. */
object BreadcrumbHorizon {
    const val DROP_AGE_MS = 60_000L
    const val DROP_PRESENCE = 0.3
    const val BACK = 0.9
    const val UP = 1.05
    /** Scale-up recalls dropped crumbs among the most recent [RECALL]. */
    const val RECALL = 50
}

/** TokenAllocator: [budget] split by weight, evenly when every weight is zero; largest remainders sum it exactly. */
fun breadcrumbAllocation(budget: Int, weights: DoubleArray): IntArray {
    val n = weights.size
    if (n == 0) return IntArray(0)
    require(budget >= 0) { "allocation budget cannot be negative" }
    val w = DoubleArray(n) { maxOf(0.0, weights[it]) }
    var total = w.sum()
    if (total == 0.0) { w.fill(1.0); total = n.toDouble() }
    val exact = DoubleArray(n) { w[it] / total * budget }
    val shares = IntArray(n) { exact[it].toInt() }
    val order = (0 until n).sortedByDescending { exact[it] - shares[it] }
    for (j in 0 until budget - shares.sum()) shares[order[j]]++
    return shares
}
