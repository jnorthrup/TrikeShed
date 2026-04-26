package borg.trikeshed.lib

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CharSeriesCacheTest {

    @Test
    fun testAsStringAndToArrayAndEquals() {
        val s = "Hello, Kotlin!".repeat(500)
        val cs1 = CharSeries(s)
        val cs2 = CharSeries(s)
        assertEquals(s, cs1.asString())
        assertTrue(cs1 == cs2)
        val arr = cs1.toArray()
        assertEquals(s, arr.concatToString())
    }

    @Test
    fun testSeekAndTrim() {
        val s = "   abc def   "
        val cs = CharSeries(s)
        cs.trim
        assertEquals("abc def", cs.asString())
    }

    @Test
    fun testSliceAndClone() {
        val s = "abcdef"
        val cs = CharSeries(s)
        cs.pos(2)
        cs.lim(5)
        val slice = cs.slice
        assertEquals("cde", slice.asString())
        val clone = slice.clone()
        assertEquals("cde", clone.asString())
    }
}
