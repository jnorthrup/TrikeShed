package borg.trikeshed.dreamer

import borg.trikeshed.couch.kline.TimeSpan
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimulationReplayTest {
    @Test
    fun `replayCsv runs Binance archive csv through cursor simulation`() = runBlocking<Unit> {
        val csv = """
            open_time,open,high,low,close,volume,close_time,quote_asset_volume,number_of_trades,taker_buy_base_volume,taker_buy_quote_volume,ignore
            1704067200000,100.0,102.0,99.0,101.0,10.0,1704070799999,1010.0,12,5.0,505.0,0
            1704070800000,101.0,104.0,100.0,103.0,12.0,1704074399999,1236.0,18,6.0,618.0,0
            1704074400000,103.0,106.0,102.0,105.0,14.0,1704077999999,1470.0,24,7.0,735.0,0
        """.trimIndent()

        val replay = SimulationReplay(
            genome = defaultGenome(),
            mode = Mode.SHADOW,
            initialCapital = 10_000.0,
        )

        val result = replay.replayCsv(
            csvText = csv,
            symbol = "BTCUSDT",
            timespan = TimeSpan.Hours1,
        )

        assertEquals("BTCUSDT", result.symbol)
        assertEquals(10_000.0, result.initialCapital)
        assertEquals(3, result.cycles.size)
        assertEquals(3, result.metrics.totalTicks)
        assertEquals(1704067200000L, result.cycles.first().openTime)
        assertTrue(result.metrics.totalReturn > 0.03, "totalReturn=${result.metrics.totalReturn}")
    }
}
