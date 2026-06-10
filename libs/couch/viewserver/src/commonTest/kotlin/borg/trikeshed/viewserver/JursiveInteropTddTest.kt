package borg.trikeshed.viewserver

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TDD spec for Jursive interop: isLikelyJsFn.
 *
 * A CouchDB-style view function is either:
 *   - function(doc) { ... }      — classic function declaration
 *   - (doc) => ...              — arrow function
 *   - { return ... }            — bare block that is a function body
 *   - x => x                    — identifier => expression
 *   - (x, y) => { ... }         — multi-param arrow
 */
class JursiveInteropTddTest {

    @Test
    fun `function keyword is detected`() {
        assertTrue(isLikelyJsFn("function(doc) { emit(doc._id, 1) }"))
    }

    @Test
    fun `function with leading whitespace is detected`() {
        assertTrue(isLikelyJsFn("  function(doc) { return doc.x }"))
    }

    @Test
    fun `arrow with parens is detected`() {
        assertTrue(isLikelyJsFn("(doc) => emit(doc._id, 1)"))
    }

    @Test
    fun `arrow with leading whitespace is detected`() {
        assertTrue(isLikelyJsFn("   (x) => x + 1"))
    }

    @Test
    fun `bare braces is detected`() {
        assertTrue(isLikelyJsFn("{ return 1 }"))
    }

    @Test
    fun `bare braces with tab is detected`() {
        assertTrue(isLikelyJsFn("\t{ return doc.value }"))
    }

    @Test
    fun `identifier arrow is detected`() {
        assertTrue(isLikelyJsFn("x => x"))
    }

    @Test
    fun `multi param arrow with braces is detected`() {
        assertTrue(isLikelyJsFn("(key, value) => { return key + value }"))
    }

    @Test
    fun `hello world is not a function`() {
        assertFalse(isLikelyJsFn("hello world"))
    }

    @Test
    fun `empty string is not a function`() {
        assertFalse(isLikelyJsFn(""))
    }

    @Test
    fun `number literal is not a function`() {
        assertFalse(isLikelyJsFn("42"))
    }

    @Test
    fun `json is not a function`() {
        assertFalse(isLikelyJsFn("""{"key": "value"}"""))
    }

    @Test
    fun `sql is not a function`() {
        assertFalse(isLikelyJsFn("SELECT * FROM users WHERE active = true"))
    }
}
