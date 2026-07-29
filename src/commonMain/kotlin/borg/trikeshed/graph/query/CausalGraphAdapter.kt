package borg.trikeshed.graph.query

import borg.trikeshed.graph.CausalGraphNode
import borg.trikeshed.graph.CausalGraphNodeIndex

/**
 * Connects the Graph Query Engine to the CausalGraphNodeIndex.
 * CausalGraphNodeIndex models edges via CausalGraphNode.parentNodeIds (child -> parent).
 *
 * In this adapter:
 * - outEdges(node) models the flow of time (parent -> child).
 * - inEdges(node) models the dependencies (child -> parent).
 */
class CausalGraphAdapter(private val index: CausalGraphNodeIndex) : Graph<CausalGraphNode, Unit> {

    // We eagerly construct adjacency based on the index to provide O(1) lookups for edges
    // and avoid blocking on first access.
    private val childrenMap: Map<String, List<CausalGraphNode>>

    init {
        val map = mutableMapOf<String, MutableList<CausalGraphNode>>()
        for (i in 0 until index.size) {
            val node = index[i]
            for (parentId in node.parentNodeIds) {
                map.getOrPut(parentId) { mutableListOf() }.add(node)
            }
        }
        childrenMap = map
    }

    override val nodes: Set<CausalGraphNode>
        get() = (0 until index.size).map { index[it] }.toSet()

    override fun outEdges(node: N): Map<CausalGraphNode, Unit> {
        val children = childrenMap[node.nodeId] ?: emptyList()
        return children.associateWith { Unit }
    }

    override fun inEdges(node: N): Map<CausalGraphNode, Unit> {
        val parents = mutableMapOf<CausalGraphNode, Unit>()
        for (parentId in node.parentNodeIds) {
            val parentIdx = index.byNodeId(parentId)
            if (parentIdx != null) {
                parents[index[parentIdx]] = Unit
            }
        }
        return parents
    }
}

private typealias N = CausalGraphNode
