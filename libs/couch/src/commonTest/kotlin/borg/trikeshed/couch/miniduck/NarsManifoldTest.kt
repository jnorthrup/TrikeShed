package borg.trikeshed.couch.miniduck

import borg.trikeshed.lib.*
import kotlin.test.*

class NarsManifoldTest {

    // --- BudgetCoord: compressed angular-radial coordinate ---

    @Test
    fun budgetCoordPacksThreeFloatsIntoUInt() {
        val coord = BudgetCoord(p = 0.75f, d = 0.5f, q = 0.25f)
        val packed = coord.packed
        val unpacked = BudgetCoord.unpack(packed)
        assertEquals(0.75, unpacked.p.toDouble(), 0.001)
        assertEquals(0.5, unpacked.d.toDouble(), 0.001)
        assertEquals(0.25, unpacked.q.toDouble(), 0.001)
    }

    @Test
    fun budgetCoordBoundaryValues() {
        val zero = BudgetCoord(p = 0f, d = 0f, q = 0f)
        val unpacked0 = BudgetCoord.unpack(zero.packed)
        assertEquals(0.0, unpacked0.p.toDouble(), 0.001)
        assertEquals(0.0, unpacked0.d.toDouble(), 0.001)
        assertEquals(0.0, unpacked0.q.toDouble(), 0.001)

        val one = BudgetCoord(p = 1f, d = 1f, q = 1f)
        val unpacked1 = BudgetCoord.unpack(one.packed)
        assertEquals(1.0, unpacked1.p.toDouble(), 0.001)
        assertEquals(1.0, unpacked1.d.toDouble(), 0.001)
        assertEquals(1.0, unpacked1.q.toDouble(), 0.001)
    }

    @Test
    fun budgetCoordClampsOutOfRange() {
        val coord = BudgetCoord(p = 1.5f, d = -0.1f, q = 0.5f)
        assertEquals(1.0, coord.p.toDouble(), 0.001)
        assertEquals(0.0, coord.d.toDouble(), 0.001)
        assertEquals(0.5, coord.q.toDouble(), 0.001)
    }

    @Test
    fun budgetCoordComparisonUsesRadialEnergy() {
        // radial energy = (p + d + q) / 3  -- average confidence
        val high = BudgetCoord(p = 0.9f, d = 0.8f, q = 0.7f)
        val low = BudgetCoord(p = 0.1f, d = 0.2f, q = 0.3f)
        assertTrue(high.radialEnergy > low.radialEnergy)
        assertTrue(high > low)
    }

    // --- ManifoldConcept: angular address + radial confidence ---

    @Test
    fun conceptCreationWithAngularAndRadial() {
        val concept = ManifoldConcept(
            angular = 0xAAAAAAAAAAAAAAAAL,
            budget = BudgetCoord(p = 0.8f, d = 0.6f, q = 0.4f),
            payload = DocRowVec(listOf("name"), listOf("test")),
        )
        assertEquals(0xAAAAAAAAAAAAAAAAL, concept.angular)
        assertEquals(0.8, concept.budget.p.toDouble(), 0.001)
        assertNotNull(concept.child)
        assertEquals(1, concept.child!!.size)
    }

    @Test
    fun conceptIsMiniRowVec() {
        val concept = ManifoldConcept(
            angular = 42L,
            budget = BudgetCoord(p = 1f, d = 1f, q = 1f),
            payload = DocRowVec(listOf("x"), listOf(1)),
        )
        // ManifoldConcept is a MiniRowVec
        assertTrue(concept is MiniRowVec)
        // scalar surface: [angular, packed_budget, radial_energy]
        assertEquals(3, concept.size)
    }

    @Test
    fun conceptDecayReducesRadialEnergy() {
        val concept = ManifoldConcept(
            angular = 0xFF00FF00FF00FF00L,
            budget = BudgetCoord(p = 0.9f, d = 0.8f, q = 0.7f),
            payload = DocRowVec(listOf("k"), listOf("v")),
        )
        val energyBefore = concept.budget.radialEnergy
        val decayed = concept.decay(0.9f) // 10% decay
        assertTrue(decayed.budget.radialEnergy < energyBefore)
        // angular unchanged by decay
        assertEquals(concept.angular, decayed.angular)
    }

