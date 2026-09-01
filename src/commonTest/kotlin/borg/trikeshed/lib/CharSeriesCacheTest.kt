package borg.trikeshed.lib

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

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

    /**
     * A cursor is not a key. Without an effect system the only enforcement left
     * is to fail at the point of misuse, because the alternative — what a
     * HashMap does with an unspecified CharSequence contract — is to miss
     * silently and report nothing.
     */
    @Test
    fun keyingACursorThrowsInsteadOfMissingSilently() {
        val cs = CharSeries("AAPL")
        assertFailsWith<UnsupportedOperationException> { cs.hashCode() }
        assertFailsWith<UnsupportedOperationException> { hashMapOf(cs to 1) }
        // the gate: reify deliberately, key on that
        assertEquals(1, hashMapOf(cs.asString() to 1)[CharSeries("AAPL").asString()])
        // and the content hash is still there for a cache that knows what it is
        assertEquals(CharSeries("AAPL").cacheCode, cs.cacheCode)
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
