package borg.trikeshed.lib

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertSame

class SeriesFilterTest {

    @Test
    fun orderingPreservesTiesAndMetadataOracles() {
        var reads = 0
        val a = 2 j { reads++; "a" }
        val b = 1 j { reads++; "b" }
        val c = 2 j { reads++; "c" }
        val ordered = s_[a, b, c].sortedWith(compareBy { it.a })
        assertSame(b, ordered[0])
        assertSame(a, ordered[1])
        assertSame(c, ordered[2])
        assertEquals(0, reads)
        assertEquals("b", ordered[0].b())
        assertEquals(1, reads)
    }

    @Test
    fun drainingTransfersStorageBeforeTheNextFill() {
        val buffer = SeriesBuffer<Any>(1)
        val item = Any()
        buffer.add(item)
        val drained = buffer.drain()
        assertEquals(0, buffer.size)
        buffer.add(Any())
        buffer.clear()
        assertEquals(1, drained.size)
        assertSame(item, drained[0])
    }

    @Test
    fun firstOrNullRespectsTheSeriesBound() {
        assertNull(emptySeriesOf<String>().firstOrNull())
        assertEquals("first", s_["first", "second"].firstOrNull())
    }

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
