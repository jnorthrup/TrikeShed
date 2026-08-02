package borg.trikeshed.manifold

import kotlin.test.Test
import kotlin.test.assertEquals

class ManifoldConceptTest {

    @Test
    fun testBudgetCoordEnergy() {
        // Full budget energy should be 1.0 (with floating point error margin)
        val full = BudgetCoord.full()
        assertEquals(1.0f, full.energy(), 0.0001f)

        // Half budget
        val half = BudgetCoord(0.5f, 0.5f, 0.5f)
        assertEquals(0.5f, half.energy(), 0.0001f)

        // Zero cases
        val zeroP = BudgetCoord(0.0f, 1.0f, 1.0f)
        assertEquals(0.0f, zeroP.energy())

        val zeroD = BudgetCoord(1.0f, 0.0f, 1.0f)
        assertEquals(0.0f, zeroD.energy())

        val zeroQ = BudgetCoord(1.0f, 1.0f, 0.0f)
        assertEquals(0.0f, zeroQ.energy())

        // Geometric mean
        val mixed = BudgetCoord(0.125f, 0.125f, 0.125f) // 1/8, pow(1/3) should be 1/2
        assertEquals(0.125f, mixed.energy(), 0.0001f)

        // Another geometric mean case
        // Let's use (1/8 * 1/8 * 1) = 1/64, cube root is 1/4 = 0.25
        val mixed2 = BudgetCoord(0.125f, 0.125f, 1.0f)
        assertEquals(0.25f, mixed2.energy(), 0.0001f)
    }
}
