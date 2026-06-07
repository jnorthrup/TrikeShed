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

        val entries = outcomes.keys.toList()
        val size = entries.size

        return size.j { i ->
            val outcome = entries[i]
            val count = outcomes[outcome] ?: 0
            val prob = (count + laplaceSmoothing) / (total + laplaceSmoothing * size)
            outcome.j(prob)
        }
    }

    fun emptySplat(): Splat<T> = 0.j { _ -> error("empty") }

    fun asStateAdapter(): SplatModel<borg.trikeshed.context.ElementState, borg.trikeshed.context.ElementState> {
        val self = this
        return object : SplatModel<borg.trikeshed.context.ElementState, borg.trikeshed.context.ElementState> {
            override fun predict(context: borg.trikeshed.context.ElementState): Splat<borg.trikeshed.context.ElementState> {
                // Return an empty splat since the types don't necessarily match the generic context
                // This is a bridge for the ChannelRunner to supply a model
                return 0.j { _ -> error("empty") }
            }
        }
    }
}
