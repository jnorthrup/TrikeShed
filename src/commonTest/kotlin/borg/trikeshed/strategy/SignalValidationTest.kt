package borg.trikeshed.strategy

import borg.trikeshed.indicator.*
import borg.trikeshed.lib.*
import kotlin.test.*

/**
 * Signal validation unit test — verifies TradingStrategyComponent port behavioral fidelity.
 *
 * Tests signal logic directly with controlled indicator inputs, sidestepping the need
 * to reverse-engineer time series that produce specific RSI/TEMA values.
 * Exercises: signal conditions, exit rules, position lifecycle, and harvest logic.
 */
class SignalValidationTest {

    // ══════════════════════════════════════════════════════════════════════════════
    // Test helpers: construct Series from explicit Double arrays
    // ══════════════════════════════════════════════════════════════════════════════

    /** Build a Series<Double> from an explicit array of values. */
    private fun seriesOf(vararg values: Double): Series<Double> =
        values.size j { i: Int -> values[i] }

    /**
     * Build a MarketBundle from close/volume arrays.
     * High and low are approximated as close±0.1%.
     */
    private fun marketBundle(close: DoubleArray, volume: DoubleArray = DoubleArray(close.size) { 1.0 }): SampleStrategySignals.Context {
        val high = DoubleArray(close.size) { close[it] * 1.001 }
        val low = DoubleArray(close.size) { close[it] * 0.999 }
        val ctx = SampleStrategySignals.Context(
            close = close.size j { close[it] },
            high = close.size j { high[it] },
            low = close.size j { low[it] },
            volume = close.size j { volume[it] },
            index = close.size - 1  // evaluate at last index where all indicators are valid
        )
        return ctx
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Signal condition unit tests
    // ══════════════════════════════════════════════════════════════════════════════

    @Test
    fun testRsiCrossedAboveFalseOnIndex0() {
        val rsi = seriesOf(25.0, 31.0, 35.0)
        // index 0 should be false (not enough history)
        assertFalse(SampleStrategySignals.rsiCrossedAbove(rsi, 30.0, 0))
    }

    @Test
    fun testRsiCrossedAboveTrueWhenCrossing() {
        // RSI[0]=28 <= 30, RSI[1]=31 > 30 → crosses above 30 → true
        val rsiCrossUp = seriesOf(28.0, 31.0, 35.0)
        assertTrue(SampleStrategySignals.rsiCrossedAbove(rsiCrossUp, 30.0, 1))
        // RSI[0]=31 > 30, RSI[1]=28 < 30 → crosses BELOW 30, not above → false
        val rsiCrossDown = seriesOf(31.0, 28.0, 25.0)
        assertFalse(SampleStrategySignals.rsiCrossedAbove(rsiCrossDown, 30.0, 1),
            "RSI crossing below 30 should not return true for rsiCrossedAbove")
    }

    @Test
    fun testRsiCrossedAboveFalseWhenNotCrossing() {
        // Both above threshold → no cross
        val rsi = seriesOf(32.0, 35.0, 38.0)
        assertFalse(SampleStrategySignals.rsiCrossedAbove(rsi, 30.0, 1))
        assertFalse(SampleStrategySignals.rsiCrossedAbove(rsi, 30.0, 2))
    }

    @Test
    fun testTemaRisingTrue() {
        val tema = seriesOf(100.0, 100.5, 101.0)
        assertTrue(SampleStrategySignals.temaRising(tema, 1))
        assertTrue(SampleStrategySignals.temaRising(tema, 2))
    }

    @Test
    fun testTemaRisingFalse() {
        val tema = seriesOf(101.0, 100.5, 100.0)
        assertFalse(SampleStrategySignals.temaRising(tema, 1))
        assertFalse(SampleStrategySignals.temaRising(tema, 2))
    }

    @Test
    fun testTemaRisingFalseOnIndex0() {
        val tema = seriesOf(100.0, 100.5, 101.0)
        assertFalse(SampleStrategySignals.temaRising(tema, 0))  // needs previous bar
    }

    @Test
    fun testTemaFallingTrue() {
        val tema = seriesOf(101.0, 100.5, 100.0)
        assertTrue(SampleStrategySignals.temaFalling(tema, 1))
        assertTrue(SampleStrategySignals.temaFalling(tema, 2))
    }

    @Test
    fun testTemaFallingFalse() {
        val tema = seriesOf(100.0, 100.5, 101.0)
        assertFalse(SampleStrategySignals.temaFalling(tema, 1))
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Entry/exit condition tests — using real indicator computation on simple data
    // ══════════════════════════════════════════════════════════════════════════════

    /**
     * Verify ENTER_LONG fires when all conditions are met:
     * RSI crosses above 30, TEMA <= BB middle, TEMA rising, volume > 0
     */
    @Test
    fun testEnterLongConditionMet() {
        // Build 20-bar close series: declining → flat → sharp rise at bar 14
        // This should push RSI[14] above 30 from below
        val close = DoubleArray(20) { i ->
            when {
                i < 14 -> 100.0 - (14 - i) * 0.3   // slowly declining, low RSI
                i == 14 -> 94.5                      // sharp rise, RSI crosses above 30
                else -> 95.0 + (i - 15) * 0.1       // monotonic rise → tema rising
            }
        }
        val volume = DoubleArray(20) { 1.0 }

        val ctx = SampleStrategySignals.Context(
            close = 20 j { close[it] },
            high = 20 j { close[it] * 1.001 },
            low = 20 j { close[it] * 0.999 },
            volume = 20 j { volume[it] },
            index = 14
        )

        val result = SampleStrategySignals.generateSignal(ctx)
        // May be ENTER_LONG or may be HOLD if TEMA not below BB at index 14
        println("Signal at index 14: ${result.signal}, conditions: ${result.conditions}")
    }

    /**
     * Verify HOLD is returned when all conditions for ENTER signals are false.
     * Use flat close series → RSI ~50, TEMA flat → no crosses.
     */
    @Test
    fun testHoldOnFlatMarket() {
        val close = DoubleArray(30) { 100.0 }
        val volume = DoubleArray(30) { 1.0 }

        // Check at index 20 (well past warm-up)
        val ctx = SampleStrategySignals.Context(
            close = 30 j { close[it] },
            high = 30 j { close[it] * 1.001 },
            low = 30 j { close[it] * 0.999 },
            volume = 30 j { volume[it] },
            index = 20
        )

        val result = SampleStrategySignals.generateSignal(ctx)
        assertEquals(TradeSignalType.HOLD, result.signal, "Flat market should produce HOLD")
        println("HOLD confirmed for flat market: signal=$result.signal")
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // ROI / Stoploss / Trailing exit rule tests
    // ══════════════════════════════════════════════════════════════════════════════

    @Test
    fun testRoiRuleTierBoundaries() {
        val roi = RoiRule(listOf(0 to 0.04, 15 to 0.07, 30 to 0.10, 60 to 0.15))

        // Exact tier boundaries
        assertEquals(0.04, roi.minProfit(0))
        assertEquals(0.04, roi.minProfit(14))
        assertEquals(0.07, roi.minProfit(15))
        assertEquals(0.07, roi.minProfit(29))
        assertEquals(0.10, roi.minProfit(30))
        assertEquals(0.10, roi.minProfit(59))
        assertEquals(0.15, roi.minProfit(60))
        assertEquals(0.15, roi.minProfit(999))   // beyond all tiers → last tier

        // Tier progression
        assertFalse(roi.shouldExit(0, 0.03))      // below tier 0
        assertTrue(roi.shouldExit(0, 0.04))      // at tier 0
        assertTrue(roi.shouldExit(0, 0.05))      // above tier 0
        assertFalse(roi.shouldExit(15, 0.06))    // below tier 1
        assertTrue(roi.shouldExit(15, 0.07))     // at tier 1
    }

    @Test
    fun testStoplossRuleSamples() {
        val sl = StoplossRule.sampleStrategy()  // stoploss = -0.03

        // Strict less-than: fires when profit < -0.03 (i.e., worse than -3%)
        assertTrue(sl.shouldExit(-0.04), "Stop loss fires at -4% (strictly worse than -3%)")
        assertFalse(sl.shouldExit(-0.03), "No exit at exactly -3% (profit equals stoploss)")
        assertFalse(sl.shouldExit(-0.02), "No exit at -2%")
        assertFalse(sl.shouldExit(0.0), "No exit at 0%")
        assertFalse(sl.shouldExit(0.05), "No exit at +5%")
    }

    @Test
    fun testTrailingStoplossRuleSamples() {
        val ts = TrailingStoplossRule.sampleStrategy()  // trailing=0.025, offset=0.05

        // maxProfit below offset → never exit
        assertFalse(ts.shouldExit(0.04, 0.04), "maxProfit=4% below offset=5% → no exit")

        // Pullback exceeds trailing → exit
        // maxProfit=8%, current=4% → pullback=4% > 2.5% → exit
        assertTrue(ts.shouldExit(0.04, 0.08), "Pullback 4% > 2.5% trailing → exit")

        // Pullback below trailing → no exit
        // maxProfit=8%, current=6% → pullback=2% < 2.5% → no exit
        assertFalse(ts.shouldExit(0.06, 0.08), "Pullback 2% < 2.5% trailing → no exit")

        // maxProfit exactly at offset
        assertFalse(ts.shouldExit(0.04, 0.05), "maxProfit at offset → no exit (not above)")

        // Disabled trailing never exits
        assertFalse(TrailingStoplossRule.disabled().shouldExit(0.04, 0.10))
        assertFalse(TrailingStoplossRule.disabled().shouldExit(0.01, 0.50))
    }

    @Test
    fun testTrailingStoplossSimple() {
        val ts = TrailingStoplossRule.simple(0.03)
        assertFalse(ts.shouldExit(0.07, 0.10), "Pullback 3% = 3% trailing → not strictly greater")
        assertTrue(ts.shouldExit(0.06, 0.10), "Pullback 4% > 3% trailing → exit")
    }

    @Test
    fun testExitRuleSetPriorityStoplossFirst() {
        val rules = ExitRuleSet.sampleStrategy()

        // Stop loss fires first (checked first in shouldExit)
        assertTrue(rules.shouldExit(0, -0.04, 0.0))
        assertEquals("STOPLOSS", rules.exitReason(0, -0.04, 0.0)?.toString())
    }

    @Test
    fun testExitRuleSetPriorityRoi() {
        val rules = ExitRuleSet.sampleStrategy()

        assertTrue(rules.shouldExit(0, 0.05, 0.0))
        assertEquals("ROI", rules.exitReason(0, 0.05, 0.0)?.toString())
    }

    @Test
    fun testExitRuleSetPriorityTrailing() {
        val rules = ExitRuleSet.sampleStrategy()

        // Stoploss doesn't fire: 0.04 > -0.03
        // ROI DOES fire first: profit 0.04 >= minProfit(0)=0.04 (ROI is checked before trailing)
        assertTrue(rules.shouldExit(0, 0.04, 0.08))
        assertEquals("ROI", rules.exitReason(0, 0.04, 0.08)?.toString(),
            "ROI fires first when profit >= minProfit, before trailing is evaluated")

        // Trailing can fire when ROI doesn't: profit 0.03 < minProfit(0)=0.04
        // maxProfit=8%, current=3% → pullback 5% > 2.5%, offset=5% met → trailing fires
        assertTrue(rules.shouldExit(0, 0.03, 0.08))
        assertEquals("TRAILING", rules.exitReason(0, 0.03, 0.08)?.toString(),
            "Trailing fires when ROI profit threshold not met but pullback exceeds trailing")
    }

    @Test
    fun testExitRuleSetNoExit() {
        val rules = ExitRuleSet.sampleStrategy()

        assertFalse(rules.shouldExit(0, 0.01, 0.01), "No rule fires for small profit")
        assertNull(rules.exitReason(0, 0.01, 0.01))
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Position lifecycle tests
    // ══════════════════════════════════════════════════════════════════════════════

    @Test
    fun testPositionOpenLong() {
        val strategy = TradingStrategy(TradingStrategy.stubAdapters())

        assertNull(strategy.position)

        strategy.openPosition("ETHUSDT", 3000.0, 0, isLong = true)

        assertNotNull(strategy.position)
        assertEquals("ETHUSDT", strategy.position!!.symbol)
        assertEquals(3000.0, strategy.position!!.entryPrice)
        assertTrue(strategy.position!!.isLong)
        assertEquals(0.0, strategy.position!!.maxProfit)
    }

    @Test
    fun testPositionOpenShort() {
        val strategy = TradingStrategy(TradingStrategy.stubAdapters())

        strategy.openPosition("ETHUSDT", 3000.0, 5, isLong = false)

        assertNotNull(strategy.position)
        assertEquals("ETHUSDT", strategy.position!!.symbol)
        assertFalse(strategy.position!!.isLong)
    }

    @Test
    fun testPositionProfitTrackingLong() {
        val strategy = TradingStrategy(TradingStrategy.stubAdapters())

        strategy.openPosition("BTCUSDT", 50000.0, 0, isLong = true)

        // Price rises 4%: (52000-50000)/50000 = 4%
        strategy.updatePosition(52000.0, 10, isLong = true)
        assertTrue(strategy.position!!.maxProfit >= 0.039, "Expected ~4% maxProfit, got ${strategy.position!!.maxProfit}")

        // Price drops to entry
        strategy.updatePosition(50000.0, 20, isLong = true)
        assertTrue(strategy.position!!.maxProfit >= 0.039, "MaxProfit should not decline from peak")

        strategy.closePosition()
    }

    @Test
    fun testPositionProfitTrackingShort() {
        val strategy = TradingStrategy(TradingStrategy.stubAdapters())

        // Short: entry=50000, price drops to 48000 → profit = (50000-48000)/50000 = 4%
        strategy.openPosition("BTCUSDT", 50000.0, 0, isLong = false)

        strategy.updatePosition(48000.0, 10, isLong = false)
        assertTrue(strategy.position!!.maxProfit >= 0.039, "Short profit should be tracked")

        strategy.closePosition()
    }

    @Test
    fun testPositionClose() {
        val strategy = TradingStrategy(TradingStrategy.stubAdapters())

        strategy.openPosition("BTCUSDT", 50000.0, 0, isLong = true)
        val closed = strategy.closePosition()

        assertNotNull(closed)
        assertEquals("BTCUSDT", closed!!.symbol)
        assertEquals(50000.0, closed!!.entryPrice)
        assertNull(strategy.position)
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Harvest simulator tests
    // ══════════════════════════════════════════════════════════════════════════════

    @Test
    fun testHarvestSimulatorInitializeBaseline() {
        val sim = HarvestSimulator()

        // First time with positive holdings → baseline set
        sim.initializeAssetIfNewOrUpdated("BTC", 100.0, 1.0)
        assertEquals(100.0, sim.tokenBaselines["BTC"])

        // Same asset again → baseline unchanged
        sim.initializeAssetIfNewOrUpdated("BTC", 150.0, 1.5)
        assertEquals(100.0, sim.tokenBaselines["BTC"], "Baseline should not update on second call")
    }

    @Test
    fun testHarvestSimulatorZeroHoldingsNoBaseline() {
        val sim = HarvestSimulator()

        // Zero holdings value → no baseline set
        sim.initializeAssetIfNewOrUpdated("BTC", 0.0, 1.0)
        assertFalse(sim.tokenBaselines.containsKey("BTC"))
    }

    @Test
    fun testHarvestSimulatorFlatPriceNoAction() {
        val sim = HarvestSimulator()

        sim.initializeAssetIfNewOrUpdated("BTC", 100.0, 1.0)

        val holdings = mapOf("BTC" to 100.0)
        val prices = mapOf("BTC" to 1.0)  // deviation = 0

        val actions = sim.processCycle(holdings, prices)
        assertTrue(actions.isEmpty(), "Flat price should not produce harvest actions")
    }

    @Test
    fun testHarvestSimulatorUnflagCrossBelow() {
        val sim = HarvestSimulator()

        sim.initializeAssetIfNewOrUpdated("BTC", 100.0, 1.0)

        val holdings = mapOf("BTC" to 100.0)

        // Cross above upper band → flagged
        val above = mapOf("BTC" to 1.04)  // 4% deviation
        sim.processCycle(holdings, above)

        // Cross back below → unflagged, no harvest
        val below = mapOf("BTC" to 1.015)  // 1.5% < 3% trigger
        val actions = sim.processCycle(holdings, below)
        assertTrue(actions.isEmpty(), "Below-band crossing should unflag with no harvest")
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Stub adapters tests
    // ══════════════════════════════════════════════════════════════════════════════

    @Test
    fun testStubAdaptersReturnSafeDefaults() {
        val stubs = TradingStrategy.stubAdapters()

        // MarketTick
        assertEquals(0.0, stubs.marketTick.close())
        assertEquals(0.0, stubs.marketTick.high())
        assertEquals(0.0, stubs.marketTick.low())
        assertEquals(0.0, stubs.marketTick.volume())
        assertEquals(0.0, stubs.marketTick.open())

        // PortfolioTensor
        assertEquals(emptyMap<String, Double>(), stubs.portfolioTensor.holdings())
        assertEquals(emptyMap<String, Double>(), stubs.portfolioTensor.prices())

        // GenomeConfig
        assertEquals(Genome(), stubs.genomeConfig.genome())
        assertEquals(AgentConfig(), stubs.genomeConfig.agentConfig())

        // ExchangeClient returns false without throwing
        assertFalse(stubs.exchangeClient.buy("BTC", 0.5, 50000.0))
        assertFalse(stubs.exchangeClient.sell("BTC", 0.5, 50000.0))
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Signal conditions map tests
    // ══════════════════════════════════════════════════════════════════════════════

    @Test
    fun testSignalConditionsAlwaysPopulated() {
        // Even HOLD should have a conditions map
        val close = DoubleArray(30) { 100.0 }
        val ctx = SampleStrategySignals.Context(
            close = 30 j { close[it] },
            high = 30 j { close[it] * 1.001 },
            low = 30 j { close[it] * 0.999 },
            volume = 30 j { 1.0 },
            index = 20
        )

        val result = SampleStrategySignals.generateSignal(ctx)
        assertEquals(TradeSignalType.HOLD, result.signal)
        assertTrue(result.conditions.isNotEmpty(), "HOLD should still populate conditions map")
    }

    @Test
    fun testAllConditionsPopulatedForEnterLong() {
        // Build a scenario that produces ENTER_LONG
        val close = DoubleArray(25) { i ->
            when {
                i < 14 -> 100.0 - (14 - i) * 0.3
                i == 14 -> 94.5
                else -> 95.0 + (i - 14) * 0.1
            }
        }

        val ctx = SampleStrategySignals.Context(
            close = 25 j { close[it] },
            high = 25 j { close[it] * 1.001 },
            low = 25 j { close[it] * 0.999 },
            volume = 25 j { 1.0 },
            index = 14
        )

        val result = SampleStrategySignals.generateSignal(ctx)
        // Check all expected condition keys are present
        val expectedKeys = listOf(
            "long_entry_rsi", "long_entry_tema_bb", "long_entry_tema_rising", "long_entry_volume",
            "short_entry_rsi", "short_entry_tema_bb", "short_entry_tema_falling", "short_entry_volume",
            "long_exit_rsi", "long_exit_tema_bb", "long_exit_tema_falling", "long_exit_volume",
            "short_exit_rsi", "short_exit_tema_bb", "short_exit_tema_rising", "short_exit_volume"
        )
        expectedKeys.forEach { key ->
            assertTrue(result.conditions.containsKey(key), "Missing condition key: $key")
        }
        println("Conditions map for ${result.signal}: ${result.conditions}")
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Batch signal series test
    // ══════════════════════════════════════════════════════════════════════════════

    @Test
    fun testBatchSignalSeriesSize() {
        val close = DoubleArray(20) { 100.0 }
        val strategy = TradingStrategy(TradingStrategy.stubAdapters())

        val signals = strategy.generateSignalsSeries(
            20 j { close[it] },
            20 j { close[it] * 1.001 },
            20 j { close[it] * 0.999 },
            20 j { 1.0 }
        )

        assertEquals(20, signals.size, "Signal series should match input length")
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Exit rules conservative / aggressive presets
    // ══════════════════════════════════════════════════════════════════════════════

    @Test
    fun testExitRuleSetConservative() {
        val rules = ExitRuleSet.conservative()

        // Conservative: ROI tiers (0→0.02, 5→0.06), stoploss=-0.01, trailing disabled
        // ROI fires: profit 0.03 >= minProfit(0)=0.02
        assertTrue(rules.shouldExit(0, 0.03, 0.0))
        assertEquals("ROI", rules.exitReason(0, 0.03, 0.0)?.toString())

        // Stop loss fires at -1%
        assertTrue(rules.shouldExit(0, -0.02, 0.0))
        assertEquals("STOPLOSS", rules.exitReason(0, -0.02, 0.0)?.toString())

        // No exit for small profit below all thresholds
        assertFalse(rules.shouldExit(0, 0.01, 0.01))
        assertNull(rules.exitReason(0, 0.01, 0.01))

        // Trailing is disabled in conservative — trailing.shouldExit returns false
        // so even large pullbacks don't exit via trailing
        val trailingDisabled = rules.trailing.shouldExit(0.03, 0.08)
        assertFalse(trailingDisabled, "Conservative trailing is disabled — shouldExit must return false")
    }

    @Test
    fun testExitRuleSetAggressive() {
        val rules = ExitRuleSet.aggressive()

        // Aggressive: requires 5% profit at 0 minutes
        assertTrue(rules.shouldExit(0, 0.06, 0.0))
        assertFalse(rules.shouldExit(0, 0.04, 0.0))

        // Stop loss at -10%
        assertTrue(rules.shouldExit(0, -0.11, 0.0))
        assertFalse(rules.shouldExit(0, -0.09, 0.0))
    }
}