package borg.trikeshed.lib

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * RED test for U7: toSeries() deduplication.
 *
 * Only these overloads are used anywhere in the codebase:
 *   - List<T>.toSeries()
 *   - ByteArray.toSeries()     (WASM/JS file streaming)
 *   - String.toSeries()        (JSON text parsing)
 *   - Sequence<T>.toSeries()
 *
 * These are dead and should be removed:
 *   - BooleanArray, ShortArray, LongArray, FloatArray,
 *     DoubleArray, CharArray, UByteArray, UShortArray,
 *     UIntArray, ULongArray
 */
class ToSeriesUnificationTest {

    @Test
    fun `List toSeries produces correct size and values`() {
        val list = listOf("a", "b", "c")
        val s: Series<String> = list.toSeries()
        assertEquals(3, s.size)
        assertEquals("a", s[0])
        assertEquals("b", s[1])
        assertEquals("c", s[2])
    }

    @Test
    fun `ByteArray toSeries produces correct size and values`() {
        val bytes = byteArrayOf(0x10, 0x20, 0x30)
        val s: Series<Byte> = bytes.toSeries()
        assertEquals(3, s.size)
        assertEquals(0x10.toByte(), s[0])
        assertEquals(0x20.toByte(), s[1])
        assertEquals(0x30.toByte(), s[2])
    }

    @Test
    fun `String toSeries splits into chars`() {
        val s: Series<Char> = "abc".toSeries()
        assertEquals(3, s.size)
        assertEquals('a', s[0])
        assertEquals('b', s[1])
        assertEquals('c', s[2])
    }

    @Test
    fun `Sequence toSeries delegates to List toSeries`() {
        val seq = sequenceOf(1, 2, 3)
        val s: Series<Int> = seq.toSeries()
        assertEquals(3, s.size)
        assertEquals(1, s[0])
        assertEquals(2, s[1])
        assertEquals(3, s[2])
    }

    @Test
    fun `ClosedRange Int toSeries produces correct values`() {
        val s: Series<Int> = (5..9).toSeries()
        assertEquals(5, s.size)
        assertEquals(5, s[0])
        assertEquals(6, s[1])
        assertEquals(7, s[2])
        assertEquals(8, s[3])
        assertEquals(9, s[4])
    }
}
