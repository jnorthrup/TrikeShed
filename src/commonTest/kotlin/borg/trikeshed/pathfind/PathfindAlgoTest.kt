package borg.trikeshed.pathfind

import borg.trikeshed.graph.query.AdjacencyListGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PathfindAlgoTest {

    @Test
    fun testDijkstra() {
        val graph = AdjacencyListGraph<String, Double>()
        graph.addEdge("A", "B", 1.0)
        graph.addEdge("B", "C", 2.0)
        graph.addEdge("A", "C", 4.0)
        graph.addEdge("C", "D", 1.0)
        
        val result = graph.dijkstra("A", "D") { it }
        assertNotNull(result)
        assertEquals(4.0, result.cost)
        assertEquals(listOf("A", "B", "C", "D"), result.path)
    }
    
    @Test
    fun testDijkstraNoPath() {
        val graph = AdjacencyListGraph<String, Double>()
        graph.addEdge("A", "B", 1.0)
        graph.addNode("C")
        
        val result = graph.dijkstra("A", "C") { it }
        assertNull(result)
    }

    @Test
    fun testAStar() {
        val graph = AdjacencyListGraph<String, Double>()
        graph.addEdge("A", "B", 1.0)
        graph.addEdge("B", "C", 2.0)
        graph.addEdge("A", "C", 4.0)
        graph.addEdge("C", "D", 1.0)
        
        // Simple heuristic: 0 (behaves like Dijkstra)
        val result = graph.aStar("A", "D", { it }, { 0.0 })
        assertNotNull(result)
        assertEquals(4.0, result.cost)
        assertEquals(listOf("A", "B", "C", "D"), result.path)
    }
}
