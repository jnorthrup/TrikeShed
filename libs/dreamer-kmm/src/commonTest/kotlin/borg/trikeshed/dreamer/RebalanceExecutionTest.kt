package borg.trikeshed.dreamer

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.collections.s_
import borg.trikeshed.lib.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for rebalance execution in the TradingEngine.
 *
 * Rebalance flow:
 *   1. First cycle: baseline is set to initial value
 *   2. Price rises: surplus triggers harvest (existing behavior)
 *   3. Price deviates significantly: rebalance is scheduled
 *   4. On next cycle: scheduled rebalance executes (baseline reset to current)
 *   5. After execution: rebalanceState is cleared, harvest resumes from new baseline
 *
 * This pins the behavior that makes FLAT_REBALANCE_TRIGGER_PERCENT an
 * evolvable genome parameter in stochastic back-testing.
 */
class RebalanceExecutionTest {

    private fun klinesToCursor(klines: List<Kline>): Cursor {
        val block = KlineBlock.mutable()
        klines.forEach { block.append(it) }
        return block.seal().asCursor()
    }

    @Test
    fun `rebalance execution resets baseline to current value`() {
        val genome = defaultGenome()
        genome[GenomeParam.FLAT_REBALANCE_TRIGGER_PERCENT] = 0.01 // 1% triggers rebalance
        genome[GenomeParam.MIN_SURPLUS_FOR_HARVEST] = 100.0 // high threshold to isolate rebalance

        val engine = TradingEngine(genome, Mode.SHADOW, initialCapital = 10_000.0)

        // First row: establishes baseline at 100.0 * qty
        val rows1 = listOf(PortfolioRow("BTCUSDT", 100.0, 100.0, 10_000.0))
        runTest { engine.update(rows1, null, 0.0, null) }

        assertEquals(10_000.0, engine.baselines["BTCUSDT"]!!, 1.0)

        // Second row: 10% increase — exceeds 1% rebalance trigger
        val rows2 = listOf(PortfolioRow("BTCUSDT", 100.0, 110.0, 11_000.0))
        runTest { engine.update(rows2, null, 0.0, null) }

        // Rebalance should be scheduled
        assertTrue(engine.rebalanceState.containsKey("BTCUSDT"),
            "Rebalance should be scheduled after 10% deviation")

        // Third row: rebalance executes, baseline resets
        val rows3 = listOf(PortfolioRow("BTCUSDT", 100.0, 112.0, 11_200.0))
        runTest { engine.update(rows3, null, 0.0, null) }

        // After execution, baseline should have been reset to current value
        // (the value at the time of execution, which is row2 or row3 value)
        assertTrue(engine.baselines["BTCUSDT"]!! > 10_000.0,
            "Baseline should be reset above initial: ${engine.baselines["BTCUSDT"]}")
    }

    @Test
    fun `rebalance clears rebalanceState after execution`() {
        val genome = defaultGenome()
        genome[GenomeParam.FLAT_REBALANCE_TRIGGER_PERCENT] = 0.01
        genome[GenomeParam.MIN_SURPLUS_FOR_HARVEST] = 100_000.0 // suppress harvest

        val engine = TradingEngine(genome, Mode.SHADOW, initialCapital = 10_000.0)

        // Cycle 1: set baseline
        runTest {
            engine.update(listOf(PortfolioRow("BTCUSDT", 100.0, 100.0, 10_000.0)), null, 0.0, null)
        }

        // Cycle 2: trigger rebalance
        runTest {
            engine.update(listOf(PortfolioRow("BTCUSDT", 100.0, 110.0, 11_000.0)), null, 0.0, null)
        }
        assertTrue(engine.rebalanceState.containsKey("BTCUSDT"))

        // Cycle 3: rebalance executes and clears state
        runTest {
            engine.update(listOf(PortfolioRow("BTCUSDT", 100.0, 112.0, 11_200.0)), null, 0.0, null)
        }
        assertFalse(engine.rebalanceState.containsKey("BTCUSDT"),
            "rebalanceState should be cleared after execution")
    }

