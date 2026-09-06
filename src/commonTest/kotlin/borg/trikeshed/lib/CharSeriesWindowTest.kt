package borg.trikeshed.lib

import borg.trikeshed.parse.json.JsonSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CharSeriesWindowTest {
    @Test
    fun packedAndRangeWindowsShareTheCharacterSequenceView() {
        val source = CharSeries("0123456789")
        val packed = source[2 j 8]
        assertEquals("234567", packed.cs.toString())
        assertSame<Any>(packed, packed.cs)
        assertEquals("234567", source[2 until 8].cs.toString())
        assertEquals("3456", source.subSequence(2 j 8).subSequence(1, 5).let { buildString { append(it) } })
        assertEquals("", source[8 until 8].cs.toString())
        assertEquals("", CharSeries("")[IntRange.EMPTY].cs.toString())
    }

    @Test
    fun windowsRejectReadsOutsideTheirOwnBounds() {
        val window = CharSeries("abcdef")[2 j 4].cs
        assertEquals('c', window[0])
        assertEquals('d', window[1])
        assertFailsWith<IndexOutOfBoundsException> { window[-1] }
        assertFailsWith<IndexOutOfBoundsException> { window[2] }
        assertFailsWith<IndexOutOfBoundsException> { window.subSequence(-1, 1) }
        assertFailsWith<IndexOutOfBoundsException> { window.subSequence(0, 3) }
        assertFailsWith<IndexOutOfBoundsException> { window.subSequence(2, 1) }
        assertFailsWith<IndexOutOfBoundsException> { window.subSequence(2, 2)[0] }
    }

    @Test
    fun genericCharacterSequenceSlicesStayFlatAndLazy() {
        var reads = 0
        val text = StringBuilder("a".repeat(2048))
        val source: Series<Char> = text.length j { index: Int -> reads++; text[index] }
        var view = source.cs
        repeat(200) { view = view.subSequence(1, view.length - 1) }
        assertEquals(0, reads)
        text[700] = 'Z'
        assertEquals('Z', view[500])
        assertEquals(1, reads)
    }

    @Test
    fun whitespaceWindowsAndClonesPreserveCursorAndBacking() {
        val text = StringBuilder("x one  two y")
        val source = CharSeries(text as CharSequence).pos(2).lim(10)
        source.mk
        val clone = source.clone()
        val words = source.splitWs()
        assertEquals(2, words.size)
        assertEquals("one", words[0].asString())
        assertEquals("two", words[1].asString())
        text[2] = 'O'
        assertEquals("One", words[0].asString())
        source.pos(0).lim(1)
        assertEquals(2, clone.pos)
        assertEquals(10, clone.limit)
        assertEquals(2, clone.mark)
    }

    @Test
    fun nestedWindowsStayLazyAndKeepAbsoluteIndexing() {
        val text = StringBuilder("a".repeat(2048))
        var chars = CharSeries(text as CharSequence)
        repeat(200) { chars = chars.pos(1).lim(chars.length - 1).slice }
        text[700] = 'Z'
        assertEquals('Z', chars[500])
        assertEquals('Z', chars.b(500))
        assertEquals(1648, chars.size)
        chars.pos(500).lim(501)
        assertEquals("Z", chars.asString())
        val clone = chars.clone()
        chars.pos(0)
        assertEquals(500, clone.pos)
        assertEquals("Z", clone.asString())
    }

    @Test
    fun genericSeriesFallbackDoesNotMultiplyAccessorCalls() {
        var calls = 0
        val series: Series<Char> = 1024 j { i: Int -> calls++; ('a'.code + i % 26).toChar() }
        var chars = CharSeries(series)
        repeat(100) { chars = chars.pos(1).lim(chars.length - 1).slice }
        calls = 0
        assertEquals(('a'.code + 300 % 26).toChar(), chars[200])
        assertEquals(1, calls)
        assertEquals("", chars.pos(0).lim(0).asString())
    }

    @Test
    fun jsonNestedSlicesPreserveEscapesNumbersAndOffsets() {
        val text = "x".repeat(300) + "\n\u1234"
        val value = mapOf("long" to text, "nested" to listOf(mapOf("n" to 7, "b" to true), emptyList<Any>()))
        val parsed = JsonSupport.parse(JsonSupport.stringify(value)) as Map<*, *>
        assertEquals(text, parsed["long"])
        val nested = parsed["nested"] as List<*>
        assertEquals(7.0, (nested[0] as Map<*, *>)["n"])
        assertEquals(true, (nested[0] as Map<*, *>)["b"])
        assertTrue((nested[1] as List<*>).isEmpty())
    }
}
