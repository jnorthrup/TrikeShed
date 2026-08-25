package borg.trikeshed.cursor

import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase-0 guard: prose-mined edges (TypeDefOracle.addLinkCheck n-grams) can admit
 * cycles; the lattice walks must terminate and the seed must never report itself
 * as its own transitive supertype.
 */
class IsALatticeCycleTest {

    private fun lattice(vararg edges: Pair<Int, Int>): IsALattice {
        val packed = edges.map { (sub, sup) -> IsAEdge(sub, sup) }
        return IsALattice(packed.size j { i: Int -> packed[i] })
    }

    @Test
    fun cycleThroughSeedTerminatesAndExcludesSeed() {
        // A → B → C → A
        val l = lattice(0 to 1, 1 to 2, 2 to 0)
        val supers = l.supertypes(TypeToken(0)).view.toList()
        assertEquals(listOf(TypeToken(1), TypeToken(2)), supers)
    }

    @Test
    fun selfLoopYieldsNoSupertypes() {
        val l = lattice(5 to 5)
        assertEquals(0, l.supertypes(TypeToken(5)).size)
    }

    @Test
    fun isAOnCycleTerminates() {
        val l = lattice(0 to 1, 1 to 0)
        assertTrue(l.isA(TypeToken(0), TypeToken(1)))
        assertTrue(l.isA(TypeToken(1), TypeToken(0)))
        assertFalse(l.isA(TypeToken(0), TypeToken(9)))
    }

    @Test
    fun maxDepthBoundsTheWalk() {
        // 0 → 1 → 2 → 3, chain of depth 3
        val l = lattice(0 to 1, 1 to 2, 2 to 3)
        assertEquals(listOf(TypeToken(1)), l.supertypes(TypeToken(0), maxDepth = 1).view.toList())
        assertEquals(3, l.supertypes(TypeToken(0)).size)
        assertEquals(0, l.supertypes(TypeToken(0), maxDepth = 0).size)
    }

    @Test
    fun bfsOrderIsShallowestFirst() {
        // diamond: 0 → {1, 2} → 3
        val l = lattice(0 to 1, 0 to 2, 1 to 3, 2 to 3)
        val supers = l.supertypes(TypeToken(0)).view.toList()
        assertEquals(setOf(TypeToken(1), TypeToken(2)), supers.take(2).toSet())
        assertEquals(TypeToken(3), supers[2])
    }

    @Test
    fun budgetCoordFloatViewRoundTrips() {
        val b = BudgetCoord(0.5f, 0.25f, 1.0f)
        assertEquals(0.5f, b.pf, 1e-4f)
        assertEquals(0.25f, b.df, 1e-4f)
        assertEquals(1.0f, b.qf, 1e-4f)
        assertEquals(BudgetCoord.full().packed, BudgetCoord(1f, 1f, 1f).packed)
        assertEquals(0L, BudgetCoord.zero().packed)
    }
}
