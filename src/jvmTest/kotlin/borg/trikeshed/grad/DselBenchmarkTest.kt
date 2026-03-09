package borg.trikeshed.grad

import ai.hypergraph.kotlingrad.api.*
import borg.trikeshed.duck.evalDouble
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.system.measureTimeMillis

class DselBenchmarkTest {
    @Test
    fun `drawdown contract computes deterministic fraction from peak`() {
        val close = SVar(DReal, "close")
        val peak = SVar(DReal, "peak")
        val drawdownExpr = ((close - peak) / peak).compile()

        val drawdown = drawdownExpr.evalDouble(
            mapOf(
                close to 90.0,
                peak to 120.0
            )
        )

        assertEquals(-0.25, drawdown, 1e-9)
    }

    @Test
    fun `max drawdown contract keeps worst observed drawdown`() {
        val prevWorst = SVar(DReal, "prevWorst")
        val close = SVar(DReal, "close")
        val peak = SVar(DReal, "peak")
        val currentDrawdown = (close - peak) / peak
        val maxDrawdownExpr = (prevWorst `minOf` currentDrawdown).compile()

        val newWorse = maxDrawdownExpr.evalDouble(
            mapOf(
                prevWorst to -0.10,
                close to 90.0,
                peak to 120.0
            )
        )
        val noNewWorse = maxDrawdownExpr.evalDouble(
            mapOf(
                prevWorst to -0.10,
                close to 118.0,
                peak to 120.0
            )
        )

        assertEquals(-0.25, newWorse, 1e-9)
        assertEquals(-0.10, noNewWorse, 1e-9)
    }

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
