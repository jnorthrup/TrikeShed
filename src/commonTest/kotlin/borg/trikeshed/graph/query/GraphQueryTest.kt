package borg.trikeshed.graph.query

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphQueryTest {

    @Test
    fun testAdjacencyListGraph() {
        val graph = AdjacencyListGraph<String, Int>()
        graph.addNode("A")
        graph.addNode("B")
        graph.addNode("C")

        graph.addEdge("A", "B", 1)
        graph.addEdge("B", "C", 2)
        graph.addEdge("A", "C", 3)

        assertEquals(3, graph.nodes.size)
        assertEquals(2, graph.outEdges("A").size)
        assertEquals(1, graph.inEdges("C").size)
    }

    @Test
    fun testGraphQuery() {
        val graph = AdjacencyListGraph<String, Int>()
        graph.addEdge("A", "B", 1)
        graph.addEdge("B", "C", 2)
        graph.addEdge("A", "C", 3)
        graph.addEdge("C", "D", 4)

        val query = graph.query("A")

        val nodesOut = query.out().toSet()
        assertEquals(setOf("B", "C"), nodesOut)

        val nodesOutFilter = query.outE { it < 2 }.toSet()
        assertEquals(setOf("B"), nodesOutFilter)

        val doubleOut = query.out().out().toSet()
        assertEquals(setOf("C", "D"), doubleOut)

        val sumEdges = query.out().aggregate(0) { acc, node ->
            acc + (graph.inEdges(node)["A"] ?: 0)
        }
        assertEquals(4, sumEdges)
    }
}
