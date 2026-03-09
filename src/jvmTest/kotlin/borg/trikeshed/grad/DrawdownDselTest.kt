package borg.trikeshed.grad

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Comprehensive tests for DrawdownDsel.kt
 */
class DrawdownDselTest {

    @Test
    fun testDrawdownSeries_basic() {
        val equity = 6 j { i: Int ->
            when (i) {
                0 -> 100.0
                1 -> 105.0
                2 -> 98.0
                3 -> 102.0
                4 -> 95.0
                5 -> 110.0
                else -> 0.0
            }
        }

        val dd = equity.drawdownSeries

        assertEquals(0.0, dd[0], 0.001)
        assertEquals(0.0, dd[1], 0.001)
        assertTrue(dd[2] > 0.06)
        assertTrue(dd[3] > 0.02)
        assertTrue(dd[4] > 0.09)
        assertEquals(0.0, dd[5], 0.001)
    }

    @Test
    fun testDrawdownSeries_empty() {
        val equity = 0 j { _: Int -> 0.0 }
        val dd = equity.drawdownSeries
        assertEquals(0, dd.size)
    }

    @Test
    fun testDrawdownSeries_monotonicIncrease() {
        val equity = 5 j { i: Int -> 100.0 + i * 10 }
        val dd = equity.drawdownSeries

        for (i in 0 until dd.size) {
            assertEquals(0.0, dd[i], 0.001)
        }
    }

    @Test
    fun testMaxDrawdown_basic() {
        val equity = 6 j { i: Int ->
            when (i) {
                0 -> 100.0
                1 -> 105.0
                2 -> 98.0
                3 -> 102.0
                4 -> 95.0
                5 -> 110.0
                else -> 0.0
            }
        }

        val mdd = equity.maxDrawdown
        assertTrue(mdd > 0.09)
    }

    @Test
    fun testMaxDrawdown_severeDecline() {
        val equity = 4 j { i: Int ->
            when (i) {
                0 -> 100.0
                1 -> 80.0
                2 -> 60.0
                3 -> 50.0
                else -> 0.0
            }
        }

        val mdd = equity.maxDrawdown
        assertEquals(0.5, mdd, 0.001)
    }

    @Test
    fun testDrawdownDuration_basic() {
        val equity = 6 j { i: Int ->
            when (i) {
                0 -> 100.0
                1 -> 95.0
                2 -> 90.0
                3 -> 105.0
                4 -> 100.0
                5 -> 98.0
                else -> 0.0
            }
        }

        val durations = equity.drawdownDuration

        assertEquals(0, durations[0])
        assertEquals(1, durations[1])
        assertEquals(2, durations[2])
        assertEquals(0, durations[3])
        assertEquals(1, durations[4])
        assertEquals(2, durations[5])
    }

    @Test
    fun testMaxDrawdownDuration_basic() {
        val equity = 6 j { i: Int ->
            when (i) {
                0 -> 100.0
                1 -> 95.0
                2 -> 90.0
                3 -> 105.0
                4 -> 100.0
                5 -> 98.0
                else -> 0.0
            }
        }

        val maxDuration = equity.maxDrawdownDuration
        assertEquals(2, maxDuration)
    }

    @Test
    fun testUlcerIndex_basic() {
        val equity = 6 j { i: Int ->
            when (i) {
                0 -> 100.0
                1 -> 105.0
                2 -> 98.0
                3 -> 102.0
                4 -> 95.0
                5 -> 110.0
                else -> 0.0
            }
        }

        val ulcer = equity.ulcerIndex
        assertTrue(ulcer > 0)
        assertTrue(ulcer < 0.1)
    }

    @Test
    fun testUlcerIndex_zero() {
        val equity = 5 j { i: Int -> 100.0 }
        val ulcer = equity.ulcerIndex
        assertEquals(0.0, ulcer, 0.001)
    }

    @Test
    fun testCalmarRatio_positive() {
        val equity = 252 j { i: Int ->
            100.0 * (1.0 + i * 0.001)
        }

        val calmar = equity.calmarRatio()
        assertTrue(calmar > 0)
    }

    @Test
    fun testCalmarRatio_insufficientData() {
        val equity = 1 j { _: Int -> 100.0 }
        val calmar = equity.calmarRatio()
        assertEquals(0.0, calmar, 0.001)
    }

    @Test
    fun testRecoveryFactor_basic() {
        val equity = 6 j { i: Int ->
            when (i) {
                0 -> 100.0
                1 -> 105.0
                2 -> 98.0
                3 -> 102.0
                4 -> 95.0
                5 -> 110.0
                else -> 0.0
            }
        }

        val recovery = equity.recoveryFactor()
        assertTrue(recovery > 0)
    }

