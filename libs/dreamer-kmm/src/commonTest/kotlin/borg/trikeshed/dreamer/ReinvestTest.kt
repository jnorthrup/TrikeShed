package borg.trikeshed.dreamer

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.lib.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for reinvestment in the TradingEngine.
 *
 * Reinvest logic:
 *   1. After harvest, accumulated cash is available for reinvestment
 *   2. HARVEST_ALLOC_REINVEST_PERCENT determines what fraction of harvest is reinvested
 *   3. Reinvest targets symbols with negative deviation (below baseline)
 *   4. MIN_NEGATIVE_DEVIATION_FOR_REINVEST sets threshold for qualifying
 *   5. MIN_REINVEST_BUY_USD sets minimum buy amount
 *   6. Reinvested amounts are recorded in the engine result
 *
 * This makes reinvest genome parameters evolvable in stochastic back-testing.
 */
class ReinvestTest {

    private fun klinesToCursor(klines: List<Kline>): Cursor {
        val block = KlineBlock.mutable()
        klines.forEach { block.append(it) }
        return block.seal().asCursor()
    }

    @Test
    fun `reinvest buys the dip when symbol has negative deviation`() = runTest {
        val genome = defaultGenome()
        genome[GenomeParam.MIN_SURPLUS_FOR_HARVEST] = 0.001
        genome[GenomeParam.HARVEST_TAKE_PERCENT] = 0.50
        genome[GenomeParam.HARVEST_ALLOC_REINVEST_PERCENT] = 0.80  // 80% of harvest reinvested
        genome[GenomeParam.MIN_NEGATIVE_DEVIATION_FOR_REINVEST] = 0.05  // 5% drop needed
        genome[GenomeParam.MIN_REINVEST_BUY_USD] = 1.0

        val engine = TradingEngine(genome, Mode.SHADOW, initialCapital = 10_000.0)

        // Two symbols: ETH will rise (harvest source), BTC will dip (reinvest target)
        // Cycle 1: set baselines
        engine.update(listOf(
            PortfolioRow("ETHUSDT", 100.0, 100.0, 5_000.0),
            PortfolioRow("BTCUSDT", 100.0, 100.0, 5_000.0),
        ), null, 0.0, null)

        // Cycle 2: ETH rises 20% → harvest triggered; BTC drops 10% → reinvest target
        val result = engine.update(listOf(
            PortfolioRow("ETHUSDT", 120.0, 120.0, 6_000.0),  // +20%, harvest
            PortfolioRow("BTCUSDT", 90.0, 90.0, 4_500.0),     // -10%, reinvest target
        ), null, 0.0, null)

        assertTrue(result.anyTradesThisCycle, "Should have trades (harvest + reinvest)")
        assertTrue(result.harvestedAmount > 0, "Should have harvested from ETH")
        assertTrue(result.reinvestedAmount > 0, "Should have reinvested into BTC")
        assertTrue(engine.holdings.containsKey("BTCUSDT"), "Should hold BTC from reinvest")
    }

    @Test
    fun `reinvest respects minimum buy size`() = runTest {
        val genome = defaultGenome()
        genome[GenomeParam.MIN_SURPLUS_FOR_HARVEST] = 0.001
        genome[GenomeParam.HARVEST_TAKE_PERCENT] = 0.10  // small harvest
        genome[GenomeParam.HARVEST_ALLOC_REINVEST_PERCENT] = 0.80
        genome[GenomeParam.MIN_NEGATIVE_DEVIATION_FOR_REINVEST] = 0.05
        genome[GenomeParam.MIN_REINVEST_BUY_USD] = 1000.0  // very large minimum

        val engine = TradingEngine(genome, Mode.SHADOW, initialCapital = 10_000.0)

        // Cycle 1: baselines
        engine.update(listOf(
            PortfolioRow("ETHUSDT", 100.0, 100.0, 5_000.0),
            PortfolioRow("BTCUSDT", 100.0, 100.0, 5_000.0),
        ), null, 0.0, null)

        // Cycle 2: small harvest, BTC dips but reinvest is below minimum
        val result = engine.update(listOf(
            PortfolioRow("ETHUSDT", 105.0, 105.0, 5_250.0),  // +5%, small harvest
            PortfolioRow("BTCUSDT", 90.0, 90.0, 4_500.0),     // -10%
        ), null, 0.0, null)

        assertEquals(0.0, result.reinvestedAmount, 0.001,
            "Should not reinvest below MIN_REINVEST_BUY_USD")
    }

