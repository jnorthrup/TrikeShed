package borg.trikeshed.lib

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CombineTest {
    @Test
    fun combineSelectsCorrectStairForMoreThanFourSeries() {
        val combined = combine(
            ints(0, 1),
            ints(2),
            ints(3, 4, 5),
            ints(6),
            ints(7, 8),
            ints(9),
        )

        assertEquals((0..9).toList(), combined.toList())
    }

    @Test
    fun reificationContextTracksExhaustionWithoutNull() {
        assertEquals(ReificationContext(1), ReificationContext(2).deeper())
        assertEquals(ReificationContext(0), ReificationContext(1).deeper())
        assertEquals(ReificationContext(0), ReificationContext(0).deeper())
        assertEquals(true, ReificationContext(0).isExhausted)
        assertEquals(false, ReificationContext(1).isExhausted)
    }

    @Test
    fun noContextCombineRemainsUnmarkedLazyView() {
        val combined = combine(ints(0), ints(1))

        assertEquals(listOf(0, 1), combined.toList())
        assertFalse(combined is StaircaseSeries<*>)
    }

    @Test
    fun reificationContextCapsStaircaseDepthWithArrayListLeaves() {
        val ctx = ReificationContext(1)
        val left = combine(ctx, ints(0), ints(1))
        val right = combine(ctx, ints(2), ints(3))

        val merged = combine(ctx, left, right)

        assertEquals((0..3).toList(), merged.toList())
        assertEquals(1, (merged as StaircaseSeries<*>).staircaseDepth)
    }

    @Test
    fun exhaustedReificationContextProducesDepthZeroSeries() {
        val merged = combine(ReificationContext(0), ints(0), ints(1, 2))

        assertEquals(listOf(0, 1, 2), merged.toList())
        assertEquals(0, (merged as StaircaseSeries<*>).staircaseDepth)
    }

    private fun ints(vararg values: Int): Series<Int> = values.size j { index -> values[index] }
}
