package borg.trikeshed.dag.algo

import borg.trikeshed.graph.query.Graph

/**
 * Breadth-First Search traversal of the graph starting from `startNodes`.
 * @return sequence of nodes in BFS order.
 */
fun <N, E> Graph<N, E>.bfs(vararg startNodes: N): Sequence<N> = sequence {
    val visited = mutableSetOf<N>()
    val queue = ArrayDeque<N>()
    
    for (start in startNodes) {
        if (visited.add(start)) {
            queue.addLast(start)
        }
    }
    
    while (queue.isNotEmpty()) {
        val node = queue.removeFirst()
        yield(node)
        
        for (neighbor in outEdges(node).keys) {
            if (visited.add(neighbor)) {
                queue.addLast(neighbor)
            }
        }
    }
}

/**
 * Depth-First Search traversal of the graph starting from `startNodes`.
 * @return sequence of nodes in DFS order.
 */
fun <N, E> Graph<N, E>.dfs(vararg startNodes: N): Sequence<N> = sequence {
    val visited = mutableSetOf<N>()
    
    suspend fun SequenceScope<N>.dfsVisit(node: N) {
        if (visited.add(node)) {
            yield(node)
            for (neighbor in this@dfs.outEdges(node).keys) {
                dfsVisit(neighbor)
            }
        }
    }
    
    for (start in startNodes) {
        dfsVisit(start)
    }
}

/**
 * Computes the transitive closure of a given node (all nodes reachable from it).
 * @return set of all reachable nodes.
 */
fun <N, E> Graph<N, E>.transitiveClosure(start: N): Set<N> {
    val reachable = mutableSetOf<N>()
    reachable.add(start)
    val queue = ArrayDeque<N>()
    
    queue.addLast(start)
    
    while (queue.isNotEmpty()) {
        val node = queue.removeFirst()
        
        for (neighbor in outEdges(node).keys) {
            if (reachable.add(neighbor)) {
                queue.addLast(neighbor)
            }
        }
    }
    
    return reachable
}
