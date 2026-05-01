package borg.trikeshed.dreamer

import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for KlineCsvParser: Binance archive CSV → ExtendedKline.
 *
 * Tests the low-level parsing edge cases that are only indirectly covered
 * by the integration tests in SimulationReplayTest and BacktestIntegrationTest.
 */
class KlineCsvParserTest {

    private fun parse(csvText: String, symbol: String = "BTCUSDT", timespan: TimeSpan = TimeSpan.Hours1) =
        klinesFromCsv(csvText.length j { csvText[it] }, symbol, timespan)

    @Test
    fun `klinesFromCsv parses standard 3-row Binance archive CSV`() {
        val csv = """
            open_time,open,high,low,close,volume,close_time,quote_asset_volume,number_of_trades,taker_buy_base_volume,taker_buy_quote_volume,ignore
            1704067200000,42000.0,42500.0,41800.0,42300.0,150.0,1704070799999,6345000.0,3200,75.0,3167250.0,0
            1704070800000,42300.0,43100.0,42100.0,42900.0,180.0,1704074399999,7722000.0,4100,90.0,3861000.0,0
            1704074400000,42900.0,43200.0,42500.0,42800.0,140.0,1704077999999,5992000.0,2800,70.0,2996000.0,0
        """.trimIndent()

        val klines = parse(csv)

        assertEquals(3, klines.size)
        assertEquals(1704067200000L, klines[0].openTime)
        assertEquals(42000.0, klines[0].open)
        assertEquals(42500.0, klines[0].high)
        assertEquals(41800.0, klines[0].low)
        assertEquals(42300.0, klines[0].close)
        assertEquals(150.0, klines[0].volume)
        assertEquals("BTCUSDT", klines[0].symbol)
        assertEquals(TimeSpan.Hours1, klines[0].timespan)
        assertEquals(3200, klines[0].trades)
    }

    @Test
    fun `klinesFromCsv skips header line`() {
        val csv = "open_time,open,high,low,close,volume,close_time,quote_asset_volume,number_of_trades,taker_buy_base_volume,taker_buy_quote_volume,ignore\n" +
            "1704067200000,100.0,102.0,99.0,101.0,10.0,1704070799999,1010.0,12,5.0,505.0,0\n"

        val klines = parse(csv)
        assertEquals(1, klines.size)
        assertEquals(100.0, klines[0].open)
    }

    @Test
    fun `klinesFromCsv returns empty for header-only CSV`() {
        val csv = "open_time,open,high,low,close,volume,close_time,quote_asset_volume,number_of_trades,taker_buy_base_volume,taker_buy_quote_volume,ignore\n"

        val klines = parse(csv)
        assertEquals(0, klines.size)
    }

    @Test
    fun `klinesFromCsv returns empty for blank input`() {
        val klines = parse("")
        assertEquals(0, klines.size)
    }

    @Test
    fun `klinesFromCsv skips blank lines between data rows`() {
        val csv = """
            open_time,open,high,low,close,volume,close_time,quote_asset_volume,number_of_trades,taker_buy_base_volume,taker_buy_quote_volume,ignore

            1704067200000,100.0,102.0,99.0,101.0,10.0,1704070799999,1010.0,12,5.0,505.0,0

            1704070800000,101.0,104.0,100.0,103.0,12.0,1704074399999,1236.0,18,6.0,618.0,0
        """.trimIndent()

        val klines = parse(csv)
        assertEquals(2, klines.size)
    }

    @Test
    fun `klinesFromCsv skips malformed lines with non-numeric fields`() {
        val csv = "open_time,open,high,low,close,volume,close_time,quote_asset_volume,number_of_trades,taker_buy_base_volume,taker_buy_quote_volume,ignore\n" +
            "bad_data,not_a_number,high,low,close,volume,close_time,quote_asset_volume,number_of_trades,taker_buy_base_volume,taker_buy_quote_volume,ignore\n" +
            "1704067200000,100.0,102.0,99.0,101.0,10.0,1704070799999,1010.0,12,5.0,505.0,0\n"

        val klines = parse(csv)
        assertEquals(1, klines.size)
        assertEquals(1704067200000L, klines[0].openTime)
    }

