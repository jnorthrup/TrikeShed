package borg.trikeshed.cursor

import borg.trikeshed.lib.*
import kotlin.test.*

class SpanMatcherTest {

    private fun createCursor(vararg times: Long): Cursor {
        val keys = listOf("openTime").toSeries()
        val rows = times.map { t ->
            cellsToRowVec(listOf<Any?>(t).toSeries(), keys)
        }
        return rows.toSeries()
    }

    @Test
    fun testEmptyCursors() {
        val a = createCursor()
        val b = createCursor(100L)
        val result = SpanMatcher.find(a, b)
        assertEquals(0, result.size)
        assertFailsWith<IndexOutOfBoundsException> {
            result[0]
        }
    }

    @Test
    fun testSimpleOverlap() {
        val a = createCursor(100L, 200L, 300L)
        val b = createCursor(100L, 200L, 300L)
        val result = SpanMatcher.find(a, b)

        assertEquals(1, result.size)
        val row = result[0]
        assertEquals(100L, row.longValue("aStart"))
        assertEquals(300L, row.longValue("aEnd"))
        assertEquals(100L, row.longValue("bStart"))
        assertEquals(300L, row.longValue("bEnd"))
        assertEquals(3, row.intValue("aRows"))
        assertEquals(3, row.intValue("bRows"))
    }

    @Test
    fun testGaps() {
        // Interval should be inferred as 100, tolerance 10
        val a = createCursor(100L, 200L, 300L, 500L, 600L)
        val b = createCursor(100L, 200L, 300L, 500L, 600L)

        val result = SpanMatcher.find(a, b)

        assertEquals(2, result.size)

        val row0 = result[0]
        assertEquals(100L, row0.longValue("aStart"))
        assertEquals(300L, row0.longValue("aEnd"))
        assertEquals(100L, row0.longValue("bStart"))
        assertEquals(300L, row0.longValue("bEnd"))
        assertEquals(3, row0.intValue("aRows"))
        assertEquals(3, row0.intValue("bRows"))

        val row1 = result[1]
        assertEquals(500L, row1.longValue("aStart"))
        assertEquals(600L, row1.longValue("aEnd"))
        assertEquals(500L, row1.longValue("bStart"))
        assertEquals(600L, row1.longValue("bEnd"))
        assertEquals(2, row1.intValue("aRows"))
        assertEquals(2, row1.intValue("bRows"))
    }

    @Test
    fun testNoOverlap() {
        val a = createCursor(100L, 200L, 300L)
        val b = createCursor(1000L, 1100L, 1200L)
        val result = SpanMatcher.find(a, b)

        assertEquals(0, result.size)
    }

    @Test
    fun testInferIntervalFallback() {
        val a = createCursor(100L)
        val b = createCursor(200L)

        val result = SpanMatcher.find(a, b)

        assertEquals(1, result.size)
        val row = result[0]
        assertEquals(100L, row.longValue("aStart"))
        assertEquals(100L, row.longValue("aEnd"))
        assertEquals(200L, row.longValue("bStart"))
        assertEquals(200L, row.longValue("bEnd"))
        assertEquals(1, row.intValue("aRows"))
        assertEquals(1, row.intValue("bRows"))
    }
}
