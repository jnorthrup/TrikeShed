/**
 * Tests for SignalGenerator.
 */
package borg.trikeshed.signal

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class SignalGeneratorTest {

    @Test
    fun testSignalGeneration() {
        // Create simple price series
        val close = 100 j { i: Int -> 100.0 + i * 0.1 }
        val high = 100 j { i: Int -> 100.0 + i * 0.1 + 0.5 }
        val low = 100 j { i: Int -> 100.0 + i * 0.1 - 0.5 }
        val volume = 100 j { _: Int -> 1000.0 }

        val generator = SignalGenerator(SignalConfig.balanced())
        val signals = generator.generate(close, high, low, volume)

        assertEquals(close.size, signals.size)
    }

    @Test
    fun testNoSignalsForShortSeries() {
        // Series too short for indicators
        val close = 20 j { i: Int -> 100.0 + i }

        val generator = SignalGenerator()
        val signals = generator.generate(close)

        // First 20 should be NONE
        for (i in 0 until 20) {
            assertEquals(Signal.Action.NONE, signals[i].action, "Signal at $i should be NONE")
        }
    }

    @Test
    fun testRsiOversoldBuySignal() {
        // Create series with RSI going oversold
        // Declining prices will make RSI go down
        val prices = doubleArrayOf(
            100.0, 99.0, 98.0, 97.0, 96.0, 95.0, 94.0, 93.0, 92.0, 91.0,
            90.0, 89.0, 88.0, 87.0, 86.0, 85.0, 84.0, 83.0, 82.0, 81.0,
            80.0, 79.0, 78.0, 77.0, 76.0, 75.0, 74.0, 73.0, 72.0, 71.0,
            70.0, 69.0, 68.0, 67.0, 66.0, 65.0
        )
        val close = prices.size j { i: Int -> prices[i] }
        val high = close
        val low = close
        val volume = close.size j { _: Int -> 1000.0 }

        val config = SignalConfig(
            rsiBuyThreshold = 30.0,
            rsiSellThreshold = 70.0,
            bollingerBuyBelowLower = false,
            macdCrossOverBuy = false,
            stochBuyBelow = 10.0 // Disable stochastic
        )
        val generator = SignalGenerator(config)
        val signals = generator.generate(close, high, low, volume)

        // Check that we get some buy signals when RSI is oversold
        var hasBuySignal = false
        for (i in 30 until close.size) {
            if (signals[i].action == Signal.Action.BUY) {
                hasBuySignal = true
                assertTrue(signals[i].reason?.contains("RSI") == true || signals[i].reason == null)
            }
        }
        // With strongly declining prices, we should see buy signals
        assertTrue(hasBuySignal, "Should have at least one buy signal with oversold RSI")
    }

    @Test
    fun testRsiOverboughtSellSignal() {
        // Create series with RSI going overbought
        // Rising prices will make RSI go up
        val prices = doubleArrayOf(
            100.0, 101.0, 102.0, 103.0, 104.0, 105.0, 106.0, 107.0, 108.0, 109.0,
            110.0, 111.0, 112.0, 113.0, 114.0, 115.0, 116.0, 117.0, 118.0, 119.0,
            120.0, 121.0, 122.0, 123.0, 124.0, 125.0, 126.0, 127.0, 128.0, 129.0,
            130.0, 131.0, 132.0, 133.0, 134.0, 135.0
        )
        val close = prices.size j { i: Int -> prices[i] }

        val config = SignalConfig(
            rsiBuyThreshold = 30.0,
            rsiSellThreshold = 70.0,
            bollingerSellAboveUpper = false,
            macdCrossUnderSell = false,
            stochSellAbove = 95.0 // Disable stochastic
        )
        val generator = SignalGenerator(config)
        val signals = generator.generate(close)

        // Check that we get some sell signals when RSI is overbought
        var hasSellSignal = false
        for (i in 30 until close.size) {
            if (signals[i].action == Signal.Action.SELL) {
                hasSellSignal = true
                assertTrue(signals[i].reason?.contains("RSI") == true || signals[i].reason == null)
            }
        }
        assertTrue(hasSellSignal, "Should have at least one sell signal with overbought RSI")
    }

    @Test
    fun testBollingerBandSignals() {
        // Create series with clear Bollinger Band breakout
        // Start stable, then spike up
        val prices = doubleArrayOf(
            100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0,
            100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0,
            100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0,
            100.0, 100.0, 100.0, 100.0, 150.0, 160.0 // Spike above upper band
        )
        val close = prices.size j { i: Int -> prices[i] }

        val config = SignalConfig(
            rsiBuyThreshold = 20.0, // Disable RSI
            rsiSellThreshold = 90.0,
            bollingerDeviation = 2.0,
            bollingerSellAboveUpper = true,
            macdCrossUnderSell = false,
            stochSellAbove = 95.0
        )
        val generator = SignalGenerator(config)
        val signals = generator.generate(close)

        // Should see sell signal when price spikes above upper band
        var hasSellSignal = false
        for (i in 30 until close.size) {
            if (signals[i].action == Signal.Action.SELL) {
                hasSellSignal = true
            }
        }
        assertTrue(hasSellSignal, "Should have sell signal when price breaks above Bollinger Band")
    }

    @Test
    fun testMacdCrossoverSignals() {
        // Create series with MACD crossover
        // This is a simplified test - real MACD crossovers need more complex price patterns
        val prices = doubleArrayOf(
            100.0, 101.0, 100.5, 101.5, 100.0, 99.0, 98.0, 97.0, 96.0, 95.0,
            94.0, 93.0, 94.0, 95.0, 96.0, 97.0, 98.0, 99.0, 100.0, 101.0,
            102.0, 103.0, 104.0, 105.0, 106.0, 107.0, 108.0, 109.0, 110.0, 111.0,
            112.0, 113.0, 114.0, 115.0, 116.0, 117.0
        )
        val close = prices.size j { i: Int -> prices[i] }

        val config = SignalConfig(
            rsiBuyThreshold = 20.0,
            rsiSellThreshold = 90.0,
            bollingerBuyBelowLower = false,
            bollingerSellAboveUpper = false,
            macdCrossOverBuy = true,
            macdCrossUnderSell = true,
            stochBuyBelow = 10.0,
            stochSellAbove = 95.0
        )
        val generator = SignalGenerator(config)
        val signals = generator.generate(close)

        // Should see some signals from MACD crossovers
        var hasBuyOrSell = false
        for (i in 30 until close.size) {
            if (signals[i].action != Signal.Action.NONE) {
                hasBuyOrSell = true
                break
            }
        }
        assertTrue(hasBuyOrSell, "Should have signals from MACD crossovers")
    }

    @Test
    fun testSampleStrategy() {
        val prices = doubleArrayOf(
            100.0, 101.0, 100.5, 101.5, 102.0, 101.0, 100.0, 99.0, 98.0, 97.0,
            96.0, 95.0, 94.0, 93.0, 94.0, 95.0, 96.0, 97.0, 98.0, 99.0,
            100.0, 101.0, 102.0, 103.0, 104.0, 105.0, 106.0, 107.0, 108.0, 109.0,
            110.0, 111.0, 112.0, 113.0, 114.0, 115.0
        )
        val close = prices.size j { i: Int -> prices[i] }
        val high = close
        val low = close
        val volume = close.size j { _: Int -> 1000.0 }

        val signals = SampleStrategy.generateSignals(close, high, low, volume)

        assertEquals(close.size, signals.size)

        // Test convenience methods
        val buyCount = signals.buyCount
        val sellCount = signals.sellCount

        assertTrue(buyCount >= 0)
        assertTrue(sellCount >= 0)
    }

    @Test
    fun testSignalStrength() {
        val close = 100 j { i: Int -> 100.0 + i * 0.5 }
        val signals = SignalGenerator().generate(close)

        // Check that signals have valid strength values
        for (i in 0 until signals.size) {
            val signal = signals[i]
            assertTrue(signal.strength in 0.0..1.0, "Signal strength should be between 0 and 1")
        }
    }

    @Test
    fun testSignalIndicators() {
        val close = 100 j { i: Int -> 100.0 + i * 0.5 }
        val signals = SignalGenerator().generate(close)

        // Non-NONE signals should have indicator data
        for (i in 0 until signals.size) {
            val signal = signals[i]
            if (signal.action != Signal.Action.NONE) {
                assertTrue(signal.indicators.isNotEmpty(), "Signal should have indicator data")
                assertTrue(signal.indicators.containsKey("RSI"))
                assertTrue(signal.indicators.containsKey("ADX"))
                assertTrue(signal.indicators.containsKey("StochK"))
            }
        }
    }

    @Test
    fun testConservativeConfig() {
        val config = SignalConfig.conservative()

        assertEquals(25.0, config.rsiBuyThreshold)
        assertEquals(75.0, config.rsiSellThreshold)
        assertEquals(2.5, config.bollingerDeviation)
        assertEquals(30.0, config.adxMinTrend)
        assertTrue(config.requireVolumeConfirmation)
    }

    @Test
    fun testAggressiveConfig() {
        val config = SignalConfig.aggressive()

        assertEquals(35.0, config.rsiBuyThreshold)
        assertEquals(65.0, config.rsiSellThreshold)
        assertEquals(1.5, config.bollingerDeviation)
        assertEquals(20.0, config.adxMinTrend)
    }

    @Test
    fun testExtensionFunctions() {
        val close = 100 j { i: Int -> 100.0 + i }

        // Test extension function
        val signals = close.generateSignals()
        assertEquals(close.size, signals.size)

        // Test buySignals and sellSignals filtering
        val buyOnly = signals.buySignals()
        val sellOnly = signals.sellSignals()

        for (i in 0 until buyOnly.size) {
            if (buyOnly[i].action != Signal.Action.NONE) {
                assertEquals(Signal.Action.BUY, buyOnly[i].action)
            }
        }

        for (i in 0 until sellOnly.size) {
            if (sellOnly[i].action != Signal.Action.NONE) {
                assertEquals(Signal.Action.SELL, sellOnly[i].action)
            }
        }
    }

    @Test
    fun testSignalCompanionMethods() {
        val buySignal = Signal.buy(
            strength = 0.8,
            reason = "Test buy",
            "RSI" to 25.0,
            "ADX" to 30.0
        )

        assertEquals(Signal.Action.BUY, buySignal.action)
        assertEquals(0.8, buySignal.strength)
        assertEquals("Test buy", buySignal.reason)
        assertEquals(25.0, buySignal.indicators["RSI"])

        val sellSignal = Signal.sell(
            strength = 0.9,
            reason = "Test sell"
        )

        assertEquals(Signal.Action.SELL, sellSignal.action)
        assertEquals(0.9, sellSignal.strength)
        assertEquals("Test sell", sellSignal.reason)
    }

    @Test
    fun testVolumeConfirmation() {
        // Create prices with a potential buy signal
        val prices = doubleArrayOf(
            100.0, 99.0, 98.0, 97.0, 96.0, 95.0, 94.0, 93.0, 92.0, 91.0,
            90.0, 89.0, 88.0, 87.0, 86.0, 85.0, 84.0, 83.0, 82.0, 81.0,
            80.0, 79.0, 78.0, 77.0, 76.0, 75.0, 74.0, 73.0, 72.0, 71.0,
            70.0, 69.0, 68.0, 67.0, 66.0, 65.0
        )
        val close = prices.size j { i: Int -> prices[i] }

        // Low volume
        val lowVolume = close.size j { _: Int -> 100.0 }
        // High volume
        val highVolume = close.size j { i: Int -> if (i > 30) 10000.0 else 100.0 }

        val config = SignalConfig(
            requireVolumeConfirmation = true,
            rsiBuyThreshold = 30.0
        )
        val generator = SignalGenerator(config)

        val signalsLowVol = generator.generate(close, volume = lowVolume)
        val signalsHighVol = generator.generate(close, volume = highVolume)

        // With volume confirmation required, high volume should produce more signals
        val lowVolBuys = signalsLowVol.buyCount
        val highVolBuys = signalsHighVol.buyCount

        assertTrue(highVolBuys >= lowVolBuys, "High volume should produce at least as many buy signals")
    }
}