    @Test
    fun `reinvest does not trigger without negative deviation`() = runTest {
        val genome = defaultGenome()
        genome[GenomeParam.MIN_SURPLUS_FOR_HARVEST] = 0.001
        genome[GenomeParam.HARVEST_TAKE_PERCENT] = 0.50
        genome[GenomeParam.HARVEST_ALLOC_REINVEST_PERCENT] = 0.80
        genome[GenomeParam.MIN_NEGATIVE_DEVIATION_FOR_REINVEST] = 0.05
        genome[GenomeParam.MIN_REINVEST_BUY_USD] = 1.0

        val engine = TradingEngine(genome, Mode.SHADOW, initialCapital = 10_000.0)

        // Cycle 1: baselines
        engine.update(listOf(
            PortfolioRow("ETHUSDT", 100.0, 100.0, 5_000.0),
            PortfolioRow("BTCUSDT", 100.0, 100.0, 5_000.0),
        ), null, 0.0, null)

        // Cycle 2: ETH rises → harvest, but BTC also rises (no negative deviation)
        val result = engine.update(listOf(
            PortfolioRow("ETHUSDT", 120.0, 120.0, 6_000.0),
            PortfolioRow("BTCUSDT", 110.0, 110.0, 5_500.0),  // +10%, not a dip
        ), null, 0.0, null)

        assertEquals(0.0, result.reinvestedAmount, 0.001,
            "Should not reinvest when no symbol has negative deviation")
    }

    @Test
    fun `reinvest zero percent disables reinvestment`() = runTest {
        val genome = defaultGenome()
        genome[GenomeParam.MIN_SURPLUS_FOR_HARVEST] = 0.001
        genome[GenomeParam.HARVEST_TAKE_PERCENT] = 0.50
        genome[GenomeParam.HARVEST_ALLOC_REINVEST_PERCENT] = 0.0  // disabled
        genome[GenomeParam.MIN_NEGATIVE_DEVIATION_FOR_REINVEST] = 0.05
        genome[GenomeParam.MIN_REINVEST_BUY_USD] = 1.0

        val engine = TradingEngine(genome, Mode.SHADOW, initialCapital = 10_000.0)

        // Cycle 1: baselines
        engine.update(listOf(
            PortfolioRow("ETHUSDT", 100.0, 100.0, 5_000.0),
            PortfolioRow("BTCUSDT", 100.0, 100.0, 5_000.0),
        ), null, 0.0, null)

        // Cycle 2: harvest from ETH, BTC dips
        val result = engine.update(listOf(
            PortfolioRow("ETHUSDT", 120.0, 120.0, 6_000.0),
            PortfolioRow("BTCUSDT", 90.0, 90.0, 4_500.0),
        ), null, 0.0, null)

        assertTrue(result.harvestedAmount > 0, "Should still harvest")
        assertEquals(0.0, result.reinvestedAmount, 0.001,
            "Should not reinvest when HARVEST_ALLOC_REINVEST_PERCENT is 0")
    }

    @Test
    fun `reinvest splits across multiple dip symbols`() = runTest {
        val genome = defaultGenome()
        genome[GenomeParam.MIN_SURPLUS_FOR_HARVEST] = 0.001
        genome[GenomeParam.HARVEST_TAKE_PERCENT] = 0.50
        genome[GenomeParam.HARVEST_ALLOC_REINVEST_PERCENT] = 0.80
        genome[GenomeParam.MIN_NEGATIVE_DEVIATION_FOR_REINVEST] = 0.05
        genome[GenomeParam.MIN_REINVEST_BUY_USD] = 1.0

        val engine = TradingEngine(genome, Mode.SHADOW, initialCapital = 15_000.0)

        // Cycle 1: baselines for 3 symbols
        engine.update(listOf(
            PortfolioRow("ETHUSDT", 100.0, 100.0, 5_000.0),
            PortfolioRow("BTCUSDT", 100.0, 100.0, 5_000.0),
            PortfolioRow("SOLUSDT", 100.0, 100.0, 5_000.0),
        ), null, 0.0, null)

        // Cycle 2: ETH rises → harvest, BTC and SOL dip
        val result = engine.update(listOf(
            PortfolioRow("ETHUSDT", 130.0, 130.0, 6_500.0),  // +30%
            PortfolioRow("BTCUSDT", 90.0, 90.0, 4_500.0),    // -10%
            PortfolioRow("SOLUSDT", 85.0, 85.0, 4_250.0),     // -15%
        ), null, 0.0, null)

        assertTrue(result.reinvestedAmount > 0, "Should have reinvested")
        assertTrue(engine.holdings.containsKey("BTCUSDT"), "Should hold BTC from reinvest")
        assertTrue(engine.holdings.containsKey("SOLUSDT"), "Should hold SOL from reinvest")
    }

