package borg.trikeshed.lib

import kotlin.test.Test
import kotlin.test.assertEquals

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

    private fun ints(vararg values: Int): Series<Int> = values.size j { index -> values[index] }
}
