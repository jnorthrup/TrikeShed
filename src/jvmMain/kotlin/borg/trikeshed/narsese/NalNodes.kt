package borg.trikeshed.narsese

import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.job.CasStore
import borg.trikeshed.jules.BrainClient
import borg.trikeshed.job.ContentId
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size

/**
 * NAL belief-bag LCNC nodes — thin wrappers over the already-built
 * ConstructionReadingLoop, AttentionEconomy.decay, and BeliefBagElement
 * read API.  These exist so the presets can wire belief-bag operations
 * as first-class LCNC nodes rather than calling the APIs directly.
 */
object NalNodes {

    /**
     * `nal.mint` — the bot proposes causal constructions; the reading loop
     * gate-checks them and lands accepted aggregates into the belief bag.
     * Wraps [ConstructionReadingLoop] with a bot seat backed by [brain].
     */
    fun mintRunner(
        brain: BrainClient,
        muxContext: kotlin.coroutines.CoroutineContext,
        cas: CasStore,
        bag: BeliefBagElement,
        rete: ReteNetwork,
        kifSink: (String) -> Unit = {},
    ): LcncNodeRunner = LcncNodeRunner { node, inputs ->
        // Delegate to ConstructionBotNode.runner — the only model-spend seam.
        val delegate = ConstructionBotNode.runner(brain, muxContext, cas, bag, rete, kifSink)
        delegate.run(node, inputs)
    }

    /**
     * `nal.decay` — a thin pulse that sends [BeliefIntake.DecayTick] into the
     * bag's intake channel.  The real formula is [AttentionEconomy.decay];
     * the node is just a timer trigger.
     */
    fun decayRunner(bag: BeliefBagElement): LcncNodeRunner = LcncNodeRunner { _, _ ->
        bag.intake.send(BeliefIntake.DecayTick)
        mapOf("decayed" to linkedMapOf(
            "size" to bag.size,
            "status" to "decay pulse sent",
        ))
    }

    /**
     * `nal.recall` — expose [BeliefBagElement]'s read methods as an LCNC node.
     * Mode param selects: `top` (priority-sorted), `sample` (stochastic),
     * `near` (angular neighborhood — requires `centroid` and `maxDistance` params).
     */
    fun recallRunner(bag: BeliefBagElement): LcncNodeRunner = LcncNodeRunner { node, _ ->
        val mode = node.params["mode"] ?: "top"
        val k = node.params["k"]?.toIntOrNull() ?: 16
        val beliefs = when (mode) {
            "sample" -> {
                val sampled = bag.recallSample(k)
                (0 until sampled.size).map { i ->
                    val s = sampled[i].a
                    val b = sampled[i].b
                    mapOf(
                        "angular" to s.angular,
                        "subject" to s.subjectCid,
                        "evidence" to mapOf("wPlus" to s.evidence.positive, "wMinus" to s.evidence.negative),
                        "priority" to b.pf,
                        "durability" to b.df,
                        "quality" to b.qf,
                    )
                }
            }
            "near" -> {
                val centroid = node.params["centroid"]?.toLongOrNull() ?: 0L
                val maxDistance = node.params["maxDistance"]?.toIntOrNull() ?: 4
                val near = bag.recallNear(centroid, maxDistance)
                (0 until near.size).map { i ->
                    val s = near[i]
                    mapOf(
                        "angular" to s.angular,
                        "subject" to s.subjectCid,
                        "evidence" to mapOf("wPlus" to s.evidence.positive, "wMinus" to s.evidence.negative),
                    )
                }
            }
            else -> { // "top"
                val top = bag.recallTop(k)
                (0 until top.size).map { i ->
                    val s = top[i].a
                    val b = top[i].b
                    mapOf(
                        "angular" to s.angular,
                        "subject" to s.subjectCid,
                        "evidence" to mapOf("wPlus" to s.evidence.positive, "wMinus" to s.evidence.negative),
                        "priority" to b.pf,
                        "durability" to b.df,
                        "quality" to b.qf,
                    )
                }
            }
        }
        mapOf("beliefs" to beliefs)
    }

    /**
     * `skill.decay` — per-skill budget computation via [AttentionEconomy.budgetOf].
     * Reads skill usage data and applies decay, exposing the resulting budgets.
     * Thin wrapper: the real formula is AttentionEconomy.budgetOf + decay.
     */
    fun skillDecayRunner(bag: BeliefBagElement): LcncNodeRunner = LcncNodeRunner { _, _ ->
        // Skill budget decay is the same AttentionEconomy.decay applied to
        // the bag.  This runner pulses the bag and returns the current
        // budget state for display/monitoring.
        bag.intake.send(BeliefIntake.DecayTick)
        // Sample current budgets from the bag for display
        val top = bag.recallTop(32)
        val budgets = (0 until top.size).map { i ->
            val b = top[i].b
            mapOf(
                "priority" to b.pf,
                "durability" to b.df,
                "quality" to b.qf,
            )
        }
        mapOf("budgets" to budgets)
    }
}
