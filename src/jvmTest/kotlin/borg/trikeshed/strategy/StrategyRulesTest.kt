package borg.trikeshed.strategy

import kotlin.test.*

class StrategyRulesTest {

    @Test fun testRoiRuleSampleStrategy() {
        val roi = RoiRule.sampleStrategy()

        // At 0 mins, need 4% profit
        assertEquals(0.04, roi.minProfit(0), 0.001)
        assertEquals(0.04, roi.minProfit(10), 0.001)

        // At 15 mins, need 7% profit
        assertEquals(0.07, roi.minProfit(15), 0.001)
        assertEquals(0.07, roi.minProfit(25), 0.001)

        // At 30 mins, need 10% profit
        assertEquals(0.10, roi.minProfit(30), 0.001)
        assertEquals(0.10, roi.minProfit(50), 0.001)

        // At 60+ mins, need 15% profit
        assertEquals(0.15, roi.minProfit(60), 0.001)
        assertEquals(0.15, roi.minProfit(120), 0.001)
    }

    @Test fun testRoiShouldExit() {
        val roi = RoiRule.sampleStrategy()

        // Should exit if profit meets threshold
        assertTrue(roi.shouldExit(0, 0.05))    // 5% > 4% threshold
        assertTrue(roi.shouldExit(15, 0.08))   // 8% > 7% threshold
        assertTrue(roi.shouldExit(60, 0.16))   // 16% > 15% threshold

        // Should NOT exit if profit below threshold
        assertFalse(roi.shouldExit(0, 0.03))   // 3% < 4% threshold
        assertFalse(roi.shouldExit(30, 0.09))  // 9% < 10% threshold
    }

    @Test fun testStoplossRule() {
        val stoploss = StoplossRule.sampleStrategy()

        // Should exit if loss exceeds stoploss
        assertTrue(stoploss.shouldExit(-0.05))  // -5% loss < -3% stoploss
        assertTrue(stoploss.shouldExit(-0.10))  // -10% loss < -3% stoploss

        // Should NOT exit if loss within tolerance
        assertFalse(stoploss.shouldExit(-0.02)) // -2% loss > -3% stoploss
        assertFalse(stoploss.shouldExit(0.05))  // 5% profit
    }

    @Test fun testTrailingStoplossDisabled() {
        val trailing = TrailingStoplossRule.disabled()

        // Never exits when disabled
        assertFalse(trailing.shouldExit(0.05, 0.10))
        assertFalse(trailing.shouldExit(-0.05, 0.0))
    }

    @Test fun testTrailingStoplossSimple() {
        val trailing = TrailingStoplossRule.simple(0.03)  // 3% trailing

        // Offset not required, always active
        // Max profit 10%, trailing 3% = stop at 7%
        assertTrue(trailing.shouldExit(0.06, 0.10))  // 6% < 7% trailing stop
        assertFalse(trailing.shouldExit(0.08, 0.10)) // 8% > 7% trailing stop
    }

    @Test fun testTrailingStoplossSampleStrategy() {
        val trailing = TrailingStoplossRule.sampleStrategy()

        // Offset not reached (max profit < 5%), trailing not active
        assertFalse(trailing.shouldExit(0.03, 0.04))  // max 4% < 5% offset

        // Offset reached (max profit >= 5%), trailing active
        // Max profit 10%, trailing 2.5% = stop at 7.5%
        assertTrue(trailing.shouldExit(0.06, 0.10))   // 6% < 7.5% trailing stop
        assertFalse(trailing.shouldExit(0.08, 0.10))  // 8% > 7.5% trailing stop
    }

    @Test fun testExitRuleSetSampleStrategy() {
        val rules = ExitRuleSet.sampleStrategy()

        // Stoploss triggers immediately on large loss
        assertTrue(rules.shouldExit(0, -0.05, 0.0))
        assertEquals("STOPLOSS", rules.exitReason(0, -0.05, 0.0))

        // ROI triggers on sufficient profit
        assertTrue(rules.shouldExit(60, 0.16, 0.16))
        assertEquals("ROI", rules.exitReason(60, 0.16, 0.16))

        // Trailing triggers after offset reached and profit falls
        assertTrue(rules.shouldExit(30, 0.06, 0.10))  // 6% < 7.5% trailing
        assertEquals("TRAILING", rules.exitReason(30, 0.06, 0.10))

        // No exit when all conditions not met
        assertFalse(rules.shouldExit(10, 0.03, 0.03))  // 3% profit, no offset reached
        assertNull(rules.exitReason(10, 0.03, 0.03))
    }

    @Test fun testExitRuleSetConservative() {
        val rules = ExitRuleSet.conservative()

        // Tight stoploss: -1%
        assertTrue(rules.shouldExit(0, -0.02, 0.0))

        // Quick ROI: 2% immediate
        assertTrue(rules.shouldExit(0, 0.03, 0.03))

        // No trailing
        assertFalse(rules.shouldExit(30, 0.05, 0.10))  // Would trigger trailing if enabled
    }

    @Test fun testExitRuleSetAggressive() {
        val rules = ExitRuleSet.aggressive()

        // Wide stoploss: -10%
        assertFalse(rules.shouldExit(0, -0.05, 0.0))  // -5% > -10% stoploss

        // High ROI targets
        assertFalse(rules.shouldExit(30, 0.08, 0.08))  // 8% < 10% at 30 mins

        // Trailing active at 5%
        assertTrue(rules.shouldExit(60, 0.03, 0.10))   // 3% < 5% trailing (10% - 5%)
    }

    @Test fun testEntryConditionSampleLong() {
        val entry = EntryCondition.sampleLong()

        assertEquals(30.0, entry.rsiCrossAbove)
        assertTrue(entry.temaBelowBbMiddle)
        assertTrue(entry.temaRising)
        assertTrue(entry.volumePositive)
    }

    @Test fun testEntryConditionSampleShort() {
        val entry = EntryCondition.sampleShort()

        assertEquals(70.0, entry.rsiCrossAbove)
        assertFalse(entry.temaBelowBbMiddle)  // Above for short
        assertFalse(entry.temaRising)         // Falling for short
        assertTrue(entry.volumePositive)
    }

    @Test fun testExitSignalConditionSampleLong() {
        val exit = ExitSignalCondition.sampleLong()

        assertEquals(70.0, exit.rsiCrossAbove)
        assertTrue(exit.temaAboveBbMiddle)
        assertTrue(exit.temaFalling)
        assertTrue(exit.volumePositive)
    }

    @Test fun testExitSignalConditionSampleShort() {
        val exit = ExitSignalCondition.sampleShort()

        assertEquals(30.0, exit.rsiCrossAbove)
        assertFalse(exit.temaAboveBbMiddle)  // Below for short exit
        assertFalse(exit.temaFalling)        // Rising for short exit
        assertTrue(exit.volumePositive)
    }
}
