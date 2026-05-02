package borg.trikeshed.dreamer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DreamerBacktestCliTest {
    @Test
    fun `parseDreamerBacktestArgs accepts flags and timespan aliases`() {
        val args = parseDreamerBacktestArgs(
            listOf(
                "--csv", "/tmp/dreamer.csv",
                "--symbol", "BTCUSDT",
                "--timespan", "1M",
                "--initial-capital", "7500",
            ),
        )

        assertEquals("/tmp/dreamer.csv", args.csvPath)
        assertEquals("BTCUSDT", args.symbol)
        assertEquals(TimeSpan.Months1, args.timespan)
        assertEquals(7500.0, args.initialCapital)
    }

    @Test
    fun `parseDreamerBacktestArgs accepts positional fallback and formats report`() {
        val args = parseDreamerBacktestArgs(
            listOf("/tmp/dreamer.csv", "ETHUSDT", "Hours1", "5000"),
        )

        assertEquals("/tmp/dreamer.csv", args.csvPath)
        assertEquals("ETHUSDT", args.symbol)
        assertEquals(TimeSpan.Hours1, args.timespan)
        assertEquals(5000.0, args.initialCapital)

        val report = BacktestReport(
            symbol = "ETHUSDT",
            initialCapital = 5000.0,
            finalEquity = 5250.0,
            totalReturn = 0.05,
            sharpeRatio = 1.2,
            sortinoRatio = 1.4,
            maxDrawdown = 0.02,
            maxDrawdownTicks = 3,
            totalTrades = 4,
            totalHarvested = 12.5,
            totalTicks = 7,
        )
        val rendered = formatDreamerBacktestReport(args, report)

        assertTrue(rendered.contains("csvPath=/tmp/dreamer.csv"))
        assertTrue(rendered.contains("symbol=ETHUSDT"))
        assertTrue(rendered.contains("timespan=Hours1 (1h)"))
        assertTrue(rendered.contains("finalEquity=5250.0"))
    }

    @Test
    fun `parseDreamerBacktestArgs throws on missing csv path`() {
        val ex = kotlin.test.assertFailsWith<IllegalArgumentException> {
            parseDreamerBacktestArgs(listOf("--symbol", "BTCUSDT", "--timespan", "1h"))
        }
        assertTrue(ex.message?.contains("Missing CSV path") == true,
            "Should complain about missing CSV: ${ex.message}")
    }

    @Test
    fun `parseDreamerBacktestArgs throws on missing symbol`() {
        val ex = kotlin.test.assertFailsWith<IllegalArgumentException> {
            parseDreamerBacktestArgs(listOf("--csv", "/tmp/data.csv", "--timespan", "1h"))
        }
        assertTrue(ex.message?.contains("Missing symbol") == true,
            "Should complain about missing symbol: ${ex.message}")
    }

    @Test
    fun `parseDreamerBacktestArgs throws on missing timespan`() {
        val ex = kotlin.test.assertFailsWith<IllegalArgumentException> {
            parseDreamerBacktestArgs(listOf("--csv", "/tmp/data.csv", "--symbol", "BTCUSDT"))
        }
        assertTrue(ex.message?.contains("Missing timespan") == true,
            "Should complain about missing timespan: ${ex.message}")
    }

    @Test
    fun `parseDreamerBacktestArgs throws on unknown timespan`() {
        val ex = kotlin.test.assertFailsWith<IllegalArgumentException> {
            parseDreamerBacktestArgs(listOf("--csv", "/tmp/data.csv", "--symbol", "BTCUSDT", "--timespan", "99x"))
        }
        assertTrue(ex.message?.contains("Unknown timespan") == true,
            "Should complain about unknown timespan: ${ex.message}")
    }

    @Test
    fun `runDreamerBacktest produces BacktestReport from CSV text`() {
        val csv = """
            open_time,open,high,low,close,volume,close_time,quote_asset_volume,number_of_trades,taker_buy_base_volume,taker_buy_quote_volume,ignore
            1704067200000,42000.0,42500.0,41800.0,42300.0,150.0,1704070799999,6345000.0,3200,75.0,3167250.0,0
            1704070800000,42300.0,43100.0,42100.0,42900.0,180.0,1704074399999,7722000.0,4100,90.0,3861000.0,0
            1704074400000,42900.0,43200.0,42500.0,42800.0,140.0,1704077999999,5992000.0,2800,70.0,2996000.0,0
        """.trimIndent()
        val args = DreamerBacktestArgs(
            csvPath = "test.csv",
            symbol = "BTCUSDT",
            timespan = TimeSpan.Hours1,
            initialCapital = 10_000.0,
        )
        val report = runDreamerBacktest(csv, args)

        assertEquals("BTCUSDT", report.symbol)
        assertEquals(10_000.0, report.initialCapital, 0.001)
        assertEquals(3, report.totalTicks)
        assertTrue(report.finalEquity > 0.0, "finalEquity should be positive: ${report.finalEquity}")
        assertTrue(report.totalReturn.isFinite(), "totalReturn should be finite: ${report.totalReturn}")
        assertTrue(report.sharpeRatio.isFinite(), "sharpeRatio should be finite: ${report.sharpeRatio}")
    }

    @Test
    fun `runDreamerBacktest and formatDreamerBacktestReport compose end-to-end`() {
        val csv = """
            open_time,open,high,low,close,volume,close_time,quote_asset_volume,number_of_trades,taker_buy_base_volume,taker_buy_quote_volume,ignore
            1704067200000,100.0,102.0,99.0,101.0,10.0,1704070799999,1010.0,12,5.0,505.0,0
            1704070800000,101.0,104.0,100.0,103.0,12.0,1704074399999,1236.0,18,6.0,618.0,0
        """.trimIndent()
        val args = parseDreamerBacktestArgs(listOf("test.csv", "BTCUSDT", "Hours1", "5000"))
        val report = runDreamerBacktest(csv, args)
        val rendered = formatDreamerBacktestReport(args, report)

        assertTrue(rendered.startsWith("dreamer backtest"))
        assertTrue(rendered.contains("csvPath=test.csv"))
        assertTrue(rendered.contains("symbol=BTCUSDT"))
        assertTrue(rendered.contains("timespan=Hours1 (1h)"))
        assertTrue(rendered.contains("initialCapital=5000.0"))
        assertTrue(rendered.contains("totalTicks=2"))
    }
}
