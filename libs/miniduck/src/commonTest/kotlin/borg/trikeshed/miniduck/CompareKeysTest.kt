package borg.trikeshed.miniduck

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompareKeysTest {

    // ── null comparisons ───────────────────────────────────────────────────────
    @Test
    fun nullBeforeValue() {
        assertTrue(compareKeys(null, 1) < 0)
    }

    @Test
    fun valueAfterNull() {
        assertTrue(compareKeys(1, null) > 0)
    }

    @Test
    fun nullEqualsNull() {
        assertEquals(0, compareKeys(null, null))
    }

    // ── number comparisons ────────────────────────────────────────────────────
    @Test
    fun intEqualsInt() {
        assertEquals(0, compareKeys(1, 1))
    }

    @Test
    fun intEqualsLong() {
        assertEquals(0, compareKeys(1, 1L))
    }

    @Test
    fun intLessThanDouble() {
        assertTrue(compareKeys(1, 2.0) < 0)
    }

    @Test
    fun longLessThanLong() {
        assertTrue(compareKeys(5L, 10L) < 0)
    }

    // ── string comparisons ───────────────────────────────────────────────────────
    @Test
    fun stringEqualsString() {
        assertEquals(0, compareKeys("a", "a"))
    }

    @Test
    fun stringLessThan() {
        assertTrue(compareKeys("a", "b") < 0)
    }

    // ── mixed type comparisons ─────────────────────────────────────────────
    @Test
    fun numberLessThanString() {
        // numbers sort before strings in default ordering
        assertTrue(compareKeys(1, "abc") < 0)
    }
}