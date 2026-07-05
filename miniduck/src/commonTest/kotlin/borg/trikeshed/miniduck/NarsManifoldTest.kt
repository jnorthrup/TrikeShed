package borg.trikeshed.miniduck

import borg.trikeshed.lib.*
import kotlin.test.*

class NarsManifoldTest {

    // --- BudgetCoord ---

    @Test
    fun budgetCoordRoundTrip() {
        val coord = BudgetCoord(p = 0.75f, d = 0.5f, q = 0.25f)
        val packed = coord.pack()
        val unpacked = BudgetCoord.unpack(packed)
        assertEquals(0.75, unpacked.p.toDouble(), 0.01)
        assertEquals(0.5, unpacked.d.toDouble(), 0.01)
        assertEquals(0.25, unpacked.q.toDouble(), 0.01)
    }

    @Test
    fun budgetCoordClampsToUnit() {
        val coord = BudgetCoord(p = 1.5f, d = -0.1f, q = 0.0f)
        val packed = coord.pack()
        val unpacked = BudgetCoord.unpack(packed)
        assertTrue(unpacked.p <= 1.0f)
        assertTrue(unpacked.d >= 0.0f)
        assertEquals(0.0, unpacked.q.toDouble(), 0.001)
    }

    @Test
    fun budgetCoordEnergy() {
        val coord = BudgetCoord(p = 0.5f, d = 0.5f, q = 0.5f)
        // energy = geometric mean of PDQ
        val e = coord.energy()
        assertEquals(0.5, e.toDouble(), 0.01)
    }

    // --- ManifoldConcept ---

    @Test
    fun conceptCreationWithAngularAndRadial() {
        val payload = DocRowVec(listOf("key"), listOf("val"))
        val concept = ManifoldConcept(
            angular = 0x5555555555555555L,
            budget = BudgetCoord(p = 0.8f, d = 0.6f, q = 0.4f),
            payload = payload,
        )
        assertEquals(0x5555555555555555L, concept.angular)
        assertEquals(0.8, concept.budget.p.toDouble(), 0.01)
        assertSame(payload, concept.payload)
    }

    @Test
    fun conceptDecayReducesRadialEnergy() {
        val concept = ManifoldConcept(
            angular = 0x0F0F0F0F0F0F0F0FL,
            budget = BudgetCoord(p = 0.9f, d = 0.8f, q = 0.7f),
        )
        val decayed = concept.decay(0.5f)
        // same angular identity
        assertEquals(concept.angular, decayed.angular)
        // radial shrunk
        assertTrue(decayed.budget.p < concept.budget.p)
        assertEquals(0.45, decayed.budget.p.toDouble(), 0.01)
    }

    @Test
    fun conceptReinforceBoostsRadialEnergy() {
        val concept = ManifoldConcept(
            angular = 0x0F0F0F0F0F0F0F0FL,
            budget = BudgetCoord(p = 0.5f, d = 0.4f, q = 0.3f),
        )
        val reinforced = concept.reinforce(0.3f)
        assertEquals(concept.angular, reinforced.angular)
        assertTrue(reinforced.budget.p > concept.budget.p)
        // clamped at 1.0
        val maxed = concept.reinforce(10.0f)
        assertEquals(1.0, maxed.budget.p.toDouble(), 0.01)
    }

    // --- Hamming distance ---

    @Test
    fun hammingDistanceBetweenAngularCoords() {
        val a = 0x0F0F0F0F0F0F0F0FL
        val b = a.inv() // every bit flipped
        val dist = hamming(a, b)
        // every bit differs -> 64 bits
        assertEquals(64, dist)
    }

    @Test
    fun hammingDistanceSelfIsZero() {
        assertEquals(0, hamming(0L, 0L))
        assertEquals(0, hamming(0x0F0F0F0F0F0F0F0FL, 0x0F0F0F0F0F0F0F0FL))
    }

    @Test
    fun hammingDistanceSymmetric() {
        val a = 0x123456789ABCDEFL
        val b = 0x0FEDCBA987654321L
        assertEquals(hamming(a, b), hamming(b, a))
    }

    // --- NarsBag: append, seal, recall ---

