package borg.trikeshed.dag

import borg.trikeshed.collections.associative.LinearHashMap
import borg.trikeshed.graph.CausalGraphNode
import borg.trikeshed.graph.CausalGraphNodeIndex
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.size

data class Activation(
    val activationId: String,
    val ruleId: String,
    val ruleVersionCid: ContentId,
    val salience: Int,
    val sequence: Long,
    val supportCids: List<ContentId>,
    val bindings: Map<String, String>,
)

/**
 * Deterministic Rete conflict set. Ordering is salience descending, committed
 * sequence ascending, then activation ID ascending.
 *
 * When a [CausalLandmarkIndex] is attached via [landmarkIndex], ordering is
 * augmented with A* differential heuristics: activations causally closer to
 * a reconciled landmark fire first (lower f-value). When no landmarks are
 * registered, degrades to the original salience-only ordering.
 */
class ReteAgenda {
    private val pending = LinearHashMap<String, Activation>()

    /** Optional causal landmark index for differential-heuristic ordering. */
    var landmarkIndex: CausalLandmarkIndex? = null

    val size: Int get() = pending.count

    fun add(activation: Activation): Boolean {
        val existing = pending.get(activation.activationId)
        if (existing != null) {
            require(existing == activation) {
                "activation ID ${activation.activationId} already identifies different content"
            }
            return false
        }
        pending.set(activation.activationId, activation)
        return true
    }

    fun popNext(): Activation? {
        var selected: Activation? = null
        pending.entries().forEach { (_, candidate) ->
            val current = selected
            if (current == null || candidate.precedes(current)) selected = candidate
        }
        return selected?.also { pending.remove(it.activationId) }
    }

    fun removeBySupport(supportCid: ContentId): Int {
        val invalidated = pending.entries()
            .filter { (_, activation) -> supportCid in activation.supportCids }
            .map { it.first }
        invalidated.forEach(pending::remove)
        return invalidated.size
    }

    /**
     * A* ordering: f(n) = -salience + h(n) + sequence. Lower f = pop first.
     *
     * Without landmarks (or when h=0), h drops out and this is the original
     * salience descending, sequence ascending, activationId ascending order.
     */
    private fun Activation.precedes(other: Activation): Boolean {
        val lm = landmarkIndex
        if (lm == null || lm.landmarkCount == 0) {
            // Original static ordering.
            return when {
                salience != other.salience -> salience > other.salience
                sequence != other.sequence -> sequence < other.sequence
                else -> activationId < other.activationId
            }
        }
        // A* f-value ordering with differential heuristic.
        // h(n) approximated as distanceToNearestLandmark for each activation.
        // Since we don't have a direct node index for an activation, use
        // the support CIDs: the heuristic reward is the minimum distance
        // across the activation's support CIDs that resolve to graph nodes.
        val hThis = heuristicDistance(supportCids, lm)
        val hOther = heuristicDistance(other.supportCids, lm)
        val fThis = fValue(salience, sequence, hThis)
        val fOther = fValue(other.salience, other.sequence, hOther)
        return if (fThis != fOther) fThis < fOther
        else activationId < other.activationId
    }

    /** A* f-value: lower pops first. Inverts salience, adds heuristic + sequence. */
    private fun fValue(salience: Int, sequence: Long, h: Int): Long {
        val salienceComponent = (Int.MAX_VALUE.toLong() - salience.toLong())
        return salienceComponent + h.toLong() * 1000L + sequence
    }

    /** Minimum distance-to-landmark across support CIDs that resolve in the graph. */
    private fun heuristicDistance(supportCids: List<ContentId>, lm: CausalLandmarkIndex): Int {
        var min = Int.MAX_VALUE
        for (cid in supportCids) {
            // Resolve the support CID to a causal graph node index via
            // a linear scan of the landmark's backing index. The CID
            // encodes content identity; we match on the graph node's
            // causalKey prefix (ContentId hex).
            val graph = lm.backingGraph()
            for (i in 0 until graph.size) {
                val node = graph[i]
                // Match support CID to the node's outputHash or causalKey.
                val nodeHash = node.outputHash
                if (nodeHash != null && cid.hex.startsWith(nodeHash.take(16))) {
                    val d = lm.distanceToNearestLandmark(i)
                    if (d < min) min = d
                    break
                }
            }
        }
        return if (min == Int.MAX_VALUE) 0 else min
    }
}

/** Access the backing graph for heuristic lookups (internal to this file). */
internal fun CausalLandmarkIndex.backingGraph(): CausalGraphNodeIndex = this.backingGraph
