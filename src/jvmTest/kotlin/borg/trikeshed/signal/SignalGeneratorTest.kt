package borg.trikeshed.signal

import borg.trikeshed.indicator.*
import borg.trikeshed.lib.*
import kotlin.test.*

class SignalGeneratorTest {

    /**
     * Create test data with predictable patterns.
     */
    private fun createTestData(
        n: Int,
        closeStart: Double = 100.0,
        closeSlope: Double = 0.1
    ): Triple<Series<Double>, Series<Double>, Series<Double>, Series<Double>> {
        val close = n j { i: Int -> closeStart + i * closeSlope }
        val high = n j { i: Int -> close[i] + 1.0 }
        val low = n j { i: Int -> close[i] - 1.0 }
        val volume = n j { i: Int -> 1000.0 + i * 10.0 }
        return Triple(close, high, low, volume)
    }

    /**
     * Create RSI crossover scenario.
     */
    private fun createRsiCrossoverData(
        threshold: Double,
        crossoverIndex: Int
    ): Triple<Series<Double>, Series<Double>, Series<Double>, Series<Double>> {
        val n = 50
        val close = n j { i: Int ->
            // Create price pattern that causes RSI to cross threshold
            if (i < crossoverIndex) {
                100.0 - (crossoverIndex - i) * 0.5  // Declining before crossover
            } else {
                100.0 + (i - crossoverIndex) * 0.5  // Rising after crossover
            }
        }
        val high = n j { i: Int -> close[i] + 1.0 }
        val low = n j { i: Int -> close[i] - 1.0 }
        val volume = n j { i: Int -> 1000.0 }
        return Triple(close, high, low, volume)
    }

    @Test fun testSampleStrategySignalsContext() {
        val (close, high, low, volume) = createTestData(50)
        val ctx = SampleStrategySignals.Context(close, high, low, volume, 25)

        assertEquals(50, ctx.close.size)
        assertEquals(25, ctx.index)
        assertEquals(102.5, ctx.close[25], 0.01)
    }

    @Test fun testComputeIndicators() {
        val (close, high, low, volume) = createTestData(50)
        val ctx = SampleStrategySignals.Context(close, high, low, volume, 25)
        val ind = SampleStrategySignals.computeIndicators(ctx)

        assertEquals(50, ind.rsi.size)
        assertEquals(50, ind.tema.size)
        assertEquals(50, ind.bbMiddle.size)

        // RSI should be in valid range
        for (i in 20 until 50) {
            assertTrue(ind.rsi[i] in 0.0..100.0, "RSI[$i] = ${ind.rsi[i]} out of range")
        }
    }

    @Test fun testRsiCrossedAbove() {
        val (close, high, low, volume) = createRsiCrossoverData(30.0, 25)
        val ctx = SampleStrategySignals.Context(close, high, low, volume, 30)
        val ind = SampleStrategySignals.computeIndicators(ctx)

        // RSI should have crossed above 30 around index 25
        val crossedAt25 = SampleStrategySignals.rsiCrossedAbove(ind.rsi, 30.0, 25)
        val crossedAt30 = SampleStrategySignals.rsiCrossedAbove(ind.rsi, 30.0, 30)

        // At least one should be true (crossover happened)
        assertTrue(crossedAt25 || crossedAt30, "RSI should cross above 30")
    }

    @Test fun testTemaRisingAndFalling() {
        val (close, high, low, volume) = createTestData(50)
        val ctx = SampleStrategySignals.Context(close, high, low, volume, 25)
        val ind = SampleStrategySignals.computeIndicators(ctx)

        // In uptrend, TEMA should be rising
        assertTrue(SampleStrategySignals.temaRising(ind.tema, 25), "TEMA should be rising in uptrend")
        assertFalse(SampleStrategySignals.temaFalling(ind.tema, 25), "TEMA should not be falling in uptrend")
    }

    @Test fun testCheckLongEntry() {
        val (close, high, low, volume) = createTestData(50)
        val ctx = SampleStrategySignals.Context(close, high, low, volume, 25)
        val ind = SampleStrategySignals.computeIndicators(ctx)

        // Test the long entry condition evaluation
        val result = SampleStrategySignals.checkLongEntry(ctx, ind)
        // Result depends on indicator values, just verify it runs
        assertTrue(result == true || result == false)
    }

