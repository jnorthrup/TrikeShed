package borg.trikeshed.dreamer

import borg.trikeshed.collections.s_
import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BinanceVisionKlineFeedTest {
    @Test
    fun `plan mirrors mp bin monthly and daily Binance Vision paths`() {
        val feed = BinanceVisionKlineFeed(cacheRoot = "/tmp/mpdata/import")
        val key = klineSeriesKey("BTC", "USDT", TimeSpan.Minutes1)

        val plan = feed.plan(
            key = key,
            months = s_[ArchiveMonth(2024, 1)],
            days = s_[ArchiveDay(2024, 2, 3)],
        )

        assertEquals("BTCUSDT", plan.key.symbol)
        assertEquals(
            "https://data.binance.vision/data/spot/monthly/klines/BTCUSDT/1m/BTCUSDT-1m-2024-01.zip",
            plan.monthly[0].url,
        )
        assertEquals(
            "/tmp/mpdata/import/monthly/klines/BTCUSDT/1m/BTCUSDT-1m-2024-01.zip.CHECKSUM",
            plan.monthly[0].checksumCachePath,
        )
        assertEquals(
            "https://data.binance.vision/data/spot/daily/klines/BTCUSDT/1m/BTCUSDT-1m-2024-02-03.zip.CHECKSUM",
            plan.daily[0].checksumUrl,
        )
    }

    @Test
    fun `parseCachedCsv seals KlineBlock and exposes cursor rows`() {
        val feed = BinanceVisionKlineFeed()
        val csv = """
            open_time,open,high,low,close,volume,close_time,quote_asset_volume,number_of_trades,taker_buy_base_volume,taker_buy_quote_volume,ignore
            1704067200000,100.0,102.0,99.0,101.0,10.0,1704070799999,1010.0,12,5.0,505.0,0
        """.trimIndent()

        val result = feed.parseCachedCsv(klineSeriesKey("ETH", "USDT", TimeSpan.Hours1), csv)

        assertEquals(1, result.block.rowCount)
        assertEquals("ETHUSDT", result.key.symbol)
        assertEquals(1, result.cursor.size)
        assertFailsWith<IllegalStateException> {
            result.block.append(result.block.rows.first())
        }
    }
}
