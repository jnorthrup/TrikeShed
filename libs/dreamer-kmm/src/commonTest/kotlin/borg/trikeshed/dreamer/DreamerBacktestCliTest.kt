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
}
