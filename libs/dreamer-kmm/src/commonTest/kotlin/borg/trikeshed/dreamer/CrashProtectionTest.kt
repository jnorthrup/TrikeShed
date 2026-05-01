package borg.trikeshed.dreamer

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.lib.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for crash protection in the TradingEngine.
 *
 * Crash protection prevents the engine from harvesting during sharp drawdowns:
 *   1. When asset value drops significantly below baseline → enter crash protection
 *   2. In CP mode: suppress harvest for that symbol (avoid locking in losses)
 *   3. When value partially recovers → exit CP, reset baseline, resume normal operations
 *
 * This makes crash protection genome parameters evolvable in stochastic back-testing.
 */
class CrashProtectionTest {

    private fun klinesToCursor(klines: List<Kline>): Cursor {
        val block = KlineBlock.mutable()
        klines.forEach { block.append(it) }
        return block.seal().asCursor()
    }

    @Test
    fun `crash protection activates when value drops below trigger threshold`() {
        val genome = defaultGenome()
        genome[GenomeParam.CP_TRIGGER_ASSET_PERCENT] = 0.10  // 10% drop triggers CP
        genome[GenomeParam.CP_TRIGGER_MIN_NEGATIVE_DEV_PERCENT] = 0.05
        genome[GenomeParam.MIN_SURPLUS_FOR_HARVEST] = 0.0  // allow easy harvest

        val engine = TradingEngine(genome, Mode.SHADOW, initialCapital = 10_000.0)

        // Cycle 1: set baseline at 10_000
        runTest {
            engine.update(listOf(PortfolioRow("BTCUSDT", 100.0, 100.0, 10_000.0)), null, 0.0, null)
        }
        assertEquals(10_000.0, engine.baselines["BTCUSDT"]!!, 1.0)
        assertFalse(engine.isCrashProtected("BTCUSDT"))

        // Cycle 2: 15% drop — triggers CP
        runTest {
            engine.update(listOf(PortfolioRow("BTCUSDT", 85.0, 85.0, 8_500.0)), null, 0.0, null)
        }
        assertTrue(engine.isCrashProtected("BTCUSDT"),
            "Should enter crash protection after 15% drop")
    }

    @Test
    fun `crash protection suppresses harvest during drawdown`() {
        val genome = defaultGenome()
        genome[GenomeParam.CP_TRIGGER_ASSET_PERCENT] = 0.10
        genome[GenomeParam.CP_TRIGGER_MIN_NEGATIVE_DEV_PERCENT] = 0.05
        genome[GenomeParam.MIN_SURPLUS_FOR_HARVEST] = 0.001
        genome[GenomeParam.HARVEST_TAKE_PERCENT] = 0.50

        val engine = TradingEngine(genome, Mode.SHADOW, initialCapital = 10_000.0)

        // Cycle 1: baseline at 10_000
        runTest {
            engine.update(listOf(PortfolioRow("BTCUSDT", 100.0, 100.0, 10_000.0)), null, 0.0, null)
        }

        // Cycle 2: 15% drop → CP activates
        runTest {
            val result = engine.update(listOf(PortfolioRow("BTCUSDT", 85.0, 85.0, 8_500.0)), null, 0.0, null)
            assertFalse(result.anyTradesThisCycle, "No harvest during crash protection")
            assertEquals(0.0, result.harvestedAmount, 0.001, "No harvest during crash")
        }

        // Cycle 3: still in drawdown, price bounces a little but still below baseline
        runTest {
            val result = engine.update(listOf(PortfolioRow("BTCUSDT", 90.0, 90.0, 9_000.0)), null, 0.0, null)
            assertFalse(result.anyTradesThisCycle, "No harvest while CP active")
        }
    }

    @Test
    fun `crash protection exits on partial recovery`() {
        val genome = defaultGenome()
        genome[GenomeParam.CP_TRIGGER_ASSET_PERCENT] = 0.10
        genome[GenomeParam.CP_TRIGGER_MIN_NEGATIVE_DEV_PERCENT] = 0.05
        genome[GenomeParam.CRASH_PROTECTION_PARTIAL_RECOVERY_PERCENT] = 0.30
        genome[GenomeParam.MIN_SURPLUS_FOR_HARVEST] = 0.001

        val engine = TradingEngine(genome, Mode.SHADOW, initialCapital = 10_000.0)

        // Cycle 1: baseline at 10_000
        runTest {
            engine.update(listOf(PortfolioRow("BTCUSDT", 100.0, 100.0, 10_000.0)), null, 0.0, null)
        }

        // Cycle 2: 20% drop → CP activates
        runTest {
            engine.update(listOf(PortfolioRow("BTCUSDT", 80.0, 80.0, 8_000.0)), null, 0.0, null)
        }
        assertTrue(engine.isCrashProtected("BTCUSDT"))

        // Cycle 3: partial recovery to 8800 (10% recovery from 8000 trough, which is 30% of the 2000 drop)
        // With 30% recovery threshold: need value >= 8000 + 0.30 * 2000 = 8600
        runTest {
            engine.update(listOf(PortfolioRow("BTCUSDT", 88.0, 88.0, 8_800.0)), null, 0.0, null)
        }
        assertFalse(engine.isCrashProtected("BTCUSDT"),
            "Should exit CP after partial recovery to 8800 (40% of 2000 drop recovered)")
    }