    @Test
    fun bagAppendAndRecall() {
        val bag = NarsBag.mutable()
        val c1 = ManifoldConcept(angular = 100L, budget = BudgetCoord(p = 0.9f, d = 0.8f, q = 0.7f))
        val c2 = ManifoldConcept(angular = 200L, budget = BudgetCoord(p = 0.5f, d = 0.4f, q = 0.3f))
        bag.insert(c1)
        bag.insert(c2)

        val concepts = bag.recall()
        assertEquals(2, concepts.size)
        assertEquals(100L, (concepts[0] as ManifoldConcept).angular)
        assertEquals(200L, (concepts[1] as ManifoldConcept).angular)
    }

    @Test
    fun bagSealPreventsMutation() {
        val bag = NarsBag.mutable()
        bag.insert(ManifoldConcept(angular = 1L, budget = BudgetCoord(p = 0.5f, d = 0.5f, q = 0.5f)))
        val sealed = bag.seal()
        // sealed bag should be immutable
        assertSame(bag, sealed)
        assertEquals(NarsBag.State.SEALED, bag.state)
    }

    @Test
    fun bagBudgetTensor() {
        val bag = NarsBag.mutable()
        bag.insert(ManifoldConcept(angular = 10L, budget = BudgetCoord(p = 0.5f, d = 0.6f, q = 0.7f)))
        bag.insert(ManifoldConcept(angular = 20L, budget = BudgetCoord(p = 0.3f, d = 0.2f, q = 0.1f)))

        val tensor = bag.budgetTensor()
        // shape: (2 concepts, 3 budget dims)
        assertEquals(2, tensor.a[0])
        assertEquals(3, tensor.a[1])
        // concept 0
        assertEquals(0.5, tensor.b(shapeOf(0, 0)).toDouble(), 0.001)
        assertEquals(0.6, tensor.b(shapeOf(0, 1)).toDouble(), 0.001)
        assertEquals(0.7, tensor.b(shapeOf(0, 2)).toDouble(), 0.001)
        // concept 1
        assertEquals(0.3, tensor.b(shapeOf(1, 0)).toDouble(), 0.001)
        assertEquals(0.2, tensor.b(shapeOf(1, 1)).toDouble(), 0.001)
        assertEquals(0.1, tensor.b(shapeOf(1, 2)).toDouble(), 0.001)
    }

    // --- Total recall across timeline ---

    @Test
    fun totalRecallAcrossTimeline() {
        val t0 = NarsBag.mutable()
        t0.insert(ManifoldConcept(angular = 1L, budget = BudgetCoord(p = 0.9f, d = 0.9f, q = 0.9f)))
        t0.seal()

        val t1 = NarsBag.mutable()
        t1.insert(ManifoldConcept(angular = 2L, budget = BudgetCoord(p = 0.5f, d = 0.5f, q = 0.5f)))
        t1.seal()

        val timeline = 2 j { i: Int -> if (i == 0) t0 else t1 }
        val all = timeline.totalRecall()

        // both bags' concepts visible
        assertEquals(2, all.first)
        // radial-descending order: highest energy first
        val c0 = all[0] as ManifoldConcept
        val c1 = all[1] as ManifoldConcept
        assertTrue(c0.budget.energy() >= c1.budget.energy())
    }

    // --- Angular neighborhood query ---

    @Test
    fun angularNeighborhood() {
        val centroid = 0x0F0F0F0F0F0F0F0FL
        val bag = NarsBag.mutable()
        // close to centroid (1 bit different)
        bag.insert(ManifoldConcept(
            angular = 0x0F0F0F0F0F0F0F0EL,
            budget = BudgetCoord(p = 0.5f, d = 0.5f, q = 0.5f),
        ))
        // far from centroid (many bits different)
        bag.insert(ManifoldConcept(
            angular = centroid.inv(),
            budget = BudgetCoord(p = 0.9f, d = 0.9f, q = 0.9f),
        ))
        bag.seal()

        val neighbors = bag.recallNear(centroid, maxDistance = 5)
        assertEquals(1, neighbors.first)
        assertEquals(0x0F0F0F0F0F0F0F0EL, (neighbors[0] as ManifoldConcept).angular)
    }
}
