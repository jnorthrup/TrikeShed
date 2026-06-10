package borg.trikeshed.couch.finance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.math.abs

class DoubleArrayMathTest {
    @Test
    fun testMeanOfEmptyArray() {
        val result = mean(doubleArrayOf())
        assertTrue(result.isNaN())
    }

    @Test
    fun testMeanOfSingleElement() {
        val result = mean(doubleArrayOf(5.0))
        assertEquals(5.0, result, 0.001)
    }

    @Test
    fun testMeanOfMultipleElements() {
        val result = mean(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0))
        assertEquals(3.0, result, 0.001)
    }

    @Test
    fun testVarianceOfEmptyArray() {
        val result = variance(doubleArrayOf())
        assertTrue(result.isNaN())
    }

    @Test
    fun testVarianceOfSingleElement() {
        val result = variance(doubleArrayOf(5.0))
        assertEquals(0.0, result, 0.001)
    }

    @Test
    fun testVarianceOfMultipleElements() {
        val result = variance(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0))
        assertEquals(2.0, result, 0.001) // population variance
    }

    @Test
    fun testStdDev() {
        val result = stdDev(doubleArrayOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0))
        assertEquals(2.0, result, 0.001)
    }

    @Test
    fun testEma() {
        val history = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val result = ema(history, span = 3)
        assertEquals(5, result.size)
        // First element should be seeded with first value
        assertEquals(1.0, result[0], 0.001)
        // EMA should be increasing for monotonic input
        assertTrue(result[4] > result[3])
        assertTrue(result[3] > result[2])
    }

    @Test
    fun testWindowSum() {
        val values = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val result = windowSum(values, window = 3)
        assertEquals(5, result.size)
        assertEquals(6.0, result[2], 0.001) // 1+2+3
        assertEquals(9.0, result[3], 0.001) // 2+3+4
        assertEquals(12.0, result[4], 0.001) // 3+4+5
    }
}
