package borg.trikeshed.lib

import kotlin.test.Test
import kotlin.test.assertEquals

class CombineTest {
    @Test
    fun emptyConcatenationDoesNotReadAnElement() {
        val empty: Series<Series<Int>> = 0 j { _: Int -> error("An empty outer Series has no element") }
        assertEquals(0, combine(empty).size)
        assertEquals(0, combine<Int>().size)
    }

    @Test
    fun emptyComponentsDoNotHideValuesAtTheirSharedOffset() {
        val parts = 12 j { i: Int -> if (i == 0 || i == 11) ints(i) else emptySeriesOf<Int>() }
        val combined = combine(parts)
        assertEquals(2, combined.size)
        assertEquals(0, combined[0])
        assertEquals(11, combined[1])
    }

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
