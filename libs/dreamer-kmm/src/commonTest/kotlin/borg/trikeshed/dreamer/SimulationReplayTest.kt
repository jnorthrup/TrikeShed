package borg.trikeshed.dreamer

import borg.trikeshed.lib.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimulationReplayTest {

    private val header = "open_time,open,high,low,close,volume,close_time,quote_asset_volume,number_of_trades,taker_buy_base_volume,taker_buy_quote_volume,ignore"

    private fun csv(vararg rows: String): String = buildString {
        appendLine(header)
        rows.forEach { appendLine(it) }
    }

    @Test
    fun `replayCsv runs Binance archive csv through cursor simulation`() = runTest {
        val csvText = csv(
            "1704067200000,100.0,102.0,99.0,101.0,10.0,1704070799999,1010.0,12,5.0,505.0,0",
            "1704070800000,101.0,104.0,100.0,103.0,12.0,1704074399999,1236.0,18,6.0,618.0,0",
            "1704074400000,103.0,106.0,102.0,105.0,14.0,1704077999999,1470.0,24,7.0,735.0,0",
        )

        val replay = SimulationReplay(
            genome = defaultGenome(),
            mode = Mode.SHADOW,
            initialCapital = 10_000.0,
        )

        val result = replay.replayCsv(
            csvText = csvText,
            symbol = "BTCUSDT",
            timespan = TimeSpan.Hours1,
        )

        assertEquals("BTCUSDT", result.symbol)
        assertEquals(10_000.0, result.initialCapital)
        assertEquals(3, result.cycles.size)
        assertEquals(3, result.metrics.totalTicks)
        assertEquals(1704067200000L, result.cycles[0].openTime)
        assertTrue(result.metrics.totalReturn > 0.03, "totalReturn=${result.metrics.totalReturn}")
    }

    @Test
    fun `replayCsv with empty body produces empty BacktestResult`() = runTest {
        val csvText = header  // header only, no data rows

        val replay = SimulationReplay(
            genome = defaultGenome(),
            mode = Mode.SHADOW,
            initialCapital = 10_000.0,
        )

        val result = replay.replayCsv(
            csvText = csvText,
            symbol = "BTCUSDT",
            timespan = TimeSpan.Hours1,
        )

        assertEquals(0, result.cycles.size)
        assertEquals(0, result.metrics.totalTicks)
        assertEquals(0.0, result.metrics.totalReturn)
    }

    @Test
    fun `replayCsv with single row produces one cycle`() = runTest {
        val csvText = csv(
            "1704067200000,42000.0,42500.0,41800.0,42300.0,150.0,1704070799999,6345000.0,3200,75.0,3167250.0,0",
        )

        val replay = SimulationReplay(
            genome = defaultGenome(),
            mode = Mode.SHADOW,
            initialCapital = 10_000.0,
        )

        val result = replay.replayCsv(
            csvText = csvText,
            symbol = "BTCUSDT",
            timespan = TimeSpan.Hours1,
        )

        assertEquals(1, result.cycles.size)
        assertEquals(1, result.metrics.totalTicks)
        assertEquals(1704067200000L, result.cycles[0].openTime)
        // Single tick: totalReturn = 0 (no prior tick to compare against)
        assertEquals(0.0, result.metrics.totalReturn)
        assertEquals(0.0, result.metrics.sharpeRatio)
    }

    @Test
    fun `replayCsv with declining prices produces negative totalReturn`() = runTest {
        val csvText = csv(
            "1704067200000,100.0,101.0,98.0,100.0,10.0,1704070799999,1000.0,10,5.0,500.0,0",
            "1704070800000,100.0,99.0,96.0,97.0,12.0,1704074399999,1164.0,15,6.0,582.0,0",
            "1704074400000,97.0,96.0,92.0,93.0,14.0,1704077999999,1302.0,20,7.0,651.0,0",
        )

        val replay = SimulationReplay(
            genome = defaultGenome(),
            mode = Mode.SHADOW,
            initialCapital = 10_000.0,
        )

        val result = replay.replayCsv(
            csvText = csvText,
            symbol = "BTCUSDT",
            timespan = TimeSpan.Hours1,
        )

        assertEquals(3, result.cycles.size)
        assertTrue(result.metrics.totalReturn < 0.0,
            "Declining prices should produce negative totalReturn, got ${result.metrics.totalReturn}")
        assertTrue(result.metrics.maxDrawdown > 0.0,
            "Declining prices should produce positive maxDrawdown, got ${result.metrics.maxDrawdown}")
    }

    @Test
    fun `replayCsv with custom genome params produces different results than default`() = runTest {
        val csvText = csv(
            "1704067200000,100.0,102.0,99.0,101.0,10.0,1704070799999,1010.0,12,5.0,505.0,0",
            "1704070800000,101.0,104.0,100.0,103.0,12.0,1704074399999,1236.0,18,6.0,618.0,0",
            "1704074400000,103.0,106.0,102.0,105.0,14.0,1704077999999,1470.0,24,7.0,735.0,0",
            "1704078000000,105.0,108.0,104.0,107.0,16.0,1704081599999,1712.0,30,8.0,856.0,0",
            "1704081600000,107.0,110.0,106.0,109.0,18.0,1704085199999,1962.0,36,9.0,981.0,0",
        )

        val defaultResult = SimulationReplay(
            genome = defaultGenome(),
            mode = Mode.SHADOW,
            initialCapital = 10_000.0,
        ).replayCsv(csvText = csvText, symbol = "BTCUSDT", timespan = TimeSpan.Hours1)

        // Custom genome: very low surplus threshold → more harvest activity
        val aggressiveGenome = defaultGenome()
        aggressiveGenome[GenomeParam.MIN_SURPLUS_FOR_HARVEST] = 0.001
        aggressiveGenome[GenomeParam.HARVEST_TAKE_PERCENT] = 0.90

        val aggressiveResult = SimulationReplay(
            genome = aggressiveGenome,
            mode = Mode.SHADOW,
            initialCapital = 10_000.0,
        ).replayCsv(csvText = csvText, symbol = "BTCUSDT", timespan = TimeSpan.Hours1)

        // Aggressive genome should harvest more
        assertTrue(aggressiveResult.metrics.totalHarvested >= defaultResult.metrics.totalHarvested,
            "Aggressive genome should harvest at least as much as default: " +
                "aggressive=${aggressiveResult.metrics.totalHarvested} vs default=${defaultResult.metrics.totalHarvested}")
    }

    @Test
    fun `replayCsv toBacktestReport produces stable summary`() = runTest {
        val csvText = csv(
            "1704067200000,2500.0,2550.0,2490.0,2530.0,500.0,1704070799999,1265000.0,800,250.0,632500.0,0",
            "1704070800000,2530.0,2600.0,2520.0,2580.0,600.0,1704074399999,1548000.0,900,300.0,774000.0,0",
            "1704074400000,2580.0,2610.0,2550.0,2570.0,450.0,1704077999999,1156500.0,750,225.0,578250.0,0",
        )

        val replay = SimulationReplay(
            genome = defaultGenome(),
            mode = Mode.SHADOW,
            initialCapital = 5_000.0,
        )
        val result = replay.replayCsv(csvText = csvText, symbol = "ETHUSDT", timespan = TimeSpan.Hours1)
        val report = result.toBacktestReport()

        assertEquals("ETHUSDT", report.symbol)
        assertEquals(5_000.0, report.initialCapital, 0.001)
        assertEquals(3, report.totalTicks)
        assertTrue(report.finalEquity > 0.0, "finalEquity should be positive: ${report.finalEquity}")
        // Metrics should match
        assertEquals(result.metrics.totalReturn, report.totalReturn, 0.001)
        assertEquals(result.metrics.sharpeRatio, report.sharpeRatio, 0.001)
        assertEquals(result.metrics.maxDrawdown, report.maxDrawdown, 0.001)
    }

    /**
     * SimulationReplay.toBacktestReport() convenience: one call from CSV to report,
     * without explicit intermediate result variable.
     */
    @Test
    fun `toBacktestReport convenience composes csv and report in one expression`() = runTest {
        val csvText = csv(
            "1704067200000,100.0,102.0,99.0,101.0,10.0,1704070799999,1010.0,12,5.0,505.0,0",
            "1704070800000,101.0,104.0,100.0,103.0,12.0,1704074399999,1236.0,18,6.0,618.0,0",
        )
        val replay = SimulationReplay(genome = defaultGenome(), mode = Mode.SHADOW, initialCapital = 10_000.0)
        val report = replay.toBacktestReport(csvText, "BTCUSDT", TimeSpan.Hours1)

        assertEquals("BTCUSDT", report.symbol)
        assertEquals(10_000.0, report.initialCapital, 0.001)
        assertEquals(2, report.totalTicks)
        assertTrue(report.finalEquity > 0.0)
        assertTrue(report.totalReturn.isFinite())
        assertTrue(report.sharpeRatio.isFinite())
    }

    /**
     * SimulationReplay with declining prices produces negative totalReturn and
     * positive maxDrawdown — pinned via toBacktestReport().
     */
    @Test
    fun `toBacktestReport declining prices produces negative return and drawdown`() = runTest {
        val csvText = csv(
            "1704067200000,100.0,101.0,98.0,100.0,10.0,1704070799999,1000.0,10,5.0,500.0,0",
            "1704070800000,100.0,99.0,96.0,97.0,12.0,1704074399999,1164.0,15,6.0,582.0,0",
            "1704074400000,97.0,96.0,92.0,93.0,14.0,1704077999999,1302.0,20,7.0,651.0,0",
        )
        val report = SimulationReplay(
            genome = defaultGenome(),
            mode = Mode.SHADOW,
            initialCapital = 10_000.0,
        ).toBacktestReport(csvText, "BTCUSDT", TimeSpan.Hours1)

        assertTrue(report.totalReturn < 0.0,
            "Declining prices → negative totalReturn: ${report.totalReturn}")
        assertTrue(report.maxDrawdown > 0.0,
            "Declining prices → drawdown: ${report.maxDrawdown}")
        assertEquals(3, report.totalTicks)
    }
}
