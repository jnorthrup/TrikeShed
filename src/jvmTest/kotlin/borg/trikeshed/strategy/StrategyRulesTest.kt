/**
 * Tests for StrategyRules DSEL contracts.
 */
package borg.trikeshed.strategy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StrategyRulesTest {

    @Test
    fun testRoiRuleConservative() {
        val roi = RoiRule.conservative()

        // Should exit immediately with 2% profit
        assertTrue(roi.shouldExit(0.02, 0))

        // Should exit with 1.5% profit at 15 minutes (threshold is 1%)
        assertTrue(roi.shouldExit(0.015, 15))

        // Should NOT exit with 0.3% profit at 15 minutes
        assertFalse(roi.shouldExit(0.003, 15))

        // Should exit with 0.6% profit at 45 minutes (threshold is 0.5%)
        assertTrue(roi.shouldExit(0.006, 45))
    }

    @Test
    fun testRoiRuleAggressive() {
        val roi = RoiRule.aggressive()

        // Should exit with 5% profit immediately
        assertTrue(roi.shouldExit(0.05, 0))

        // Should NOT exit with 2% profit at 0 minutes
        assertFalse(roi.shouldExit(0.02, 0))
    }

    @Test
    fun testRoiRuleScalp() {
        val roi = RoiRule.scalp()

        // Quick scalp: 1% target
        assertTrue(roi.shouldExit(0.01, 0))
        assertTrue(roi.shouldExit(0.005, 5)) // After 5 min, threshold is 0.5%
    }

    @Test
    fun testStoplossRuleTight() {
        val stoploss = StoplossRule.tight()

        // Should exit at -2% loss
        assertTrue(stoploss.shouldExit(-0.02))

        // Should NOT exit at -1.5% loss
        assertFalse(stoploss.shouldExit(-0.015))

        // Should exit at -3% loss
        assertTrue(stoploss.shouldExit(-0.03))
    }

    @Test
    fun testStoplossRuleTrailing() {
        val stoploss = StoplossRule.trailing(0.01)

        // No profit yet, should NOT exit at -1% (trailing stop not activated)
        assertFalse(stoploss.shouldExit(-0.01, 0.0))

        // Made 5% profit, now down to 3% profit
        // Trailing stop = 0.05 - 0.01 = 0.04, current is 0.03, should exit
        assertTrue(stoploss.shouldExit(0.03, 0.05))

        // Made 10% profit, now down to 8% profit
        // Trailing stop = 0.10 - 0.01 = 0.09, current is 0.08, should exit
        assertTrue(stoploss.shouldExit(0.08, 0.10))
    }

    @Test
    fun testStoplossRuleStandard() {
        val stoploss = StoplossRule.standard()

        // Should exit at -5%
        assertTrue(stoploss.shouldExit(-0.05))

        // Should NOT exit at -4%
        assertFalse(stoploss.shouldExit(-0.04))
    }

    @Test
    fun testTimeExitRule() {
        val dayTrade = TimeExitRule.dayTrade()

        assertFalse(dayTrade.shouldExit(100))
        assertTrue(dayTrade.shouldExit(480))
        assertTrue(dayTrade.shouldExit(500))

        val scalp = TimeExitRule.scalp()
        assertFalse(scalp.shouldExit(20))
        assertTrue(scalp.shouldExit(30))
    }

    @Test
    fun testStrategyRulesBalanced() {
        val rules = StrategyRules.balanced()

        // HOLD: no conditions met
        var decision = rules.evaluate(0.005, 10)
        assertEquals(Decision.Action.HOLD, decision.action)

        // SELL: stoploss triggered
        decision = rules.evaluate(-0.05, 10)
        assertEquals(Decision.Action.SELL, decision.action)
        assertTrue(decision.reason?.contains("Stoploss") == true)

        // SELL: ROI triggered (1% profit at 15 min)
        decision = rules.evaluate(0.015, 15)
        assertEquals(Decision.Action.SELL, decision.action)
        assertTrue(decision.reason?.contains("ROI") == true)

        // SELL: time exit triggered
        decision = rules.evaluate(0.0, 500)
        assertEquals(Decision.Action.SELL, decision.action)
        assertTrue(decision.reason?.contains("Time exit") == true)
    }

    @Test
    fun testStrategyRulesAggressive() {
        val rules = StrategyRules.aggressive()

        // SELL: tight stoploss at -2%
        val decision = rules.evaluate(-0.02, 10)
        assertEquals(Decision.Action.SELL, decision.action)
        assertTrue(decision.reason?.contains("Stoploss") == true)
    }

    @Test
    fun testStrategyRulesScalp() {
        val rules = StrategyRules.scalp()

        // SELL: quick ROI at 1%
        val decision = rules.evaluate(0.01, 0)
        assertEquals(Decision.Action.SELL, decision.action)
        assertTrue(decision.reason?.contains("ROI") == true)
    }

    @Test
    fun testStrategyRulesStoplossPriority() {
        // Stoploss should be checked first (risk management)
        val rules = strategy {
            withRoi(RoiRule.aggressive())
            withStoploss(StoplossRule.standard())
            describedAs("Test Strategy")
        }

        // Both ROI and stoploss triggered, but stoploss should be reported
        val decision = rules.evaluate(-0.05, 0)
        assertEquals(Decision.Action.SELL, decision.action)
        assertTrue(decision.reason?.contains("Stoploss") == true)
    }

    @Test
    fun testStrategyBuilderDsl() {
        val rules = strategy {
            withRoi(RoiRule.conservative())
            withStoploss(StoplossRule.tight())
            withTimeExit(TimeExitRule.swing())
            describedAs("Custom DSL Strategy")
        }

        assertEquals("Custom DSL Strategy", rules.description)

        // Test the rules work
        val decision = rules.evaluate(-0.02, 10)
        assertEquals(Decision.Action.SELL, decision.action)
    }

    @Test
    fun testInfixStrategyBuilding() {
        val (roi, stoploss) = RoiRule.conservative() and StoplossRule.standard()
        val strategy = (roi and stoploss and TimeExitRule.dayTrade()).toStrategy()

        assertEquals("Composite Strategy", strategy.description)

        val decision = strategy.evaluate(0.02, 5)
        assertEquals(Decision.Action.SELL, decision.action)
        assertTrue(decision.reason?.contains("ROI") == true)
    }

    @Test
    fun testDecisionStrength() {
        val rules = StrategyRules.balanced()

        // Stoploss should have highest strength
        val stoplossDecision = rules.evaluate(-0.05, 10)
        assertEquals(1.0, stoplossDecision.strength)

        // Time exit should have medium strength
        val timeDecision = rules.evaluate(0.0, 500)
        assertEquals(0.8, timeDecision.strength)

        // ROI should have high strength
        val roiDecision = rules.evaluate(0.02, 0)
        assertEquals(0.9, roiDecision.strength)
    }

    @Test
    fun testEdgeCases() {
        // Empty strategy (no rules)
        val emptyRules = StrategyRules()
        val decision = emptyRules.evaluate(0.5, 1000)
        assertEquals(Decision.Action.HOLD, decision.action)

        // Only ROI rule
        val roiOnly = StrategyRules(roi = RoiRule.scalp())
        assertTrue(roiOnly.evaluate(0.01, 0).action == Decision.Action.SELL)
        assertEquals(Decision.Action.HOLD, roiOnly.evaluate(-0.5, 0).action)

        // Only stoploss rule
        val stoplossOnly = StrategyRules(stoploss = StoplossRule.tight())
        assertTrue(stoplossOnly.evaluate(-0.02, 0).action == Decision.Action.SELL)
        assertEquals(Decision.Action.HOLD, stoplossOnly.evaluate(0.5, 0).action)
    }
}