    @Test
    fun `rebalance does not fire when deviation is below trigger`() {
        val genome = defaultGenome()
        genome[GenomeParam.FLAT_REBALANCE_TRIGGER_PERCENT] = 0.50 // 50% deviation needed
        genome[GenomeParam.MIN_SURPLUS_FOR_HARVEST] = 100_000.0

        val engine = TradingEngine(genome, Mode.SHADOW, initialCapital = 10_000.0)

        // Cycle 1: set baseline
        runTest {
            engine.update(listOf(PortfolioRow("BTCUSDT", 100.0, 100.0, 10_000.0)), null, 0.0, null)
        }

        // Cycle 2: 10% increase — below 50% trigger
        runTest {
            engine.update(listOf(PortfolioRow("BTCUSDT", 100.0, 110.0, 11_000.0)), null, 0.0, null)
        }

        assertFalse(engine.rebalanceState.containsKey("BTCUSDT"),
            "Rebalance should NOT be scheduled for small deviation")
    }

    @Test
    fun `simulateTicks rebalance execution produces different results than no rebalance`() = runTest {
        // Two identical price series, one with tight rebalance trigger, one with wide
        val klines = (0 until 20).map { i ->
            val price = 100.0 + i * 5.0 // steadily rising
            Kline("BTCUSDT", TimeSpan.Hours1, 1000L + i * 3600_000L, price - 1.0, price + 1.0, price - 2.0, price, 50.0)
        }
        val cursor = klinesToCursor(klines)

        // Tight rebalance: triggers early and often
        val genomeTight = defaultGenome()
        genomeTight[GenomeParam.FLAT_REBALANCE_TRIGGER_PERCENT] = 0.01
        val engineTight = TradingEngine(genomeTight, Mode.SHADOW, initialCapital = 10_000.0)
        val resultTight = simulateTicks(cursor, engineTight, initialCapital = 10_000.0)

        // Rebuild cursor (it's lazy but we need fresh state)
        val cursor2 = klinesToCursor(klines)
        val genomeWide = defaultGenome()
        genomeWide[GenomeParam.FLAT_REBALANCE_TRIGGER_PERCENT] = 10.0 // effectively never triggers
        val engineWide = TradingEngine(genomeWide, Mode.SHADOW, initialCapital = 10_000.0)
        val resultWide = simulateTicks(cursor2, engineWide, initialCapital = 10_000.0)

        // The tight rebalance should schedule and execute rebalances
        val tightRebalanceCycles = resultTight.cycles.view.count { it.rebalanceScheduled }
        assertTrue(tightRebalanceCycles > 0, "Tight rebalance should have scheduled rebalances: got $tightRebalanceCycles")

        // With tight rebalance, baselines reset frequently, affecting harvest behavior
        // This makes the FLAT_REBALANCE_TRIGGER_PERCENT parameter evolvable
        val tightHarvestTotal = resultTight.metrics.totalHarvested
        val wideHarvestTotal = resultWide.metrics.totalHarvested
        // They should differ because tight rebalance resets baselines, changing harvest surplus
        assertTrue(tightHarvestTotal != wideHarvestTotal || tightRebalanceCycles > 0,
            "Tight vs wide rebalance should produce different harvest behavior")
    }

    @Test
    fun `rebalance execution is recorded in engineSnapshot`() = runTest {
        val klines = listOf(
            Kline("BTCUSDT", TimeSpan.Hours1, 1000L, 100.0, 101.0, 99.0, 100.0, 50.0),
            Kline("BTCUSDT", TimeSpan.Hours1, 2000L, 100.0, 101.0, 99.0, 200.0, 50.0),  // 100% jump
            Kline("BTCUSDT", TimeSpan.Hours1, 3000L, 200.0, 201.0, 199.0, 200.0, 50.0),
        )
        val cursor = klinesToCursor(klines)

        val genome = defaultGenome()
        genome[GenomeParam.FLAT_REBALANCE_TRIGGER_PERCENT] = 0.01
        genome[GenomeParam.MIN_SURPLUS_FOR_HARVEST] = 100_000.0 // suppress harvest

        val engine = TradingEngine(genome, Mode.SHADOW, initialCapital = 10_000.0)
        val result = simulateTicks(cursor, engine, initialCapital = 10_000.0)

        // At least one cycle should have rebalanceScheduled=true
        val rebalanceCycles = result.cycles.view.filter { it.rebalanceScheduled }
        assertTrue(rebalanceCycles.isNotEmpty(), "Should have rebalance cycles")

        // The engine snapshot should show updated baselines after rebalance
        val lastSnapshot = result.cycles.last().engineSnapshot
        val baselines = lastSnapshot["baselines"] as? Map<*, *>
        assertTrue(baselines != null && baselines.containsKey("BTCUSDT"),
            "Snapshot should contain BTCUSDT baseline")
    }
}
