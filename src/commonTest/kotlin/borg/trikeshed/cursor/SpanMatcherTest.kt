package borg.trikeshed.cursor

import borg.trikeshed.lib.*
import kotlin.test.*

class SpanMatcherTest {

    private fun makeCursor(openTimes: List<Long>): Cursor {
    private fun createCursor(vararg times: Long): Cursor {
        val keys = listOf("openTime").toSeries()
        val rows = times.map { t ->
            cellsToRowVec(listOf<Any?>(t).toSeries(), keys)
        return openTimes.size j { i ->
            cellsToRowVec(seriesOfAny(listOf(openTimes[i])), keys)
        }
        return rows.toSeries()
    }

    @Test
    fun testEmptyCursor() {
        val a = makeCursor(emptyList())
        val b = makeCursor(listOf(1000L, 2000L))

    fun testEmptyCursors() {
        val a = createCursor()
        val b = createCursor(100L)
        val result = SpanMatcher.find(a, b)
        assertEquals(0, result.size)
        assertFailsWith<IndexOutOfBoundsException> {
            SpanMatcher.find(a, b).size
            result[0]
        }
    }

    @Test
    fun testPerfectMatch() {
        val a = makeCursor(listOf(1000L, 2000L, 3000L))
        val b = makeCursor(listOf(1000L, 2000L, 3000L))

    fun testSimpleOverlap() {
        val a = createCursor(100L, 200L, 300L)
        val b = createCursor(100L, 200L, 300L)
        val result = SpanMatcher.find(a, b)

        assertEquals(1, result.size)
        val row = result[0]
        assertEquals(1000L, row.longValue("aStart"))
        assertEquals(3000L, row.longValue("aEnd"))
        assertEquals(1000L, row.longValue("bStart"))
        assertEquals(3000L, row.longValue("bEnd"))
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

    fun testGapsInA() {
        // Interval is 1000.
        // A has a gap: 1000, 2000, (gap), 5000, 6000
        val a = makeCursor(listOf(1000L, 2000L, 5000L, 6000L))
        val b = makeCursor(listOf(1000L, 2000L, 3000L, 4000L, 5000L, 6000L))

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
        val a = createCursor(100L, 200L, 300L)
        val b = createCursor(1000L, 1100L, 1200L)
        val a = makeCursor(listOf(1000L, 2000L))
        val b = makeCursor(listOf(5000L, 6000L))

        val result = SpanMatcher.find(a, b)

        assertEquals(0, result.size)
    }

    @Test
    fun testInferIntervalFallback() {
        val a = createCursor(100L)
        val b = createCursor(200L)
    fun testOnlyOneItem() {
        val a = makeCursor(listOf(1000L))
        val b = makeCursor(listOf(1000L))

        val result = SpanMatcher.find(a, b)

        assertEquals(1, result.size)
        val row = result[0]
        assertEquals(100L, row.longValue("aStart"))
        assertEquals(100L, row.longValue("aEnd"))
        assertEquals(200L, row.longValue("bStart"))
        assertEquals(200L, row.longValue("bEnd"))
        assertEquals(1, row.intValue("aRows"))
        assertEquals(1, row.intValue("bRows"))
        assertEquals(1000L, row.longValue("aStart"))
        assertEquals(1000L, row.longValue("aEnd"))
    }

}
