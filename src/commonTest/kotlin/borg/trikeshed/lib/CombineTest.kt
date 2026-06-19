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



    private fun ints(vararg values: Int): Series<Int> = values.size j { index -> values[index] }
}
