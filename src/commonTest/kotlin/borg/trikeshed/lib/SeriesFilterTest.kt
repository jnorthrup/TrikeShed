package borg.trikeshed.lib

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeriesFilterTest {

    @Test
    fun filterEmpty() {
        val s = emptySeriesOf<Int>()
        val filtered = s.filter { it > 0 }
        assertEquals(0, filtered.size)
    }

    @Test
    fun filterNoneMatches() {
        val s = s_[1, 2, 3]
        val filtered = s.filter { it > 10 }
        assertEquals(0, filtered.size)
    }

    @Test
    fun filterAllMatches() {
        val s = s_[1, 2, 3]
        val filtered = s.filter { it > 0 }
        assertEquals(3, filtered.size)
        assertEquals(1, filtered[0])
        assertEquals(2, filtered[1])
        assertEquals(3, filtered[2])
    }

    @Test
    fun filterSomeMatches() {
        val s = s_[1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13]
        val filtered = s.filter { it % 2 == 0 }
        assertEquals(6, filtered.size)
        assertEquals(2, filtered[0])
        assertEquals(4, filtered[1])
        assertEquals(6, filtered[2])
        assertEquals(8, filtered[3])
        assertEquals(10, filtered[4])
        assertEquals(12, filtered[5])
    }
}
