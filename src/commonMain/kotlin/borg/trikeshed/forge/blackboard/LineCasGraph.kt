package borg.trikeshed.forge.blackboard

import borg.trikeshed.cas.LineCasEdge
import borg.trikeshed.cas.LineCasNeighbor
import borg.trikeshed.cas.LineCasSpine
import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.graph.CausalGraphNodeIndex
import borg.trikeshed.graph.causalGraphNode

fun lineSpineToCausalIndex(
    spine: List<LineCasSpine>,
    edges: List<LineCasEdge>
): CausalGraphNodeIndex {
    val index = CausalGraphNodeIndex()
    val blackboard = BlackboardContext("line_cas_graph")

    for (node in spine) {
        val nodeId = node.linkedKey ?: "${node.contentCid.value}_${node.ordinal}"

        val parentNodeIds = edges
            .filter { it.targetCid == node.contentCid && (it.relationship == LineCasNeighbor.NEIGHBOR_PREV || it.confidence >= 0.9) }
            .map { edge ->
                val parentSpine = spine.find { it.contentCid == edge.sourceCid }
                parentSpine?.linkedKey ?: "${edge.sourceCid.value}_${parentSpine?.ordinal ?: 0}"
            }
            .distinct()

        val causalNode = causalGraphNode(
            nodeId = nodeId,
            opId = "line_cas",
            opVersion = "1.0",
            parentNodeIds = parentNodeIds,
            inputFingerprint = node.contentCid.value,
            blackboard = blackboard,
            causalClock = 0L,
            topoOrdinal = node.ordinal,
            outputHash = null
        )

        index.addOrGet(causalNode)
    }

    return index
}
