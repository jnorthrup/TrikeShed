package borg.trikeshed.narsese

import borg.trikeshed.cursor.BudgetCoord
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HijackBeliefBagInvariantTest {
    private fun slot(key: Long, weight: Long = Nal.UNIT): HijackBeliefBag.Slot {
        val signal = SemanticSignal(key, EvidenceCoord(weight, 0L), RelationKind.MATCH, "subject-$key")
        return HijackBeliefBag.Slot(key, BudgetCoord(0.6f, 0.5f, 0.5f), signal)
    }

    private fun occurrences(bag: HijackBeliefBag, key: Long): Int {
        var n = 0
        bag.forEach { if (it.angular == key) n++ }
        return n
    }

    @Test
    fun putSearchesDeeperLevelsBeforeUsingAnEarlierVacancy() {
        // Make the second put lose the first-level roulette and descend.
        val rng = object : Random() {
            override fun nextBits(bitCount: Int): Int = if (bitCount == 0) 0 else -1 ushr (32 - bitCount)
        }
        val bag = HijackBeliefBag(capacity = 2, beta = 1, rng = rng)
        bag.put(slot(1)) { a, _ -> a }
        bag.put(slot(2)) { a, _ -> a }
        assertEquals(1, bag.levelOf(2L))
        assertNotNull(bag.remove(1L))
        var merges = 0
        val result = bag.put(slot(2, 2 * Nal.UNIT)) { _, b -> merges++; b }
        assertTrue(result is HijackBeliefBag.Put.Placed && result.merged)
        assertEquals(1, merges)
        assertEquals(1, bag.size)
        assertEquals(1, occurrences(bag, 2L))
        assertEquals(2 * Nal.UNIT, bag.get(2L)?.signal?.evidence?.positive)
        assertNotNull(bag.remove(2L))
        assertNull(bag.get(2L))
    }

    @Test
    fun replaySearchesAllLevelsAndTheWholeBucketBeforePlacement() {
        val deep = HijackBeliefBag(capacity = 2, beta = 1)
        deep.place(slot(1)); deep.place(slot(2)); deep.remove(1L)
        deep.place(slot(2, 2 * Nal.UNIT))
        assertEquals(1, deep.size)
        assertEquals(1, occurrences(deep, 2L))
        assertEquals(2 * Nal.UNIT, deep.get(2L)?.signal?.evidence?.positive)

        val sameBucket = HijackBeliefBag(capacity = 4, beta = 2)
        sameBucket.place(slot(1)); sameBucket.place(slot(2)); sameBucket.remove(1L)
        sameBucket.place(slot(2))
        assertEquals(1, sameBucket.size)
        assertEquals(1, occurrences(sameBucket, 2L))
    }

    @Test
    fun cascadeIntoFreeCellIncreasesPhysicalSize() {
        val rng = object : Random() { override fun nextBits(bitCount: Int): Int = 0 }
        val bag = HijackBeliefBag(capacity = 2, beta = 1, rng = rng)
        bag.put(slot(1)) { a, _ -> a }
        val second = bag.put(slot(2)) { a, _ -> a }
        assertTrue(second is HijackBeliefBag.Put.Placed)
        assertFalse(second.merged)
        assertEquals(2, bag.size)
        assertEquals(2, bag.levelSizes.sum())
        assertEquals(1, occurrences(bag, 1L))
        assertEquals(1, occurrences(bag, 2L))
        assertNotNull(bag.remove(1L))
        assertEquals(1, bag.size)
        assertEquals(1, bag.levelSizes.sum())
    }
    @Test
    fun uniqueKeysStillFollowRouletteRejectionAndHijack() {
        val lose = object : Random() {
            override fun nextBits(bitCount: Int): Int = if (bitCount == 0) 0 else -1 ushr (32 - bitCount)
        }
        val rejecting = HijackBeliefBag(capacity = 2, beta = 1, rng = lose)
        rejecting.place(slot(1)); rejecting.place(slot(2))
        assertTrue(rejecting.put(slot(3)) { a, _ -> a } is HijackBeliefBag.Put.Rejected)
        assertEquals(2, rejecting.size)
        assertNotNull(rejecting.get(1L)); assertNotNull(rejecting.get(2L)); assertNull(rejecting.get(3L))

        val win = object : Random() { override fun nextBits(bitCount: Int): Int = 0 }
        val hijacking = HijackBeliefBag(capacity = 2, beta = 1, rng = win)
        hijacking.place(slot(1)); hijacking.place(slot(2))
        val outcome = hijacking.put(slot(3)) { a, _ -> a }
        assertTrue(outcome is HijackBeliefBag.Put.Hijacked)
        assertEquals(1L, outcome.victim.angular)
        assertEquals(2, hijacking.size)
        assertNull(hijacking.get(1L)); assertNotNull(hijacking.get(2L)); assertNotNull(hijacking.get(3L))
    }

}