    @Test
    fun `klinesFromCsv parses extended kline fields correctly`() {
        val csv = "open_time,open,high,low,close,volume,close_time,quote_asset_volume,number_of_trades,taker_buy_base_volume,taker_buy_quote_volume,ignore\n" +
            "1704067200000,100.0,102.0,99.0,101.0,10.0,1704070799999,1010.0,12,5.0,505.0,0\n"

        val klines = parse(csv)
        assertEquals(1, klines.size)
        val k = klines[0]
        assertEquals(1704070799999L, k.closeTime)
        assertEquals(1010.0, k.quoteAssetVolume)
        assertEquals(12, k.trades)
        assertEquals(5.0, k.takerBuyBaseVolume)
        assertEquals(505.0, k.takerBuyQuoteVolume)
    }

    @Test
    fun `ExtendedKline toKline drops extended fields`() {
        val csv = "open_time,open,high,low,close,volume,close_time,quote_asset_volume,number_of_trades,taker_buy_base_volume,taker_buy_quote_volume,ignore\n" +
            "1704067200000,100.0,102.0,99.0,101.0,10.0,1704070799999,1010.0,12,5.0,505.0,0\n"

        val extended = parse(csv)[0]
        val kline = extended.toKline()

        assertEquals("BTCUSDT", kline.symbol)
        assertEquals(TimeSpan.Hours1, kline.timespan)
        assertEquals(1704067200000L, kline.openTime)
        assertEquals(100.0, kline.open)
        assertEquals(102.0, kline.high)
        assertEquals(99.0, kline.low)
        assertEquals(101.0, kline.close)
        assertEquals(10.0, kline.volume)
    }

    @Test
    fun `klinesFromCsv handles different symbols and timespans`() {
        val csv = "open_time,open,high,low,close,volume,close_time,quote_asset_volume,number_of_trades,taker_buy_base_volume,taker_buy_quote_volume,ignore\n" +
            "1704067200000,2500.0,2550.0,2490.0,2530.0,500.0,1704070799999,1265000.0,800,250.0,632500.0,0\n"

        val klines = parse(csv, symbol = "ETHUSDT", timespan = TimeSpan.Minutes5)

        assertEquals(1, klines.size)
        assertEquals("ETHUSDT", klines[0].symbol)
        assertEquals(TimeSpan.Minutes5, klines[0].timespan)
        assertEquals(2530.0, klines[0].close)
    }

    @Test
    fun `klinesFromCsv handles Windows-style CRLF line endings`() {
        val csv = "open_time,open,high,low,close,volume,close_time,quote_asset_volume,number_of_trades,taker_buy_base_volume,taker_buy_quote_volume,ignore\r\n" +
            "1704067200000,100.0,102.0,99.0,101.0,10.0,1704070799999,1010.0,12,5.0,505.0,0\r\n" +
            "1704070800000,101.0,104.0,100.0,103.0,12.0,1704074399999,1236.0,18,6.0,618.0,0\r\n"

        val klines = parse(csv)
        assertEquals(2, klines.size)
    }

    @Test
    fun `skipNl handles mixed newline styles`() {
        val arr = charArrayOf('a', '\r', '\n', 'b')
        assertEquals(3, skipNl(arr, 1, 4), "CRLF: skip past both \\r and \\n")

        val arr2 = charArrayOf('a', '\n', 'b')
        assertEquals(2, skipNl(arr2, 1, 3), "LF: skip past \\n")

        val arr3 = charArrayOf('a', '\r', 'b')
        assertEquals(2, skipNl(arr3, 1, 3), "CR: skip past \\r")

        val arr4 = charArrayOf('a', 'x', 'b')
        assertEquals(1, skipNl(arr4, 1, 3), "no newline: stay at pos")
    }
}
