package borg.trikeshed.dag.algo

import borg.trikeshed.graph.query.AdjacencyListGraph
import kotlin.test.Test
import kotlin.test.assertEquals

class TraversalsTest {

    @Test
    fun testBfs() {
        val graph = AdjacencyListGraph<String, Unit>()
        graph.addEdge("A", "B", Unit)
        graph.addEdge("A", "C", Unit)
        graph.addEdge("B", "D", Unit)
        graph.addEdge("C", "E", Unit)

        val bfsOrder = graph.bfs("A").toList()
        assertEquals(5, bfsOrder.size)
        assertEquals("A", bfsOrder[0])
        // B and C order is undefined but should be at indices 1 and 2
        assertEquals(setOf("B", "C"), setOf(bfsOrder[1], bfsOrder[2]))
        assertEquals(setOf("D", "E"), setOf(bfsOrder[3], bfsOrder[4]))
    }

    @Test
    fun testDfs() {
        val graph = AdjacencyListGraph<String, Unit>()
        graph.addEdge("A", "B", Unit)
        graph.addEdge("A", "C", Unit)
        graph.addEdge("B", "D", Unit)

        val dfsOrder = graph.dfs("A").toList()
        assertEquals(4, dfsOrder.size)
        assertEquals("A", dfsOrder[0])
        // B or C could be next
    }
    
    @Test
    fun testTransitiveClosure() {
        val graph = AdjacencyListGraph<String, Unit>()
        graph.addEdge("A", "B", Unit)
        graph.addEdge("B", "C", Unit)
        graph.addEdge("D", "E", Unit)
        
        val closure = graph.transitiveClosure("A")
        assertEquals(setOf("A", "B", "C"), closure)
    }
}
