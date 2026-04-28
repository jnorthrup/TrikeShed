package borg.trikeshed.viewserver

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JursiveInteropTest {

    @Test
    fun functionDeclaration() {
        assertTrue(isLikelyJsFn("function(doc) { emit(doc._id, 1) }"))
    }

    @Test
    fun arrowFunction() {
        assertTrue(isLikelyJsFn("(doc) => emit(doc._id, 1)"))
    }

    @Test
    fun bareBraces() {
        assertTrue(isLikelyJsFn("{ return 1 }"))
    }

    @Test
    fun leadingWhitespaceFunction() {
        assertTrue(isLikelyJsFn("  function(doc) { emit(doc._id, 1) }"))
    }

    @Test
    fun leadingWhitespaceArrow() {
        assertTrue(isLikelyJsFn("   (x) => x + 1"))
    }

    @Test
    fun leadingWhitespaceBraces() {
        assertTrue(isLikelyJsFn("\t{ return 1 }"))
    }

    @Test
    fun arrowExpression() {
        assertTrue(isLikelyJsFn("x => x"))
    }

    @Test
    fun nonFunction_helloWorld() {
        assertFalse(isLikelyJsFn("hello world"))
    }

    @Test
    fun nonFunction_number() {
        assertFalse(isLikelyJsFn("42"))
    }

    @Test
    fun nonFunction_empty() {
        assertFalse(isLikelyJsFn(""))
    }
}
