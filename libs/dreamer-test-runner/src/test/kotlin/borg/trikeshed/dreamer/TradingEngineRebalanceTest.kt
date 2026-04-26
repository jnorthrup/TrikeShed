package borg.trikeshed.dreamer

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class TradingEngineRebalanceTest {
    @Test
    fun rebalanceScheduledWhenDeviationExceedsTrigger() = runBlocking {
        val g = defaultGenome()
        g["FLAT_REBALANCE_TRIGGER_PERCENT"] = 0.01
        val engine = TradingEngine(g, Mode.SHADOW, initialCapital = 100.0)

        val first = listOf(PortfolioRow("FOO", 1.0, 10.0, 10.0))
        engine.update(first, null, 100.0, mapOf("FOO" to Holding(1.0)))

        val second = listOf(PortfolioRow("FOO", 1.0, 10.5, 10.5))
        engine.update(second, null, 100.0, mapOf("FOO" to Holding(1.0)))

        assertTrue(engine.rebalanceState.containsKey("FOO"), "Expected rebalance state to be scheduled for FOO")
    }
}