    @Test
    fun `simulateMultiSymbolTicks reinvest affects backtest metrics`() = runTest {
        // Two symbols: ETH rises (harvest source), BTC dips (reinvest target).
        // Each openTime has exactly one ETH row and one BTC row.
        // Tick 0 (1000L): baselines set for both
        // Tick 1 (2000L): ETH=120 (+20% → harvest), BTC=90 (-10% → dip target)
        // Tick 2 (3000L): ETH=130 (+33% from original → harvest), BTC=80 (-20% → dip)
        // Tick 3 (4000L): ETH=110 (dip), BTC=90 (recovery from trough but still below baseline)
        // Tick 4 (5000L): ETH=125 (recovery), BTC=100 (back to baseline)
        val klines = listOf(
            // Tick 0: baselines
            Kline("ETHUSDT", TimeSpan.Hours1, 1000L, 100.0, 101.0, 99.0, 100.0, 50.0),
            Kline("BTCUSDT", TimeSpan.Hours1, 1000L, 100.0, 101.0, 99.0, 100.0, 50.0),
            // Tick 1: ETH harvest, BTC dip
            Kline("ETHUSDT", TimeSpan.Hours1, 2000L, 100.0, 120.0, 99.0, 120.0, 50.0),
            Kline("BTCUSDT", TimeSpan.Hours1, 2000L, 100.0, 95.0,  88.0,  90.0, 50.0),
            // Tick 2: ETH harvest again, BTC deeper dip
            Kline("ETHUSDT", TimeSpan.Hours1, 3000L, 120.0, 135.0, 119.0, 130.0, 50.0),
            Kline("BTCUSDT", TimeSpan.Hours1, 3000L,  90.0, 88.0,  78.0,  80.0, 50.0),
            // Tick 3: ETH dips, BTC recovers somewhat
            Kline("ETHUSDT", TimeSpan.Hours1, 4000L, 130.0, 115.0, 108.0, 110.0, 50.0),
            Kline("BTCUSDT", TimeSpan.Hours1, 4000L,  80.0, 95.0,  79.0,  90.0, 50.0),
            // Tick 4: ETH recovers, BTC back to baseline
            Kline("ETHUSDT", TimeSpan.Hours1, 5000L, 110.0, 130.0, 109.0, 125.0, 50.0),
            Kline("BTCUSDT", TimeSpan.Hours1, 5000L,  90.0, 105.0, 89.0, 100.0, 50.0),
        )

        val cursor = klinesToCursor(klines)

        // With reinvest: 80% of harvest proceeds go into dip symbols
        val genomeReinvest = defaultGenome()
        genomeReinvest[GenomeParam.MIN_SURPLUS_FOR_HARVEST] = 0.001
        genomeReinvest[GenomeParam.HARVEST_TAKE_PERCENT] = 0.50
        genomeReinvest[GenomeParam.HARVEST_ALLOC_REINVEST_PERCENT] = 0.80
        genomeReinvest[GenomeParam.MIN_NEGATIVE_DEVIATION_FOR_REINVEST] = 0.05
        genomeReinvest[GenomeParam.MIN_REINVEST_BUY_USD] = 1.0

        val engine1 = TradingEngine(genomeReinvest, Mode.SHADOW, initialCapital = 10_000.0)
        simulateMultiSymbolTicks(cursor, engine1, initialCapital = 10_000.0)

        // Without reinvest: all harvest proceeds stay as cash
        val genomeNoReinvest = defaultGenome()
        genomeNoReinvest[GenomeParam.MIN_SURPLUS_FOR_HARVEST] = 0.001
        genomeNoReinvest[GenomeParam.HARVEST_TAKE_PERCENT] = 0.50
        genomeNoReinvest[GenomeParam.HARVEST_ALLOC_REINVEST_PERCENT] = 0.0  // disabled

        val engine2 = TradingEngine(genomeNoReinvest, Mode.SHADOW, initialCapital = 10_000.0)
        simulateMultiSymbolTicks(cursor, engine2, initialCapital = 10_000.0)

        // With reinvest, engine1 deploys harvested cash into BTC dips.
        // Engine2 keeps all cash uninvested.
        val cashDiff = engine1.cashBalance != engine2.cashBalance
        val holdingsDiff = engine1.holdings != engine2.holdings
        assertTrue(cashDiff || holdingsDiff,
            "Reinvest should change engine state: cash1=${engine1.cashBalance} vs cash2=${engine2.cashBalance}, holdings differ=$holdingsDiff")
        assertTrue(engine1.holdings.containsKey("BTCUSDT"),
            "Engine with reinvest should hold BTCUSDT: holdings=${engine1.holdings}")
    }
}