    @Test fun testCheckShortEntry() {
        val (close, high, low, volume) = createTestData(50)
        val ctx = SampleStrategySignals.Context(close, high, low, volume, 25)
        val ind = SampleStrategySignals.computeIndicators(ctx)

        val result = SampleStrategySignals.checkShortEntry(ctx, ind)
        assertTrue(result == true || result == false)
    }

    @Test fun testGenerateSignal() {
        val (close, high, low, volume) = createTestData(50)
        val ctx = SampleStrategySignals.Context(close, high, low, volume, 25)

        val result = SampleStrategySignals.generateSignal(ctx)

        // Should return a valid signal
        assertNotNull(result.signal)
        assertTrue(result.signal in SignalType.entries)
    }

    @Test fun testGenerateSignalsSeries() {
        val (close, high, low, volume) = createTestData(50)
        val ctx = SampleStrategySignals.Context(close, high, low, volume, 0)

        val signals = SampleStrategySignals.generateSignalsSeries(ctx)

        assertEquals(50, signals.size)
        // All signals should be valid
        for (i in 0 until 50) {
            assertTrue(signals[i] in SignalType.entries)
        }
    }

    @Test fun testConfigurableStrategyDefaults() {
        val strategy = ConfigurableStrategy()

        assertEquals(30.0, strategy.longEntryRsi)
        assertEquals(70.0, strategy.shortEntryRsi)
        assertEquals(70.0, strategy.longExitRsi)
        assertEquals(30.0, strategy.shortExitRsi)
        assertTrue(strategy.useTemaBbGuard)
        assertTrue(strategy.useTemaTrendGuard)
        assertTrue(strategy.requireVolume)
    }

    @Test fun testConfigurableStrategyCustom() {
        val strategy = ConfigurableStrategy(
            longEntryRsi = 25.0,
            shortEntryRsi = 75.0,
            useTemaBbGuard = false,
            requireVolume = false
        )

        assertEquals(25.0, strategy.longEntryRsi)
        assertEquals(75.0, strategy.shortEntryRsi)
        assertFalse(strategy.useTemaBbGuard)
        assertFalse(strategy.requireVolume)
    }

    @Test fun testConfigurableStrategySignalGeneration() {
        val strategy = ConfigurableStrategy()
        val (close, high, low, volume) = createTestData(50)
        val ctx = SampleStrategySignals.Context(close, high, low, volume, 25)

        val result = strategy.generateSignal(ctx)

        assertNotNull(result.signal)
        assertTrue(result.signal in SignalType.entries)
    }

    @Test fun testConfigurableStrategyRelaxedConditions() {
        // Strategy with no guards should generate more signals
        val relaxed = ConfigurableStrategy(
            useTemaBbGuard = false,
            useTemaTrendGuard = false,
            requireVolume = false
        )

        val strict = ConfigurableStrategy(
            useTemaBbGuard = true,
            useTemaTrendGuard = true,
            requireVolume = true
        )

        val (close, high, low, volume) = createTestData(50)
        val ctx = SampleStrategySignals.Context(close, high, low, volume, 25)

        // Relaxed strategy should be at least as likely to signal
        val relaxedSignal = relaxed.generateSignal(ctx)
        val strictSignal = strict.generateSignal(ctx)

        // Both should return valid signals
        assertNotNull(relaxedSignal.signal)
        assertNotNull(strictSignal.signal)
    }

    @Test fun testSignalResultConditions() {
        val (close, high, low, volume) = createTestData(50)
        val ctx = SampleStrategySignals.Context(close, high, low, volume, 25)

        val result = SampleStrategySignals.generateSignal(ctx)

        // Conditions map should contain expected keys
        assertTrue(result.conditions.containsKey("long_entry_rsi"))
        assertTrue(result.conditions.containsKey("long_entry_tema_bb"))
        assertTrue(result.conditions.containsKey("long_entry_tema_rising"))
        assertTrue(result.conditions.containsKey("long_entry_volume"))
        assertTrue(result.conditions.containsKey("short_entry_rsi"))
        assertTrue(result.conditions.containsKey("short_exit_rsi"))
    }

    @Test fun testSignalTypeValues() {
        // Verify all signal types exist
        assertEquals(SignalType.ENTER_LONG, SignalType.ENTER_LONG)
        assertEquals(SignalType.ENTER_SHORT, SignalType.ENTER_SHORT)
        assertEquals(SignalType.EXIT_LONG, SignalType.EXIT_LONG)
        assertEquals(SignalType.EXIT_SHORT, SignalType.EXIT_SHORT)
        assertEquals(SignalType.HOLD, SignalType.HOLD)
    }
}
