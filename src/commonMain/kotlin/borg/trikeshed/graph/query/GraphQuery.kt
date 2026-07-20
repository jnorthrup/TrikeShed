package borg.trikeshed.graph.query

/**
 * A basic generic directed graph interface.
 */
interface Graph<N, E> {
    val nodes: Set<N>
    fun outEdges(node: N): Map<N, E>
    fun inEdges(node: N): Map<N, E>
}

/**
 * A generic mutable graph.
 */
interface MutableGraph<N, E> : Graph<N, E> {
    fun addNode(node: N): Boolean
    fun removeNode(node: N): Boolean
    fun addEdge(from: N, to: N, edge: E)
    fun removeEdge(from: N, to: N): Boolean
}

/**
 * Basic in-memory adjacency list graph implementation.
 */
class AdjacencyListGraph<N, E> : MutableGraph<N, E> {
    private val outAdj = mutableMapOf<N, MutableMap<N, E>>()
    private val inAdj = mutableMapOf<N, MutableMap<N, E>>()
    
    override val nodes: Set<N> get() = outAdj.keys

    override fun outEdges(node: N): Map<N, E> = outAdj[node] ?: emptyMap()
    
    override fun inEdges(node: N): Map<N, E> = inAdj[node] ?: emptyMap()

    override fun addNode(node: N): Boolean {
        if (outAdj.containsKey(node)) return false
        outAdj[node] = mutableMapOf()
        inAdj[node] = mutableMapOf()
        return true
    }

    override fun removeNode(node: N): Boolean {
        if (!outAdj.containsKey(node)) return false
        
        // Remove incoming edges to this node from other nodes
        inAdj[node]?.keys?.forEach { from ->
            outAdj[from]?.remove(node)
        }
        
        // Remove outgoing edges from this node to other nodes
        outAdj[node]?.keys?.forEach { to ->
            inAdj[to]?.remove(node)
        }
        
        outAdj.remove(node)
        inAdj.remove(node)
        return true
    }

    override fun addEdge(from: N, to: N, edge: E) {
        addNode(from)
        addNode(to)
        outAdj[from]!![to] = edge
        inAdj[to]!![from] = edge
    }

    override fun removeEdge(from: N, to: N): Boolean {
        val removedOut = outAdj[from]?.remove(to) != null
        val removedIn = inAdj[to]?.remove(from) != null
        return removedOut && removedIn
    }
}

/**
 * Query builder to traverse graphs.
 */
class GraphQuery<N, E>(private val graph: Graph<N, E>, private val currentNodes: Set<N>) {
    
    /** Filter current nodes by a predicate. */
    fun filter(predicate: (N) -> Boolean): GraphQuery<N, E> {
        return GraphQuery(graph, currentNodes.filter(predicate).toSet())
    }
    
    /** Traverse outbound edges from the current nodes. */
    fun out(): GraphQuery<N, E> {
        val nextNodes = mutableSetOf<N>()
        for (node in currentNodes) {
            nextNodes.addAll(graph.outEdges(node).keys)
        }
        return GraphQuery(graph, nextNodes)
    }

    /** Traverse inbound edges from the current nodes. */
    fun `in`(): GraphQuery<N, E> {
        val nextNodes = mutableSetOf<N>()
        for (node in currentNodes) {
            nextNodes.addAll(graph.inEdges(node).keys)
        }
        return GraphQuery(graph, nextNodes)
    }
    
    /** Traverse out filtering edges by a predicate. */
    fun outE(predicate: (E) -> Boolean): GraphQuery<N, E> {
        val nextNodes = mutableSetOf<N>()
        for (node in currentNodes) {
            graph.outEdges(node).forEach { (to, edge) ->
                if (predicate(edge)) {
                    nextNodes.add(to)
                }
            }
        }
        return GraphQuery(graph, nextNodes)
    }
    
    /** Return all nodes matching the query so far. */
    fun toList(): List<N> = currentNodes.toList()
    
    /** Return nodes as a Set. */
    fun toSet(): Set<N> = currentNodes
    
    /** Aggregate values over the current nodes. */
    fun <T> aggregate(initial: T, operation: (acc: T, N) -> T): T {
        var acc = initial
        for (node in currentNodes) {
            acc = operation(acc, node)
        }
        return acc
    }
}

/** Start a query on the whole graph or a subset of start nodes. */
fun <N, E> Graph<N, E>.query(startNodes: Set<N> = nodes): GraphQuery<N, E> = GraphQuery(this, startNodes)
fun <N, E> Graph<N, E>.query(vararg startNodes: N): GraphQuery<N, E> = GraphQuery(this, startNodes.toSet())
