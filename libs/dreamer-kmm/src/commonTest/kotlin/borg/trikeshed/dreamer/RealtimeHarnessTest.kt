package borg.trikeshed.dreamer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RealtimeHarnessTest {
    @Test
    fun `stochastic bag is deterministic and spans pair cursors`() = runTest {
        val left = source("BTC", prices = listOf(100.0, 101.0, 102.0, 103.0))
        val right = source("ETH", prices = listOf(10.0, 10.5, 10.4, 10.7))

        val first = StochasticBag(listOf(left, right), seed = 7).select(maxWindows = 2, spanLength = 2)
        val second = StochasticBag(listOf(left, right), seed = 7).select(maxWindows = 2, spanLength = 2)

        assertEquals(first.windows, second.windows)
        assertEquals(first.spans, second.spans)
        assertTrue(first.spans.isNotEmpty())
        assertTrue(first.windows.all { it.endExclusive > it.start })
    }

    @Test
    fun `stochastic bag advances repeated draws from one seeded sampler`() = runTest {
        val left = source("BTC", prices = (0 until 16).map { 100.0 + it })
        val right = source("ETH", prices = (0 until 16).map { 10.0 + it * 0.1 })
        val bag = StochasticBag(listOf(left, right), seed = 19)

        val first = bag.select(maxWindows = 2, spanLength = 4)
        val second = bag.select(maxWindows = 2, spanLength = 4)
        val replay = StochasticBag(listOf(left, right), seed = 19).select(maxWindows = 2, spanLength = 4)

        assertEquals(first, replay)
        assertTrue(first.windows != second.windows)
        assertEquals(first.spans, second.spans)
        assertTrue(first.windows.all { it.rowCount == 4 })
    }

    @Test
    fun `realtime harness replays sealed kline blocks through Dreamer agent`() = runTest {
        val btc = block("BTC", listOf(100.0, 102.0, 104.0, 106.0))
        val eth = block("ETH", listOf(10.0, 9.8, 10.1, 10.4))
        val harness = RealtimeHarness(
            genome = defaultGenome(),
            initialCapital = 10_000.0,
            stochasticSeed = 11,
            stochasticSpanLength = 2,
        )

        val result = harness.replay(
            listOf(
                HarnessReplayInput(klineSeriesKey("BTC", "USDT", TimeSpan.Minutes1), btc),
                HarnessReplayInput(klineSeriesKey("ETH", "USDT", TimeSpan.Minutes1), eth),
            )
        )

        assertEquals(4, result.cycles.size)
        assertEquals(0, result.cycles.first().frame.tick)
        assertEquals(2, result.cycles.first().frame.rows.size)
        assertTrue(result.finalTotalValue > 0.0)
        assertTrue(result.cycles.all { it.frame.bag.windows.size == 1 })
        assertTrue(result.walletJournal.any { it.action == WalletAction.MARK_TO_MARKET })
    }

    @Test
    fun `genome trainer promotes from one dimensional timeseries replay`() = runTest {
        val key = klineSeriesKey("BTC", "USDT", TimeSpan.Minutes1)
        val trainer = GenomeTrainer(initialCapital = 10_000.0)

        val result = trainer.trainOneDimensional(
            key = key,
            block = block("BTC", listOf(100.0, 101.0, 103.0, 102.0, 105.0)),
        )

        assertEquals(4, result.evaluations.size)
        assertEquals(Genome.WIDTH, result.champion.doubles.size)
        assertTrue(result.evaluations.first().fitness >= result.evaluations.last().fitness)
    }

    @Test
    fun `stochastic trainer emits full bag span training snapshot`() = runTest {
        val trainer = StochasticBagSpanTrainer(
            StochasticTrainingConfig(
                bases = listOf("BTC", "ETH", "SOL"),
                rowsPerSeries = 48,
                populationSize = 4,
                spanLength = 8,
                initialCapital = 100.0,
                seed = 23,
            )
        )

        val first = trainer.runGeneration()
        val second = trainer.runGeneration()

        assertEquals(1, first.generation)
        assertEquals(2, second.generation)
        assertEquals(3, first.pairCount)
        assertEquals(4, first.evaluations)
        assertTrue(first.totalWindows > 0)
        assertTrue(first.totalSpans > 0)
        assertTrue(first.bestTotalValue > 0.0)
        assertEquals(first.bestTotalValue - 100.0, first.bestProfit, 0.001)
        assertTrue(first.bestDrawdown >= 0.0)
        assertTrue(first.sampleWindows.isNotEmpty())
        assertTrue(first.sampleSpans.isNotEmpty())
        assertTrue(first.bestFitness == first.bestFitness)
    }

    private fun source(base: String, prices: List<Double>): KlineSeriesSource {
        val key = klineSeriesKey(base, "USDT", TimeSpan.Minutes1)
        return KlineSeriesSource(key, block(base, prices).asCursor())
    }

    private fun block(base: String, prices: List<Double>): KlineBlock {
        val block = KlineBlock.mutable(TimeSpan.Minutes1)
        prices.forEachIndexed { index, close ->
            val open = if (index == 0) close else prices[index - 1]
            block.append(
                Kline(
                    symbol = "${base}USDT",
                    timespan = TimeSpan.Minutes1,
                    openTime = 1_704_067_200_000L + (index * 60_000L),
                    open = open,
                    high = maxOf(open, close) + 1.0,
                    low = minOf(open, close) - 1.0,
                    close = close,
                    volume = 100.0 + index,
                )
            )
        }
        return block.seal()
    }
}
