package borg.trikeshed.grad

import ai.hypergraph.kotlingrad.api.*
import borg.trikeshed.duck.evalDouble
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.system.measureTimeMillis

class DselBenchmarkTest {

    @Test
    fun `benchmark standard evaluate throughput`() {
        val x = SVar(DReal, "x")
        val y = SVar(DReal, "y")
        
        // A moderately complex calculation, representing typical drawdown expressions
        val expr = (x * DReal.wrap(2.0)) + (y / DReal.wrap(3.0)) - (x * y)
        val compiledExpr = expr.compile()

        val iterations = 50_000
        val bindings = mapOf(x to 1.5, y to 2.5)

        val timeMs = measureTimeMillis {
            for (i in 0 until iterations) {
                compiledExpr.evalDouble(bindings)
            }
        }

        // Target: 20k/sec means 50k should take under 2500ms
        // A raw Kotlingrad tree lookup without JIT bounds should be well under 1000ms.
        val targetMs = 2500L
        println("Expression evaluated $iterations times in ${timeMs}ms")
        
        assertTrue(timeMs < targetMs, "Throughput failure: took ${timeMs}ms for $iterations ops (Target: <${targetMs}ms)")
    }
}
