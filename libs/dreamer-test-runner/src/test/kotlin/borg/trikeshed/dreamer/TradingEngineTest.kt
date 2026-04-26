package borg.trikeshed.dreamer

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class TradingEngineTest {
    @Test
    fun baselineInitialization() = runBlocking {
        val g = defaultGenome()
        val engine = TradingEngine(g, Mode.SHADOW, initialCapital = 100.0)

        val portfolio = listOf(PortfolioRow("FOO", 1.0, 10.0, 10.0))
        val res = engine.update(portfolio, null, 100.0, mapOf("FOO" to Holding(1.0)))

        assertEquals(10.0, engine.baselines["FOO"])
        assertEquals(true, res.stateChanged)
        assertEquals(false, res.anyTradesThisCycle)
    }
}
