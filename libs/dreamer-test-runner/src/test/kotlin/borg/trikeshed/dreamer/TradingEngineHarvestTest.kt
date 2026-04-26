package borg.trikeshed.dreamer

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class TradingEngineHarvestTest {
    @Test
    fun harvestTriggersWhenSurplusExceedsMin() = runBlocking {
        val g = defaultGenome()
        val engine = TradingEngine(g, Mode.SHADOW, initialCapital = 100.0)

        // First pass to initialize baseline at 10.0
        val first = listOf(PortfolioRow("FOO", 1.0, 10.0, 10.0))
        engine.update(first, null, 100.0, mapOf("FOO" to Holding(1.0)))

        // Second pass: value increased to 11.0 (surplus = 1.0)
        val second = listOf(PortfolioRow("FOO", 1.0, 11.0, 11.0))
        val res = engine.update(second, null, 100.0, mapOf("FOO" to Holding(1.0)))

        val expectedHarvest = 1.0 * 0.70

        assertTrue(res.anyTradesThisCycle)
        assertEquals(expectedHarvest, res.harvestedAmount, 1e-9)
        assertEquals(expectedHarvest, engine.totalHarvested, 1e-9)
    }
}
