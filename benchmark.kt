Looking at this conflict, I need to merge both benchmark implementations into a single file. The "ours" side contains a benchmark for `QueryEngine.extractDoubleColumn()` with mock cursor data, while the "theirs" side contains graph-related classes and a benchmark for `GraphQuery.outE()`. Both are valid and should be preserved.

Here's the resolved file:

```kotlin
package borg.trikeshed.graph.query

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.graph.query.QueryEngine
import kotlin.system.measureTimeMillis

interface Graph<N, E> {
    val nodes: Set<N>
    fun outEdges(node: N): Map<N, E>
    fun inEdges(node: N): Map<N, E>
}
interface MutableGraph<N, E> : Graph<N, E> {
    fun addNode(node: N): Boolean
    fun removeNode(node: N): Boolean
    fun addEdge(from: N, to: N, edge: E)
    fun removeEdge(from: N, to: N): Boolean
}
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
    override fun removeNode(node: N): Boolean { return false }
    override fun addEdge(from: N, to: N, edge: E) {
        addNode(from)
        addNode(to)
        outAdj[from]!![to] = edge
        inAdj[to]!![from] = edge
    }
    override fun removeEdge(from: N, to: N): Boolean { return false }
}

class GraphQuery<N, E>(private val graph: Graph<N, E>, private val currentNodes: Set<N>) {
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
    fun toSet(): Set<N> = currentNodes
}

class GraphQueryOptimized<N, E>(private val graph: Graph<N, E>, private val currentNodes: Set<N>) {
    fun outE(predicate: (E) -> Boolean): GraphQueryOptimized<N, E> {
        val nextNodes = mutableSetOf<N>()
        for (node in currentNodes) {
            for ((to, edge) in graph.outEdges(node)) {
                if (predicate(edge)) {
                    nextNodes.add(to)
                }
            }
        }
        return GraphQueryOptimized(graph, nextNodes)
    }
    fun toSet(): Set<N> = currentNodes
}

fun main() {
    // Benchmark 1: QueryEngine.extractDoubleColumn
    val numRows = 1_000_000
    val meta1: () -> ColumnMeta = { ColumnMeta("a", IOMemento.IoInt) }
    val meta2: () -> ColumnMeta = { ColumnMeta("target", IOMemento.IoDouble) }
    val meta3: () -> ColumnMeta = { ColumnMeta("c", IOMemento.IoString) }

    // Create a mock cursor with ReifiedSplitSeries2 to reflect real-world usage where possible
    val metas = 3 j { idx -> when(idx) { 0 -> meta1; 1 -> meta2; else -> meta3 } }

    val cursor: Cursor = numRows j { rowIndex ->
        val values = 3 j { colIndex ->
            when (colIndex) {
                0 -> rowIndex
                1 -> rowIndex * 1.5
                else -> "string$rowIndex"
            }
        }
        RowVec(values, metas)
    }

    val engine = QueryEngine(cursor)
    
    val time1 = measureTimeMillis {
        val result = engine.extractDoubleColumn("target")
        println("Extracted ${result.size} values")
    }
    println("Benchmark 1 (extractDoubleColumn): $time1 ms")

    // Benchmark 2: GraphQuery.outE
    val graph = AdjacencyListGraph<Int, Double>()
    val numNodes = 10
}
```