<<<<<<< ours
Looking at the conflict in `benchmark.kt`, I need to resolve the merge between two different benchmark implementations. Let me analyze both sides:

**Ours (session 114727/568010):** A benchmark for `QueryEngine.extractDoubleColumn()` with mock cursor data
**Theirs (session 325167):** A benchmark for `GraphQuery.outE()` with graph operations

Both sides have valid code but serve different purposes. The imports at the top show dependencies from both sides. Since this is a cumulative conflict resolution, I need to combine both benchmarks into a single file that preserves both functionalities.

The file should contain both benchmark functions, properly structured with all necessary imports and the package declaration.

Here's the resolved file:

```kotlin
package borg.trikeshed.graph.query

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.graph.query.QueryEngine
import kotlin.system.measureTimeMillis

=======
package borg.trikeshed.graph.query

>>>>>>> theirs
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

<<<<<<< ours
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
        ReifiedSplitSeries2(values, metas)
    }

    val engine = QueryEngine(cursor)

    // Warmup
    engine.extractDoubleColumn("target")

    // Measure
    val time = measureTimeMillis {
        engine.extractDoubleColumn("target")
    }

    println("Time: $time ms")

    // Benchmark 2: GraphQuery.outE
=======

fun main() {
>>>>>>> theirs
    val graph = AdjacencyListGraph<Int, String>()
    // create a dense graph where many nodes point to the same destination
    val destNodes = (0..100).toList()
    val srcNodes = (1000..6000).toList()
    for (dest in destNodes) {
        graph.addNode(dest)
    }
    for (src in srcNodes) {
        graph.addNode(src)
        for (dest in destNodes) {
            graph.addEdge(src, dest, "edge_src_dest")
        }
    }
    
    val startNodes = srcNodes.toSet()
    
    // warmup
    for (i in 0..10) {
        val q1 = GraphQuery(graph, startNodes)
<<<<<<< ours
        q1.outE { it.contains("edge") }
    }
    
    // Measure original
    val timeOriginal = measureTimeMillis {
        val q1 = GraphQuery(graph, startNodes)
        q1.outE { it.contains("edge") }
    }
    println("Original GraphQuery time: $timeOriginal ms")
    
    // Measure optimized
    val timeOptimized = measureTimeMillis {
        val q1 = GraphQueryOptimized(graph, startNodes)
        q1.outE { it.contains("edge") }
    }
    println("Optimized GraphQuery time: $timeOptimized ms")
}
```

The resolution combines both benchmark implementations into a single `main()` function, preserving all the graph infrastructure classes from the "theirs" side and the cursor benchmark from the "ours" side. The package declaration is included as required by the "theirs" side's code structure.
=======
        q1.outE { it.contains("edge") }.toSet()
        val q2 = GraphQueryOptimized(graph, startNodes)
        q2.outE { it.contains("edge") }.toSet()
    }
    
    var time1 = 0L
    var time2 = 0L
    for (i in 0..50) {
        val q1 = GraphQuery(graph, startNodes)
        val start1 = System.nanoTime()
        q1.outE { it.contains("edge") }.toSet()
        time1 += (System.nanoTime() - start1)
        
        val q2 = GraphQueryOptimized(graph, startNodes)
        val start2 = System.nanoTime()
        q2.outE { it.contains("edge") }.toSet()
        time2 += (System.nanoTime() - start2)
    }
    
    println("Original outE time: " + (time1 / 1_000_000) + " ms")
    println("Optimized outE time: " + (time2 / 1_000_000) + " ms")
}
>>>>>>> theirs
