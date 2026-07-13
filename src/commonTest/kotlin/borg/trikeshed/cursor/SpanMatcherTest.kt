package borg.trikeshed.cursor

import borg.trikeshed.lib.*
import kotlin.test.*

class SpanMatcherTest {

    private fun makeCursor(openTimes: List<Long>): Cursor {
        val keys = listOf("openTime").toSeries()
        return openTimes.size j { i ->
            cellsToRowVec(seriesOfAny(listOf(openTimes[i])), keys)
        }
    }

    @Test
    fun testEmptyCursor() {
        val a = makeCursor(emptyList())
        val b = makeCursor(listOf(1000L, 2000L))

        assertFailsWith<IndexOutOfBoundsException> {
            SpanMatcher.find(a, b).size
        }
    }

    @Test
    fun testPerfectMatch() {
        val a = makeCursor(listOf(1000L, 2000L, 3000L))
        val b = makeCursor(listOf(1000L, 2000L, 3000L))

        val result = SpanMatcher.find(a, b)
        assertEquals(1, result.size)
        val row = result[0]
        assertEquals(1000L, row.longValue("aStart"))
        assertEquals(3000L, row.longValue("aEnd"))
        assertEquals(1000L, row.longValue("bStart"))
        assertEquals(3000L, row.longValue("bEnd"))
        assertEquals(3, row.intValue("aRows"))
        assertEquals(3, row.intValue("bRows"))
    }

    @Test
    fun testGapsInA() {
        // Interval is 1000.
        // A has a gap: 1000, 2000, (gap), 5000, 6000
        val a = makeCursor(listOf(1000L, 2000L, 5000L, 6000L))
        val b = makeCursor(listOf(1000L, 2000L, 3000L, 4000L, 5000L, 6000L))

        val result = SpanMatcher.find(a, b)
        assertEquals(2, result.size)

        val r0 = result[0]
        assertEquals(1000L, r0.longValue("aStart"))
        assertEquals(3000L, r0.longValue("aEnd")) // Wait, why 3000? Let's check SpanMatcher logic for hasGapAfter

        val r1 = result[1]
        assertEquals(4000L, r1.longValue("aStart"))
        assertEquals(6000L, r1.longValue("aEnd"))
    }

    @Test
    fun testGapsInBoth() {
        val a = makeCursor(listOf(1000L, 2000L, 5000L, 6000L))
        val b = makeCursor(listOf(1000L, 2000L, 5000L, 6000L))

        val result = SpanMatcher.find(a, b)
        assertEquals(2, result.size)

        val r0 = result[0]
        assertEquals(1000L, r0.longValue("aStart"))
        assertEquals(2000L, r0.longValue("aEnd"))
        assertEquals(2, r0.intValue("aRows"))
        assertEquals(2, r0.intValue("bRows"))

        val r1 = result[1]
        assertEquals(5000L, r1.longValue("aStart"))
        assertEquals(6000L, r1.longValue("aEnd"))
        assertEquals(2, r1.intValue("aRows"))
        assertEquals(2, r1.intValue("bRows"))
    }

    @Test
    fun testNoOverlap() {
        val a = makeCursor(listOf(1000L, 2000L))
        val b = makeCursor(listOf(5000L, 6000L))

        val result = SpanMatcher.find(a, b)
        assertEquals(0, result.size)
    }

    @Test
    fun testOnlyOneItem() {
        val a = makeCursor(listOf(1000L))
        val b = makeCursor(listOf(1000L))

        val result = SpanMatcher.find(a, b)
        assertEquals(1, result.size)
        val row = result[0]
        assertEquals(1000L, row.longValue("aStart"))
        assertEquals(1000L, row.longValue("aEnd"))
    }

}
