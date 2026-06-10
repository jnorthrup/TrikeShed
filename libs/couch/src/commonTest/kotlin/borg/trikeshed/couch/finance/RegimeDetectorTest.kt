package borg.trikeshed.couch.finance

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegimeDetectorTest {
    @Test
    fun testUnknownRegimeForEmptyHistory() {
        val detector = RegimeDetector()
        val result = detector.analyze(doubleArrayOf())
        assertEquals(Regime.UNKNOWN, result)
    }

    @Test
    fun testUnknownRegimeForShortHistory() {
        val detector = RegimeDetector()
        val result = detector.analyze(doubleArrayOf(1.0, 2.0, 3.0))
        assertEquals(Regime.UNKNOWN, result)
    }

    @Test
    fun testStatsForShortHistory() {
        val detector = RegimeDetector()
        val result = detector.stats(doubleArrayOf(1.0, 2.0, 3.0))
        assertNull(result)
    }

    @Test
    fun testBullRushRegime() {
        val detector = RegimeDetector()
        // Strong upward move with volatility
        val prices = generatePriceSeries(
            start = 100.0,
            roi = 0.10, // 10% gain
            volatility = 0.03 // 3% vol
        )
        val result = detector.analyze(prices)
        assertEquals(Regime.BULL_RUSH, result)
    }

    @Test
    fun testBearCrashRegime() {
        val detector = RegimeDetector()
        // Strong downward move with volatility
        val prices = generatePriceSeries(
            start = 100.0,
            roi = -0.10, // -10% loss
            volatility = 0.03 // 3% vol
        )
        val result = detector.analyze(prices)
        assertEquals(Regime.BEAR_CRASH, result)
    }

    @Test
    fun testSteadyGrowthRegime() {
        val detector = RegimeDetector()
        // Gentle upward drift with low volatility
        val prices = generatePriceSeries(
            start = 100.0,
            roi = 0.03, // 3% gain
            volatility = 0.005 // 0.5% vol
        )
        val result = detector.analyze(prices)
        assertEquals(Regime.STEADY_GROWTH, result)
    }

    @Test
    fun testCrabChopRegime() {
        val detector = RegimeDetector()
        // Sideways movement with moderate volatility
        val prices = generatePriceSeries(
            start = 100.0,
            roi = 0.01, // 1% gain (below steady growth threshold)
            volatility = 0.015 // moderate vol
        )
        val result = detector.analyze(prices)
        assertEquals(Regime.CRAB_CHOP, result)
    }

    @Test
    fun testVolatileChopRegime() {
        val detector = RegimeDetector()
        // High volatility prices - should detect some regime (not UNKNOWN)
        val prices = doubleArrayOf(100.0, 108.0, 92.0, 110.0, 88.0, 112.0, 85.0, 115.0, 82.0, 118.0,
            100.0, 108.0, 92.0, 110.0, 88.0, 112.0, 85.0, 115.0, 82.0, 118.0,
            100.0, 108.0, 92.0, 110.0, 88.0, 112.0, 85.0, 115.0, 82.0, 118.0,
            100.0, 108.0, 92.0, 110.0, 88.0, 112.0, 85.0, 115.0, 82.0, 118.0,
            100.0, 108.0, 92.0, 110.0, 88.0, 112.0, 85.0, 115.0, 82.0, 118.0,
            100.0, 108.0, 92.0, 110.0, 88.0, 112.0, 85.0, 115.0, 82.0, 118.0,
            100.0, 108.0, 92.0, 110.0, 88.0, 112.0, 85.0, 115.0, 82.0, 118.0,
            100.0, 108.0, 92.0, 110.0, 88.0, 112.0, 85.0, 115.0, 82.0, 118.0,
            100.0, 108.0, 92.0, 110.0, 88.0, 112.0, 85.0, 115.0, 82.0, 118.0,
            100.0, 108.0, 92.0, 110.0, 88.0, 112.0, 85.0, 115.0, 82.0, 118.0)
        val result = detector.analyze(prices)
        // High volatility should not be UNKNOWN
        assertNotEquals(Regime.UNKNOWN, result)
    }

    @Test
    fun testStatsComputation() {
        val detector = RegimeDetector()
        val prices = doubleArrayOf(100.0, 102.0, 104.0, 106.0, 108.0, 110.0, 112.0, 114.0, 116.0, 118.0,
            120.0, 122.0, 124.0, 126.0, 128.0, 130.0, 132.0, 134.0, 136.0, 138.0,
            140.0, 142.0, 144.0, 146.0, 148.0, 150.0, 152.0, 154.0, 156.0, 158.0,
            160.0, 162.0, 164.0, 166.0, 168.0, 170.0, 172.0, 174.0, 176.0, 178.0,
            180.0, 182.0, 184.0, 186.0, 188.0, 190.0, 192.0, 194.0, 196.0, 198.0,
            200.0, 202.0, 204.0, 206.0, 208.0, 210.0, 212.0, 214.0, 216.0, 218.0,
            220.0, 222.0, 224.0, 226.0, 228.0, 230.0, 232.0, 234.0, 236.0, 238.0,
            240.0, 242.0, 244.0, 246.0, 248.0, 250.0, 252.0, 254.0, 256.0, 258.0,
            260.0, 262.0, 264.0, 266.0, 268.0, 270.0, 272.0, 274.0, 276.0, 278.0,
            280.0, 282.0, 284.0, 286.0, 288.0, 290.0, 292.0, 294.0, 296.0, 298.0)
        val stats = detector.stats(prices)

        assertEquals(1.98, stats!!.roi, 0.01) // (298-100)/100 ≈ 1.98
        val vol = stats.volatility
        assertTrue(vol > 0.0)
        val mean = stats.meanPrice
        assertTrue(mean > 100.0)
    }

    @Test
    fun testClassifyTopLevelFunction() {
        val prices = generatePriceSeries(
            start = 100.0,
            roi = 0.10,
            volatility = 0.03
        )
        val result = classify(prices)
        assertEquals(Regime.BULL_RUSH, result)
    }

    @Test
    fun testClassifyFromStats() {
        val stats = RegimeStats(
            roi = 0.10,
            volatility = 0.03,
            meanPrice = 105.0
        )
        val result = classifyFromStats(stats)
        assertEquals(Regime.BULL_RUSH, result)
    }

    // Helper function to generate price series with desired properties
   fun generatePriceSeries(
        start: Double,
        roi: Double,
        volatility: Double,
        length: Int = 100
    ): DoubleArray {
        val end = start * (1.0 + roi)
        val prices = DoubleArray(length)
        prices[0] = start
        prices[length - 1] = end

        // Fill intermediate values with linear trend plus noise
        val trendStep = (end - start) / (length - 1)
        for (i in 1 until length - 1) {
            val trend = start + i * trendStep
            val noise = (Random.Default.nextDouble() - 0.5) * 2 * volatility * trend

            prices[i] = trend + noise
        }

        return prices
    }
}
