package borg.trikeshed.dag

import borg.trikeshed.graph.CausalGraphNodeIndex
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size

/**
 * Cut: unify causal graph nodes and rete facts.
 *
 * Bridges [BlackboardEvent.NodePlanning] into the existing causal substrate
 * ([CausalGraphNodeIndex] via [indexNodePlanning]) AND into the existing
 * rete fact vocabulary ([ReteFact.NodeFact]) at the same time.
 *
 * The causal node and the rete fact carry the same identity:
 *   causal: causalKey + (nodeId, boardId) on the CausalGraphNode
 *   rete:   factId = "node:<boardId>:<nodeId>"
 *
 * This is the smallest unification that proves the two surfaces can carry
 * the same event without either side being a wrapper around the other.
 *
 * Endgame chain position:
 *   BlackboardEvent.NodePlanning (BlackboardDagFabric)
 *     -> ReteCausalBridge.project (THIS CUT)
 *       -> CausalGraphNodeIndex.indexNodePlanning (real, HARD)
 *         -> Int position in the causal store
 *       -> Series<ReteFact> with one ReteFact.NodeFact (real, HARD)
 */
object ReteCausalBridge {

    /**
     * Result of unifying a NodePlanning event across both surfaces.
     *
     * @property position the position assigned by [CausalGraphNodeIndex.addOrGet]
     * @property facts the rete projection for the same event (one [ReteFact.NodeFact]
     *           for non-NodePlanning inputs, one for each causal node indexed by
     *           [indexNodePlanning])
     */
    data class Projection(
        val position: Int,
        val facts: Series<ReteFact>,
    )

    /**
     * Project a [BlackboardEvent.NodePlanning] into both the causal index and the
     * rete network. The [ReteFact.NodeFact] mirrors the [CausalGraphNode] by
     * `boardId:nodeId`, so a downstream consumer can join the two surfaces by
     * either `factId` or `causalKey`.
     */
    fun project(
        event: BlackboardEvent.NodePlanning,
        index: CausalGraphNodeIndex,
    ): Projection {
        val position = index.indexNodePlanning(event)
        val node = index[position]
        val fact = ReteFact.NodeFact(
            nodeId = node.nodeId,
            boardId = node.blackboard.id,
            opId = node.opId,
            opVersion = node.opVersion,
            causalKey = node.causalKey,
        )
        val facts: Series<ReteFact> = 1 j { i -> if (i == 0) fact else error("unified projection has one fact") }
        return Projection(position = position, facts = facts)
    }
}