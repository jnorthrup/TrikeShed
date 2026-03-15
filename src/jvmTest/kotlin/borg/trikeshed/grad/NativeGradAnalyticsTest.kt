package borg.trikeshed.grad

import borg.trikeshed.duck.*
import borg.trikeshed.lib.*
import kotlin.test.*

class NativeGradAnalyticsTest {
    @Test
    fun testGradOfXTimesX() {
        // Test grad of x*x at 3.0 == 6.0
        val result = grad(3.0) { x -> x * x }
        assertEquals(6.0, result, 1e-10)
    }

    @Test
    fun testDrawdownGradientSign() {
        // Test drawdown gradient sign with weights=[1.0, 0.9, 0.8]
        val wArr = listOf(1.0, 0.9, 0.8)
        val weights = wArr.size j { i: Int -> wArr[i] }

        val drawdown = drawdown(weights)

        // drawdown.v should be in (0.0..1.0)
        assertTrue(drawdown.v in 0.0..1.0, "Drawdown value should be between 0 and 1")

        // drawdown.dv should not be 0.0 (gradient exists)
        assertTrue(drawdown.dv != 0.0, "Drawdown gradient should not be zero")
    }

    @Test
    fun testEmaFoldSpan2() {
        // Test EMA fold with span=2 on [1.0,2.0,3.0,4.0,5.0]
        val vArr = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val values = vArr.size j { i: Int -> vArr[i] as Any? }

        val result = emaFold(values, 2)

        // Reference calculation: alpha=2.0/3, ema starts at 1.0
        // Step 1: ema = (2.0/3)*2.0 + (1.0/3)*1.0 = 1.6667
        // Step 2: ema = (2.0/3)*3.0 + (1.0/3)*1.6667 = 2.5556
        // Step 3: ema = (2.0/3)*4.0 + (1.0/3)*2.5556 = 3.5185
        // Step 4: ema = (2.0/3)*5.0 + (1.0/3)*3.5185 = 4.5062
        val expected = 4.506172839506173

        assertEquals(expected, result, 0.001, "EMA calculation should match reference")
    }

    @Test
    fun testSoftPnlFold() {
        // Test softPnlFold([1.0,2.0,3.0]) returns Dual with dv==1.0
        val pArr = listOf(1.0, 2.0, 3.0)
        val pnl = pArr.size j { i: Int -> pArr[i] as Any? }

        val result = softPnlFold(pnl)

        // Should sum to 6.0 (1+2+3)
        assertEquals(6.0, result.v, 1e-10)

        // Last element gradient should be 1.0
        assertEquals(1.0, result.dv, 1e-10)
    }
}