    @Test
    fun conceptReinforceIncreasesRadialEnergy() {
        val concept = ManifoldConcept(
            angular = 0x0F0F0F0F0F0F0F0FL,
            budget = BudgetCoord(p = 0.3f, d = 0.4f, q = 0.5f),
            payload = DocRowVec(listOf("k"), listOf("v")),
        )
        val energyBefore = concept.budget.radialEnergy
        val reinforced = concept.reinforce(0.3f)
        assertTrue(reinforced.budget.radialEnergy > energyBefore)
        assertEquals(concept.angular, reinforced.angular)
    }

    // --- Hamming distance (angular metric on the manifold) ---

    @Test
    fun hammingDistanceBetweenAngularAddresses() {
        val a = 0b1010101010101010L
        val b = 0b0101010101010101L
        assertEquals(16, ManifoldConcept.hamming(a, b))

        val same = 0b1010101010101010L
        assertEquals(0, ManifoldConcept.hamming(a, same))

        val oneOff = 0b1010101010101011L
        assertEquals(1, ManifoldConcept.hamming(a, oneOff))
    }

    // --- NarsBag: manifold-shaped bag with budget tensor ---

    @Test
    fun narsBagStartsMutable() {
        val bag = NarsBag.mutable()
        assertEquals(NarsBag.State.MUTABLE, bag.state)
        assertEquals(0, bag.conceptCount)
    }

    @Test
    fun narsBagInsertAndSeal() {
        val bag = NarsBag.mutable()
        bag.insert(ManifoldConcept(
            angular = 100L,
            budget = BudgetCoord.full(),
            payload = DocRowVec(listOf("x"), listOf(1)),
        ))
        bag.insert(ManifoldConcept(
            angular = 200L,
            budget = BudgetCoord(p = 0.5f, d = 0.5f, q = 0.5f),
            payload = DocRowVec(listOf("y"), listOf(2)),
        ))
        assertEquals(2, bag.conceptCount)
        bag.seal()
        assertEquals(NarsBag.State.SEALED, bag.state)
    }

    @Test
    fun narsBagSealPreventsInsert() {
        val bag = NarsBag.mutable()
        bag.seal()
        assertFailsWith<IllegalStateException> {
            bag.insert(ManifoldConcept(
                angular = 1L,
                budget = BudgetCoord.full(),
                payload = DocRowVec(listOf("a"), listOf(1)),
            ))
        }
    }

    @Test
    fun narsBagRecallOrdersByRadialEnergy() {
        val bag = NarsBag.mutable()
        val low = ManifoldConcept(
            angular = 1L,
            budget = BudgetCoord(p = 0.1f, d = 0.1f, q = 0.1f),
            payload = DocRowVec(listOf("name"), listOf("low")),
        )
        val mid = ManifoldConcept(
            angular = 2L,
            budget = BudgetCoord(p = 0.5f, d = 0.5f, q = 0.5f),
            payload = DocRowVec(listOf("name"), listOf("mid")),
        )
        val high = ManifoldConcept(
            angular = 3L,
            budget = BudgetCoord(p = 0.9f, d = 0.9f, q = 0.9f),
            payload = DocRowVec(listOf("name"), listOf("high")),
        )
        bag.insert(low)
        bag.insert(high)
        bag.insert(mid)
        bag.seal()

        val cursor = bag.recall()
        assertEquals(3, cursor.size)
        // radial energy descending: high > mid > low
        assertEquals("high", ((cursor[0].child!![0] as DocRowVec)["name"]))
        assertEquals("mid", ((cursor[1].child!![0] as DocRowVec)["name"]))
        assertEquals("low", ((cursor[2].child!![0] as DocRowVec)["name"]))
    }

    @Test
    fun narsBagRecallBreaksTiesByAngularProximity() {
        val anchor = 0b10000000L

        val far = ManifoldConcept(
            angular = 0b00000001L, // hamming distance 7 from anchor
            budget = BudgetCoord(p = 0.8f, d = 0.8f, q = 0.8f),
            payload = DocRowVec(listOf("name"), listOf("far")),
        )
        val near = ManifoldConcept(
            angular = 0b10000001L, // hamming distance 1 from anchor
            budget = BudgetCoord(p = 0.8f, d = 0.8f, q = 0.8f),
            payload = DocRowVec(listOf("name"), listOf("near")),
        )
        val bag = NarsBag.mutable()
        bag.insert(far)
        bag.insert(near)
        bag.seal()

        val cursor = bag.recall(anchor = anchor)
        assertEquals(2, cursor.size)
        // same radial energy, but near has smaller hamming distance to anchor
        assertEquals("near", ((cursor[0].child!![0] as DocRowVec)["name"]))
        assertEquals("far", ((cursor[1].child!![0] as DocRowVec)["name"]))
    }

