package borg.trikeshed.lib

import kotlin.test.*

class SeriesFoldTest {
    @Test
    fun emptySeriesKeepsIdentityWithoutReadingOrCombining() {
        val empty: Series<Int> = 0 j { _: Int -> error("An empty series has no coordinates") }
        val identity = Any()
        assertSame(identity, empty.fold(identity) { _, _ -> error("An empty series has no reduction") })
        assertSame(identity, empty.view.fold(identity) { _, _ -> error("An empty view has no reduction") })
    }

    @Test
    fun foldConsumesEachCoordinateOnceInOrder() {
        val reads = mutableListOf<Int>()
        val source: Series<Int> = 3 j { i: Int -> reads.add(i); i + 1 }
        assertEquals(4, source.fold(10) { acc, element -> acc - element })
        assertEquals<List<Int>>(listOf(0, 1, 2), reads)
    }

    @Test
    fun iterableViewUsesTheSameFoldWithoutReentry() {
        val source = s_[3, 2, 1].view
        assertEquals("x/3/2/1", source.fold("x") { acc, element -> "$acc/$element" })
    }

    @Test
    fun typedReducerReadsTheLiveOracleBetweenReductionSteps() {
        val values = intArrayOf(2, 3, 4)
        val trace = mutableListOf<String>()
        val source: Series<Int> = values.size j { i: Int ->
            trace.add("read:$i")
            values[i]
        }
        val reducer = object : Reducer<Int, Int> {
            override val zero = 10
            var reductions = 0
            override fun combine(acc: Int, element: Int): Int {
                trace.add("combine:$element")
                reductions++
                if (reductions < values.size) values[reductions] += 10
                return acc - element
            }
        }
        assertEquals(-19, source.fold(reducer))
        assertEquals<List<String>>(listOf("read:0", "combine:2", "read:1", "combine:13", "read:2", "combine:14"), trace)
        val empty: Series<Int> = emptySeriesOf()
        assertEquals(10, empty.fold(reducer))
        assertEquals(3, reducer.reductions)
    }
}
