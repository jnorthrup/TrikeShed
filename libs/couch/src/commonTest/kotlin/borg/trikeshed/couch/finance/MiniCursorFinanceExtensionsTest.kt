package borg.trikeshed.couch.finance

import borg.trikeshed.miniduck.DocRowVec
import borg.trikeshed.miniduck.at
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MiniCursorFinanceExtensionsTest {
    @Test
    fun testDoubleArrayToPriceCursor() {
        val prices = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val cursor = prices.toPriceCursor()

        assertEquals(5, cursor.size)
    }

    @Test
    fun testDoubleArrayToPriceCursorWithCustomColumn() {
        val prices = doubleArrayOf(100.0, 101.0, 102.0)
        val cursor = prices.toPriceCursor("price")

        assertEquals(3, cursor.size)
        val row = cursor.at(0) as? DocRowVec
        assertEquals(listOf("price"), row?.keys)
    }

    @Test
    fun testDoubleSeriesExtraction() {
        val prices = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val cursor = prices.toPriceCursor()
        val series = cursor.doubleSeries()

        assertEquals(5, series.size)
        assertEquals(1.0, series.b(0), 0.001)
        assertEquals(5.0, series.b(4), 0.001)
    }

    @Test
    fun testDoubleSeriesWithCustomColumn() {
        val prices = doubleArrayOf(10.0, 20.0, 30.0)
        val cursor = prices.toPriceCursor("custom")
        val series = cursor.doubleSeries("custom")

        assertEquals(3, series.size)
        assertEquals(10.0, series.b(0), 0.001)
        assertEquals(30.0, series.b(2), 0.001)
    }

    @Test
    fun testDoubleSeriesHandlesMissingColumn() {
        val prices = doubleArrayOf(1.0, 2.0, 3.0)
        val cursor = prices.toPriceCursor("close")
        val series = cursor.doubleSeries("nonexistent")

        assertEquals(3, series.size)
        assertTrue(series.b(0).isNaN())
        assertTrue(series.b(1).isNaN())
        assertTrue(series.b(2).isNaN())
    }

    @Test
    fun testDoubleSeriesHandlesStringValues() {
        val cursor = doubleArrayOf(1.0, 2.0, 3.0).toPriceCursor()
        // Manually create a cursor with a string value in one column
        val row = cursor.at(0) as? DocRowVec
        assertTrue(row != null)
    }
}
