package borg.trikeshed.graph.query

class TestGraphQuery<N, E>(private val graph: Graph<N, E>, private val currentNodes: Set<N>) {
    fun outE(predicate: (E) -> Boolean): GraphQuery<N, E> {
        val nextNodes = mutableSetOf<N>()
        for (node in currentNodes) {
            // trying user suggestion verbatim
            // val edges = graph.edgeIndex[label]?.get(node) ?: emptyList()
        }
        return GraphQuery(graph, nextNodes)
    }
}