    // --- NarsBag budget tensor ---

    @Test
    fun narsBagBudgetTensorShapeMatchesConceptCount() {
        val bag = NarsBag.mutable()
        bag.insert(ManifoldConcept(
            angular = 1L, budget = BudgetCoord(p = 0.5f, d = 0.6f, q = 0.7f),
            payload = DocRowVec(listOf("x"), listOf(1)),
        ))
        bag.insert(ManifoldConcept(
            angular = 2L, budget = BudgetCoord(p = 0.3f, d = 0.4f, q = 0.5f),
            payload = DocRowVec(listOf("x"), listOf(2)),
        ))
        bag.seal()

        val tensor = bag.budgetTensor()
        // shape: (rowCount, 3) for P,D,Q per concept
        assertEquals(2, tensor.a[0]) // rows
        assertEquals(3, tensor.a[1]) // dimensions
        // verify values
        assertEquals(0.5, tensor[shapeOf(0, 0)].toDouble(), 0.001) // concept 0, P
        assertEquals(0.6, tensor[shapeOf(0, 1)].toDouble(), 0.001) // concept 0, D
        assertEquals(0.7, tensor[shapeOf(0, 2)].toDouble(), 0.001) // concept 0, Q
    }

    // --- Timeline: series of sealed bags with total recall ---

    @Test
    fun timelineTotalRecallScansAcrossEpochs() {
        val epoch1 = NarsBag.mutable()
        epoch1.insert(ManifoldConcept(
            angular = 1L, budget = BudgetCoord(p = 0.5f, d = 0.5f, q = 0.5f),
            payload = DocRowVec(listOf("epoch"), listOf(1)),
        ))
        epoch1.seal()

        val epoch2 = NarsBag.mutable()
        epoch2.insert(ManifoldConcept(
            angular = 2L, budget = BudgetCoord(p = 0.9f, d = 0.9f, q = 0.9f),
            payload = DocRowVec(listOf("epoch"), listOf(2)),
        ))
        epoch2.insert(ManifoldConcept(
            angular = 3L, budget = BudgetCoord(p = 0.7f, d = 0.7f, q = 0.7f),
            payload = DocRowVec(listOf("epoch"), listOf(3)),
        ))
        epoch2.seal()

        val timeline: Timeline = listOf(epoch1, epoch2).toSeries()
        val cursor = timeline.totalRecall()

        assertEquals(3, cursor.size)
        // ordered by radial energy desc: epoch2(0.9) > epoch2(0.7) > epoch1(0.5)
        assertEquals(2, ((cursor[0].child!![0] as DocRowVec)["epoch"]))
        assertEquals(3, ((cursor[1].child!![0] as DocRowVec)["epoch"]))
        assertEquals(1, ((cursor[2].child!![0] as DocRowVec)["epoch"]))
    }

    @Test
    fun timelineEmptyEpochsAreHarmless() {
        val empty = NarsBag.mutable()
        empty.seal()

        val epoch2 = NarsBag.mutable()
        epoch2.insert(ManifoldConcept(
            angular = 1L, budget = BudgetCoord.full(),
            payload = DocRowVec(listOf("k"), listOf("v")),
        ))
        epoch2.seal()

        val timeline: Timeline = listOf(empty, epoch2).toSeries()
        val cursor = timeline.totalRecall()
        assertEquals(1, cursor.size)
    }

    // --- Concept angular walk (step on the manifold) ---

    @Test
    fun conceptAngularWalkFlipsBits() {
        val origin = 0b10000000L
        val stepped = ManifoldConcept.angularWalk(origin, setOf(0, 6))
        // flip bit 0 and bit 6
        assertEquals(0b10000001L xor 0b01000000L, stepped)
    }
}
