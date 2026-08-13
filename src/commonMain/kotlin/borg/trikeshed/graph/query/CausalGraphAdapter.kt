package borg.trikeshed.graph.query

import borg.trikeshed.graph.CausalGraphNode
import borg.trikeshed.graph.CausalGraphNodeIndex
import borg.trikeshed.lib.α
import borg.trikeshed.lib.view
import borg.trikeshed.lib.j

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
        val map = HashMap<String, MutableList<CausalGraphNode>>(index.size)
        for (i in 0 until index.size) {
            val node = index[i]
            for (parentId in node.parentNodeIds) {
                var list = map[parentId]
                if (list == null) {
                    list = ArrayList()
                    map[parentId] = list
                }
                list.add(node)
            }
        }
        childrenMap = map
    }

    override val nodes: Set<CausalGraphNode>
        get() = (index.size j { i: Int -> index[i] }).view.toSet() // stdlib-boundary: Set required by CausalGraphAdapter

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
