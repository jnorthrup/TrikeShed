package borg.trikeshed.pointcut.polyglot

import kotlin.test.Test
import kotlin.test.assertNotNull

class BasicPolyglotPointcutTest {
    @Test
    fun testIntercept() {
        val pointcut = BasicPolyglotPointcut()
        val result = pointcut.intercept("js", "console.log('test')")
        assertNotNull(result)
    }
}