    @Test
    fun testPainIndex_basic() {
        val equity = 6 j { i: Int ->
            when (i) {
                0 -> 100.0
                1 -> 105.0
                2 -> 98.0
                3 -> 102.0
                4 -> 95.0
                5 -> 110.0
                else -> 0.0
            }
        }

        val pain = equity.painIndex
        assertTrue(pain > 0)
        assertTrue(pain < 0.1)
    }

    @Test
    fun testPretest_passing() {
        val equity = 252 j { i: Int ->
            100.0 * (1.0 + i * 0.002)
        }

        val result = equity.pretest()

        assertTrue(result.passed)
        assertTrue(result.failures.isEmpty())
        assertTrue(result.metrics.containsKey("maxDrawdown"))
        assertTrue(result.metrics.containsKey("calmarRatio"))
    }

    @Test
    fun testPretest_summary() {
        val equity = 252 j { i: Int -> 100.0 * (1.0 + i * 0.001) }
        val result = equity.pretest()

        val summary = result.summary()
        assertTrue(summary.contains("Pretest Result"))
        assertTrue(summary.contains("Metrics"))
    }

    @Test
    fun testPaperTest_basic() {
        val prices = 100 j { i: Int -> 100.0 + i * 0.5 }
        val signals = 100 j { i: Int ->
            when {
                i % 20 == 0 -> 1
                i % 20 == 10 -> -1
                else -> 0
            }
        }

        val result = paperTest(prices, signals)

        assertTrue(result.trades.isNotEmpty())
        assertTrue(result.equityCurve.size > 0)
    }

    @Test
    fun testPaperTest_noSignals() {
        val prices = 100 j { i: Int -> 100.0 + i * 0.1 }
        val signals = 100 j { _: Int -> 0 }

        val result = paperTest(prices, signals)

        assertEquals(0, result.trades.size)
        assertEquals(0.0, result.winRate, 0.001)
    }

    @Test
    fun testPaperTest_summary() {
        val prices = 100 j { i: Int -> 100.0 + i * 0.5 }
        val signals = 100 j { i: Int ->
            when {
                i % 20 == 0 -> 1
                i % 20 == 10 -> -1
                else -> 0
            }
        }

        val result = paperTest(prices, signals)
        val summary = result.summary()

        assertTrue(summary.contains("Paper Test Result"))
        assertTrue(summary.contains("Total Return"))
    }

    @Test
    fun testOptimalF_basic() {
        val trades = 20 j { i: Int ->
            when {
                i % 3 == 0 -> 100.0
                i % 3 == 1 -> -50.0
                else -> 75.0
            }
        }

        val optF = trades.optimalF()

        assertTrue(optF.fraction >= 0)
        assertTrue(optF.fraction <= 0.25)
        assertTrue(optF.winRate > 0)
    }

    @Test
    fun testDifferentiableDrawdownSeries() {
        val equity = 6 j { i: Int ->
            when (i) {
                0 -> 100.0.lift
                1 -> 105.0.lift
                2 -> 98.0.lift
                3 -> 102.0.lift
                4 -> 95.0.lift
                5 -> 110.0.lift
                else -> 0.0.lift
            }
        }

        val ddFun = equity.drawdownSeries()
        assertEquals(6, ddFun.size)
    }

    @Test
    fun testDifferentiableMaxDrawdown() {
        val equity = 6 j { i: Int ->
            when (i) {
                0 -> 100.0.lift
                1 -> 105.0.lift
                2 -> 98.0.lift
                3 -> 102.0.lift
                4 -> 95.0.lift
                5 -> 110.0.lift
                else -> 0.0.lift
            }
        }

        val mddFun = equity.maxDrawdown()
        val mdd = mddFun `≈` emptyMap()
        assertTrue(mdd > 0)
    }

    @Test
    fun testDifferentiableUlcerIndex() {
        val equity = 6 j { i: Int ->
            when (i) {
                0 -> 100.0.lift
                1 -> 105.0.lift
                2 -> 98.0.lift
                3 -> 102.0.lift
                4 -> 95.0.lift
                5 -> 110.0.lift
                else -> 0.0.lift
            }
        }

        val ulcerFun = equity.ulcerIndex()
        val ulcer = ulcerFun `≈` emptyMap()
        assertTrue(ulcer > 0)
    }

    @Test
    fun testDifferentiablePainIndex() {
        val equity = 6 j { i: Int ->
            when (i) {
                0 -> 100.0.lift
                1 -> 105.0.lift
                2 -> 98.0.lift
                3 -> 102.0.lift
                4 -> 95.0.lift
                5 -> 110.0.lift
                else -> 0.0.lift
            }
        }

        val painFun = equity.painIndex()
        val pain = painFun `≈` emptyMap()
        assertTrue(pain > 0)
    }

    @Test
    fun testGenerateSignals_basic() {
        val prices = 10 j { i: Int ->
            (100.0 + i * 2).lift
        }

        val threshold = 0.02.lift
        val signals = prices.generateSignals(threshold)

        assertEquals(10, signals.size)
    }
}
