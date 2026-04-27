package borg.trikeshed.lib

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ByteSeriesTest {

    @Test
    fun testSeekToLiteral() {
        val bs = ByteSeries("hello world")
        assertTrue(bs.seekTo("world".encodeToByteArray() α { it }))
        assertEquals(11, bs.pos)
    }

    @Test
    fun testSeekToLiteralMiss() {
        val bs = ByteSeries("hello world")
        assertFalse(bs.seekTo("xyz".encodeToByteArray() α { it }))
        assertEquals(0, bs.pos) // position unchanged on miss
    }

    @Test
    fun testSplitWs() {
        val bs = ByteSeries("  one two  three ")
        val parts = bs.splitWs()
        assertEquals(3, parts.size)
        assertEquals("one", parts[0].asString())
        assertEquals("two", parts[1].asString())
        assertEquals("three", parts[2].asString())
    }

    @Test
    fun testConfixScope() {
        val bs = ByteSeries("[content]")
        bs.confixScope { it == '['.code.toByte() || it == ']'.code.toByte() }
        assertEquals("content", bs.asString())
    }

    @Test
    fun testTrim() {
        val bs = ByteSeries("  abc def  ")
        bs.trim
        assertEquals("abc def", bs.asString())
    }

    @Test
    fun testUnbrace() {
        val bs = ByteSeries("{key}")
        assertTrue(ByteSeries.unbrace(bs))
        assertEquals("key", bs.asString())
    }

    @Test
    fun testUnbraceNoMatch() {
        val bs = ByteSeries("plain")
        assertFalse(ByteSeries.unbrace(bs))
        assertEquals("plain", bs.asString())
    }

    @Test
    fun testUnbracket() {
        val bs = ByteSeries("[item]")
        assertTrue(ByteSeries.unbracket(bs))
        assertEquals("item", bs.asString())
    }

    @Test
    fun testUnquote() {
        val bs = ByteSeries("\"value\"")
        assertTrue(ByteSeries.unquote(bs))
        assertEquals("value", bs.asString())
    }
}
