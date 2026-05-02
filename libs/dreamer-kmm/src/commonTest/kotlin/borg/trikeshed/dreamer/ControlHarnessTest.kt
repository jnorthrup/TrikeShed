package borg.trikeshed.dreamer

import borg.trikeshed.context.ElementState
import borg.trikeshed.cursor.at
import borg.trikeshed.cursor.getValue
import borg.trikeshed.lib.size
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ControlHarnessTest {
    @Test
    fun `control harness projects wallet free horizon pancake and ochl frames`() = runTest {
        val btc = HarnessReplayInput(
            klineSeriesKey("BTC", "USDT", TimeSpan.Minutes1),
            block("BTC", listOf(100.0, 102.0, 104.0, 106.0)),
        )
        val eth = HarnessReplayInput(
            klineSeriesKey("ETH", "USDT", TimeSpan.Minutes1),
            block("ETH", listOf(10.0, 11.0, 12.0, 13.0)),
        )
        val simulation = ControlSimulation(
            inputs = listOf(btc, eth),
            options = ControlSimulationOptions(
                initialCapital = 1_000.0,
                depthMode = SimulationDepthMode.SHORT,
                sweepMode = SimulationSweepMode.MICRO_GRID,
            ),
        )
        simulation.wallet.record("BTC", 0.25)
        repeat(3) { simulation.advance() }
        val harness = ControlHarness(horizonDepth = 4)

        assertSame(ControlHarness.Key, harness.key)
        harness.open()
        val frame = harness.frame(simulation)

        assertEquals(ElementState.ACTIVE, harness.lifecycleState)
        assertEquals(3, frame.tick)
        assertEquals(2, frame.pairs.size)
        assertEquals(1_704_067_380_000L, frame.openTime)
        assertSame(simulation.wallet, simulation.wallet)
        assertEquals(4, simulation.simTimeLimit)
        assertEquals(SimulationDepthMode.SHORT, simulation.options.depthMode)
        assertEquals(SimulationSweepMode.MICRO_GRID, simulation.options.sweepMode)
        assertEquals(listOf("BTC", "ETH"), simulation.tradedPairs.map { it.a })
        assertEquals(listOf("USDT", "USDT"), simulation.tradedPairs.map { it.b })

        val btcFrame = frame.pairs.first()
        assertEquals(btc.key, btcFrame.route.a)
        assertEquals(3, btcFrame.route.b)
        assertEquals(0.25, btcFrame.walletFree.at(0).getValue("BTC"))
        assertEquals(-1_000.0, btcFrame.walletFree.at(0).getValue("USDT"))
        assertEquals(-1e-10, btcFrame.walletFree.at(0).getValue("ETH"))
        assertEquals(4, btcFrame.horizonOhlcv.size)
        assertEquals(20, btcFrame.pancake.at(0).size)
        assertEquals(100.0, btcFrame.pancake.at(0).getValue("open/0"))
        assertEquals(106.0, btcFrame.ochl.at(0).getValue("close/0"))

        val materialized = frame.materializePancake()
        assertEquals(46, materialized.size)
        assertTrue(materialized.any { it == 106.0 })
    }

    @Test
    fun `control harness refuses frames after drain closes lifecycle`() = runTest {
        val harness = ControlHarness(horizonDepth = 2)
        val input = HarnessReplayInput(
            klineSeriesKey("BTC", "USDT", TimeSpan.Minutes1),
            block("BTC", listOf(100.0, 101.0)),
        )
        val simulation = ControlSimulation(
            inputs = listOf(input),
            options = ControlSimulationOptions(initialCapital = 100.0),
        )

        harness.open()
        harness.drain()

        assertEquals(ElementState.CLOSED, harness.lifecycleState)
        assertFailsWith<IllegalStateException> {
            harness.frame(simulation)
        }
    }
}
