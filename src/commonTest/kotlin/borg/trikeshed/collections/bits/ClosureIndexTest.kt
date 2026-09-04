package borg.trikeshed.collections.bits

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A small SUMO-shaped DAG: 0 Entity ⊃ {1 Physical, 2 Abstract}; Physical ⊃ {3 Object, 4 Process};
 * Object ⊃ 5 Agent; 6 Human ⊂ Agent AND ⊂ 7 Organism; Organism ⊂ Object; 8 Quantity ⊂ Abstract; 9 Number ⊂ Quantity.
 */
class ClosureIndexTest {
    private val parents = arrayOf(
        intArrayOf(), intArrayOf(0), intArrayOf(0), intArrayOf(1), intArrayOf(1),
        intArrayOf(3), intArrayOf(5, 7), intArrayOf(3), intArrayOf(2), intArrayOf(8),
    )
    private val idx = ClosureIndex.build(parents.size) { parents[it] }

    @Test
    fun isAIsTheTransitiveReflexiveClosure() {
        assertTrue(idx.isA(6, 0)); assertTrue(idx.isA(6, 3)); assertTrue(idx.isA(6, 7)); assertTrue(idx.isA(6, 5))
        assertTrue(idx.isA(6, 6), "reflexive")
        assertFalse(idx.isA(6, 2)); assertFalse(idx.isA(6, 4)); assertFalse(idx.isA(0, 6))
        assertEquals(setOf(0, 1, 3, 5, 7), idx.ancestorNodes(6).toSet())
        assertEquals(setOf(1, 2, 3, 4, 5, 6, 7, 8, 9), idx.descendantNodes(0).toSet())
        assertEquals(setOf(6), idx.descendantNodes(7).toSet())
    }

    @Test
    fun preorderNumberingMakesDescendantSetsRuns() {
        assertEquals(0, idx.id(0), "the root is id 0")
        val physical = idx.descendantIds(1)
        // Physical's subtree is numbered contiguously right after it, except Human is
        // reached first under Agent, so Organism's only descendant is not adjacent to it.
        assertEquals(idx.id(1) + 1, physical.first())
        assertTrue(physical.contains(idx.id(6)))
        val hist = idx.shapeHistogram()
        assertTrue(hist.getValue("run") >= 1, "at least one descendant set is a run: $hist")
        assertEquals(0, hist.getValue("bitmap"))
    }

    @Test
    fun cyclesAreCutNotLooped() {
        val cyc = ClosureIndex.build(3) { arrayOf(intArrayOf(2), intArrayOf(0), intArrayOf(1))[it] }
        assertTrue(cyc.isA(0, 1)); assertTrue(cyc.isA(1, 0))
        assertEquals(3, (0 until 3).map { cyc.id(it) }.toSet().size, "every node numbered once")
    }
}
