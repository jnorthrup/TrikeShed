package borg.trikeshed.narsese

import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.job.ContentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Strata exposure contract of [HijackBeliefBag]: the funnel levels are
 * attention primacy made physical, and [HijackBeliefBag.levelRange] /
 * [HijackBeliefBag.forEachIn] / [HijackBeliefBag.levelSizes] must let a
 * per-level moment consumer walk them with zero new storage in the bag.
 */
class FunnelLevelRangeTest {

    private fun mintSlot(term: String): HijackBeliefBag.Slot {
        val signal = SemanticSignal(
            angular = AngularCodec.encode(RelationKind.CAUSALITY, taxonomyKey = "strata", subjectTerm = term),
            evidence = EvidenceCoord(10 * Nal.UNIT, 0),
            relation = RelationKind.CAUSALITY,
            subjectCid = ContentId.of(term.encodeToByteArray()).value,
        )
        return HijackBeliefBag.Slot(signal.angular, BudgetCoord(0.6f, 0.5f, 0.5f), signal)
    }

    @Test
    fun levelRangesPartitionTheSpaceExactly() {
        // several geometries, including non-power-of-two and degenerate single-level
        for ((cap, beta) in listOf(64 to 4, 257 to 4, 33 to 8, 4 to 4)) {
            val bag = HijackBeliefBag(capacity = cap, beta = beta)
            val covered = BooleanArray(bag.space)
            for (level in 0 until bag.levels) {
                for (i in bag.levelRange(level)) {
                    assertTrue(i in 0 until bag.space, "cap=$cap beta=$beta: index $i outside [0, ${bag.space})")
                    assertFalse(covered[i], "cap=$cap beta=$beta: levels overlap at slot $i")
                    covered[i] = true
                }
            }
            assertTrue(covered.all { it }, "cap=$cap beta=$beta: ranges must cover [0, ${bag.space}) exactly")
        }
    }

    @Test
    fun placedBeliefIsFoundByForEachInOfExactlyItsLevelRange() {
        val bag = HijackBeliefBag(capacity = 64)
        val slot = mintSlot("stratum probe belief")
        bag.place(slot)

        val level = bag.levelOf(slot.angular)
        assertTrue(level >= 0, "placed belief must be live")

        var hits = 0
        bag.forEachIn(bag.levelRange(level)) { if (it.angular == slot.angular) hits++ }
        assertEquals(1, hits, "belief must surface exactly once in its own stratum")

        for (other in 0 until bag.levels) {
            if (other == level) continue
            bag.forEachIn(bag.levelRange(other)) {
                assertTrue(it.angular != slot.angular, "belief leaked into level $other (lives at $level)")
            }
        }
    }

    @Test
    fun levelSizesSumsToSizeAndMatchesRangeCounts() {
        val bag = HijackBeliefBag(capacity = 128)
        repeat(40) { i -> bag.place(mintSlot("belief stratum member $i")) }

        val sizes = bag.levelSizes
        assertEquals(bag.levels, sizes.size)
        assertEquals(bag.size, sizes.sum(), "levelSizes must sum to size")

        // cross-check each count against a forEachIn walk of the same stratum
        for (level in 0 until bag.levels) {
            var n = 0
            bag.forEachIn(bag.levelRange(level)) { n++ }
            assertEquals(sizes[level], n, "level $level count must agree with its range walk")
        }
    }
}