    @Test
    fun `crash protection resets baseline on exit`() {
        val genome = defaultGenome()
        genome[GenomeParam.CP_TRIGGER_ASSET_PERCENT] = 0.10
        genome[GenomeParam.CRASH_PROTECTION_PARTIAL_RECOVERY_PERCENT] = 0.30
        genome[GenomeParam.MIN_SURPLUS_FOR_HARVEST] = 0.001

        val engine = TradingEngine(genome, Mode.SHADOW, initialCapital = 10_000.0)

        // Cycle 1: baseline at 10_000
        runTest {
            engine.update(listOf(PortfolioRow("BTCUSDT", 100.0, 100.0, 10_000.0)), null, 0.0, null)
        }
        val originalBaseline = engine.baselines["BTCUSDT"]!!

        // Cycle 2: 20% drop → CP
        runTest {
            engine.update(listOf(PortfolioRow("BTCUSDT", 80.0, 80.0, 8_000.0)), null, 0.0, null)
        }

        // Cycle 3: partial recovery → CP exits, baseline resets
        runTest {
            engine.update(listOf(PortfolioRow("BTCUSDT", 88.0, 88.0, 8_800.0)), null, 0.0, null)
        }
        val newBaseline = engine.baselines["BTCUSDT"]!!
        assertTrue(newBaseline < originalBaseline,
            "Baseline should be reset below original after CP exit: was $newBaseline vs $originalBaseline")
    }

    @Test
    fun `crash protection does not activate for small drops`() {
        val genome = defaultGenome()
        genome[GenomeParam.CP_TRIGGER_ASSET_PERCENT] = 0.20  // 20% drop needed
        genome[GenomeParam.MIN_SURPLUS_FOR_HARVEST] = 0.001

        val engine = TradingEngine(genome, Mode.SHADOW, initialCapital = 10_000.0)

        // Cycle 1: baseline
        runTest {
            engine.update(listOf(PortfolioRow("BTCUSDT", 100.0, 100.0, 10_000.0)), null, 0.0, null)
        }

        // Cycle 2: 5% drop — below 20% threshold
        runTest {
            engine.update(listOf(PortfolioRow("BTCUSDT", 95.0, 95.0, 9_500.0)), null, 0.0, null)
        }
        assertFalse(engine.isCrashProtected("BTCUSDT"),
            "Should NOT enter CP for small 5% drop with 20% threshold")
    }

    @Test
    fun `simulateTicks crash protection affects backtest metrics during crash`() = runTest {
        // Rising then crashing price series
        val klines = listOf(
            Kline("BTCUSDT", TimeSpan.Hours1, 1000L, 100.0, 101.0, 99.0, 100.0, 50.0),
            Kline("BTCUSDT", TimeSpan.Hours1, 2000L, 100.0, 110.0, 99.0, 110.0, 50.0),  // +10%
            Kline("BTCUSDT", TimeSpan.Hours1, 3000L, 110.0, 115.0, 109.0, 115.0, 50.0),  // +15%
            Kline("BTCUSDT", TimeSpan.Hours1, 4000L, 115.0, 120.0, 114.0, 120.0, 50.0),  // +20%
            // Crash: 30% drop from peak baseline
            Kline("BTCUSDT", TimeSpan.Hours1, 5000L, 120.0, 80.0, 78.0, 80.0, 50.0),    // -33%
            Kline("BTCUSDT", TimeSpan.Hours1, 6000L, 80.0, 82.0, 78.0, 80.0, 50.0),      // still down
            Kline("BTCUSDT", TimeSpan.Hours1, 7000L, 80.0, 100.0, 79.0, 100.0, 50.0),    // partial recovery
            Kline("BTCUSDT", TimeSpan.Hours1, 8000L, 100.0, 110.0, 99.0, 110.0, 50.0),   // recovery
        )

        val cursor = klinesToCursor(klines)

        // With crash protection enabled
        val genomeCP = defaultGenome()
        genomeCP[GenomeParam.CP_TRIGGER_ASSET_PERCENT] = 0.15
        genomeCP[GenomeParam.CRASH_PROTECTION_PARTIAL_RECOVERY_PERCENT] = 0.30
        genomeCP[GenomeParam.MIN_SURPLUS_FOR_HARVEST] = 0.001

        val engineCP = TradingEngine(genomeCP, Mode.SHADOW, initialCapital = 10_000.0)
        val resultCP = simulateTicks(cursor, engineCP, initialCapital = 10_000.0)

        // Without crash protection (threshold so high it never triggers)
        val cursor2 = klinesToCursor(klines)
        val genomeNoCP = defaultGenome()
        genomeNoCP[GenomeParam.CP_TRIGGER_ASSET_PERCENT] = 0.99  // effectively disabled
        genomeNoCP[GenomeParam.MIN_SURPLUS_FOR_HARVEST] = 0.001

        val engineNoCP = TradingEngine(genomeNoCP, Mode.SHADOW, initialCapital = 10_000.0)
        val resultNoCP = simulateTicks(cursor2, engineNoCP, initialCapital = 10_000.0)

        // Crash protection should result in less harvest during the crash phase
        // which means different totalHarvested
        val cpHarvested = resultCP.metrics.totalHarvested
        val noCpHarvested = resultNoCP.metrics.totalHarvested
        // With CP, we avoid harvesting the crash bars, so total should differ
        assertTrue(cpHarvested != noCpHarvested,
            "Crash protection should change harvest behavior: CP=$cpHarvested vs noCP=$noCpHarvested")
    }
}
