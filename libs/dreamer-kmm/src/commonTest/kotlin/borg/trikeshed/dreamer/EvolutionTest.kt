package borg.trikeshed.dreamer

import borg.trikeshed.couch.kline.TimeSpan
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EvolutionTest {
    @Test
    fun `evaluatePopulation replays each genome over archive csv`() = runBlocking<Unit> {
        val csv = """
            open_time,open,high,low,close,volume,close_time,quote_asset_volume,number_of_trades,taker_buy_base_volume,taker_buy_quote_volume,ignore
            1704067200000,100.0,102.0,99.0,101.0,10.0,1704070799999,1010.0,12,5.0,505.0,0
            1704070800000,101.0,104.0,100.0,103.0,12.0,1704074399999,1236.0,18,6.0,618.0,0
        """.trimIndent()
        val a = defaultGenome()
        val b = defaultGenome().also { it["FLAT_HARVEST_TRIGGER_PERCENT"] = 0.05 }

        val evaluations = evaluatePopulation(
            genomes = listOf(a, b),
            csvText = csv,
            symbol = "BTCUSDT",
            timespan = TimeSpan.Hours1,
            initialCapital = 10_000.0,
        )

        assertEquals(2, evaluations.size)
        assertSame(a, evaluations[0].genome)
        assertSame(b, evaluations[1].genome)
        assertEquals(2, evaluations[0].result.metrics.totalTicks)
        assertTrue(evaluations.all { it.fitness == fitnessFromResult(it.result) })
    }
}
