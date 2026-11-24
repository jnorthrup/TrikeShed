package narchy.spacegraph.layout

// Upstream test/layout/ergonomics.test.ts at 125b369: all 7 cases ported, expected values copied from upstream.

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ErgonomicsTest {
    private fun chain(count: Int): Pair<List<ErgonomicsNode>, List<ErgonomicsEdge>> {
        val nodes = (0 until count).map { ErgonomicsNode("n$it", 180.0, 72.0) }
        val edges = (0 until count - 1).map { ErgonomicsEdge("n$it", "n${it + 1}") }
        return nodes to edges
    }

    private val wide = ErgonomicsViewport(1600.0, 900.0)
    private val narrow = ErgonomicsViewport(900.0, 1000.0)

    // 'chooses columns from the surface aspect, not from a host preset'
    @Test
    fun choosesColumnsFromTheSurfaceAspect() {
        assertEquals(4, chooseColumns(12, wide))
        assertEquals(3, chooseColumns(12, narrow))
        assertEquals(2, chooseColumns(12, wide, 2))
        assertEquals(1, chooseColumns(1, wide))
    }

    // 'starts from the busiest node and keeps every id without host semantics'
    @Test
    fun startsFromTheBusiestNodeAndKeepsEveryId() {
        val (nodes, edges) = chain(6)
        val order = orderByProximity(nodes, edges)
        assertEquals(nodes.map { it.id }.toSet(), order.toSet())
        assertTrue(order[0] in listOf("n1", "n2", "n3", "n4"))
    }

    // 'puts the busiest node first'
    @Test
    fun putsTheBusiestNodeFirst() {
        val nodes = listOf("a", "b", "c", "hub").map { ErgonomicsNode(it) }
        val edges = listOf("a", "b", "c").map { ErgonomicsEdge("hub", it) }
        assertEquals("hub", orderByProximity(nodes, edges)[0])
    }

    // 'keeps swap refinement deterministic and bounded'
    @Test
    fun keepsSwapRefinementDeterministicAndBounded() {
        val (nodes, edges) = chain(8)
        val order = orderByProximity(nodes, edges)
        assertEquals(refineBySwaps(order, edges, 4, 2), refineBySwaps(order, edges, 4, 2))
        assertEquals(order, refineBySwaps(order, edges, 4, 0))
    }

    // 'fills the surface without overlap and respects the legibility floor'
    @Test
    fun fillsTheSurfaceWithoutOverlapAndRespectsTheLegibilityFloor() {
        val (nodes, edges) = chain(12)
        val layout = solveErgonomicLayout(nodes, edges, wide)!!
        assertEquals(12, layout.positions.size)
        assertEquals(0, layout.metrics.overlaps)
        assertTrue(layout.metrics.labelFloorMet)
        assertTrue(layout.metrics.occupancy > 0.3)
        assertTrue(layout.metrics.occupancy <= 1.0)
        assertTrue(layout.card.first >= 180.0)
        assertTrue(layout.card.second >= 72.0)
    }

    // 'adapts the grid to a narrow surface'
    @Test
    fun adaptsTheGridToANarrowSurface() {
        val (nodes, edges) = chain(12)
        val broad = solveErgonomicLayout(nodes, edges, wide)!!
        val tall = solveErgonomicLayout(nodes, edges, narrow)!!
        assertTrue(tall.columns < broad.columns)
        assertTrue(tall.card.first >= 180.0)
        assertEquals(0, tall.metrics.overlaps)
    }

    // 'is deterministic and returns nothing for an empty graph'
    @Test
    fun isDeterministicAndReturnsNothingForAnEmptyGraph() {
        val (nodes, edges) = chain(9)
        val a = solveErgonomicLayout(nodes, edges, wide)!!
        val b = solveErgonomicLayout(nodes, edges, wide)!!
        assertEquals(a.positions.toList(), b.positions.toList())
        assertNull(solveErgonomicLayout(emptyList(), emptyList(), wide))
        assertNull(solveErgonomicLayout(nodes, edges, ErgonomicsViewport(0.0, 0.0)))
    }
}
