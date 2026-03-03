package borg.trikeshed.grad

import ai.hypergraph.kotlingrad.api.*
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertNotSame

class CompilerCacheTest {

    @Test
    fun `identical structural expressions return the exact same reference via compile pass`() {
        CompilerCache.clear()

        val x = SVar(DReal, "x")
        val y = SVar(DReal, "y")
        
        // Two independent un-cached copies of (x * 2.0) + y
        val expr1 = (x * DReal.wrap(2.0)) + y
        val expr2 = (x * DReal.wrap(2.0)) + y

        // Without cache, they are distinct JVM objects
        assertNotSame(expr1, expr2)

        // Compile pass forces structural de-duplication
        val cached1 = expr1.compile()
        val cached2 = expr2.compile()

        // With cache, they share the identical memory reference
        assertSame(cached1, cached2)
    }
}
