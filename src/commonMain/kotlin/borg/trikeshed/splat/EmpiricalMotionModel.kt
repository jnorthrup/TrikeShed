package borg.trikeshed.splat

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j

class EmpiricalMotionModel<Context, T> : SplatModel<Context, T> {
    private val counts = HashMap<Context, MutableMap<T, Int>>()
    private val totalRuns = HashMap<Context, Int>()
    private val laplaceSmoothing = 1e-3

    fun observe(context: Context, outcome: T) {
        val contextCounts = counts.getOrPut(context) { mutableMapOf() }
        contextCounts[outcome] = (contextCounts[outcome] ?: 0) + 1
        totalRuns[context] = (totalRuns[context] ?: 0) + 1
    }

    override fun predict(context: Context): Splat<T> {
        val outcomes = counts[context] ?: return emptySplat()
        val total = totalRuns[context] ?: 0

        val entries = arrayOfNulls<Any?>(outcomes.size)
        var entryIndex = 0
        for (outcome in outcomes.keys) entries[entryIndex++] = outcome
        entries.sortBy { it.toString() }
        val size = entries.size

        return size.j { i: Int ->
            @Suppress("UNCHECKED_CAST") val outcome = entries[i] as T
            val count = outcomes[outcome] ?: 0
            val prob = (count + laplaceSmoothing) / (total + laplaceSmoothing * size)
            outcome.j(prob)
        }
    }

    fun emptySplat(): Splat<T> = 0.j { _ -> error("empty") }
}
