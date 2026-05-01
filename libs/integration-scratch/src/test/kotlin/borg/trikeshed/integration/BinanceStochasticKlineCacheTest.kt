package borg.trikeshed.integration

import borg.trikeshed.couch.kline.Kline
import borg.trikeshed.couch.kline.KlineBlock
import borg.trikeshed.couch.kline.TimeSpan
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.lib.size
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class BinanceStochasticKlineCacheTest {

    @Test
    fun `process local stochastic cache returns same value and calls provider once for same key`() = runBlocking {
        ProcessLocalBinanceStochasticCache.clearForTests()
        val key = BinanceKlineKey(
            symbol = "BTCUSDT",
            interval = "1h",
            startDate = LocalDate.parse("2024-01-01"),
            endDate = LocalDate.parse("2024-01-01"),
        )
        var loads = 0
        val provider = object : BinanceKlineProvider {
            override suspend fun open(key: BinanceKlineKey): Cursor {
                loads++
                return syntheticTwentyRowCursor(key)
            }
        }

        val first = ProcessLocalBinanceStochasticCache.getOrLoad(key, provider)
        val second = ProcessLocalBinanceStochasticCache.getOrLoad(key, provider)

        assertSame(first, second)
        assertEquals(1, loads)
        assertEquals(1, ProcessLocalBinanceStochasticCache.sizeForTests())
        assertEquals(20, first.cursor.size)
        assertEquals(20, first.stochastic.k.size)
    }

    @Test
    fun `process local stochastic cache separates different symbols`() = runBlocking {
        ProcessLocalBinanceStochasticCache.clearForTests()
        val btc = BinanceKlineKey("BTCUSDT", "1h", LocalDate.parse("2024-01-01"), LocalDate.parse("2024-01-01"))
        val eth = BinanceKlineKey("ETHUSDT", "1h", LocalDate.parse("2024-01-01"), LocalDate.parse("2024-01-01"))
        var loads = 0
        val provider = object : BinanceKlineProvider {
            override suspend fun open(key: BinanceKlineKey): Cursor {
                loads++
                return syntheticTwentyRowCursor(key)
            }
        }

        ProcessLocalBinanceStochasticCache.getOrLoad(btc, provider)
        ProcessLocalBinanceStochasticCache.getOrLoad(eth, provider)

        assertEquals(2, loads)
        assertEquals(2, ProcessLocalBinanceStochasticCache.sizeForTests())
    }

    @Test
    fun `process local stochastic cache coalesces concurrent loads for same key`() = runBlocking {
        ProcessLocalBinanceStochasticCache.clearForTests()
        val key = BinanceKlineKey("BTCUSDT", "1h", LocalDate.parse("2024-01-01"), LocalDate.parse("2024-01-01"))
        var loads = 0
        val provider = object : BinanceKlineProvider {
            override suspend fun open(key: BinanceKlineKey): Cursor {
                loads++
                delay(100)
                return syntheticTwentyRowCursor(key)
            }
        }

        coroutineScope {
            List(10) { async { ProcessLocalBinanceStochasticCache.getOrLoad(key, provider) } }.awaitAll()
        }

        assertEquals(1, loads)
        assertEquals(1, ProcessLocalBinanceStochasticCache.sizeForTests())
    }

    @Test
    fun `parseBinanceStochasticMainArgs parses symbol interval dates and throttle`() {
        val parsed = parseBinanceStochasticMainArgs(
            arrayOf(
                "--symbol", "ETHUSDT",
                "--interval", "1h",
                "--start", "2024-01-01",
                "--end", "2024-01-03",
                "--max-concurrent-fetches", "2",
            ),
        )

        assertEquals("ETHUSDT", parsed.symbol)
        assertEquals("1h", parsed.interval)
        assertEquals(LocalDate.parse("2024-01-01"), parsed.startDate)
        assertEquals(LocalDate.parse("2024-01-03"), parsed.endDate)
        assertEquals(2, parsed.maxConcurrentFetches)
    }

    @Test
    fun `runBinanceStochasticKlineCache loads one process local cache entry`() = runBlocking {
        ProcessLocalBinanceStochasticCache.clearForTests()
        val args = BinanceStochasticMainArgs(
            symbol = "BTCUSDT",
            interval = "1h",
            startDate = LocalDate.parse("2024-01-01"),
            endDate = LocalDate.parse("2024-01-01"),
            maxConcurrentFetches = 1,
        )

        val loaded = runBinanceStochasticKlineCache(
            args = args,
            provider = object : BinanceKlineProvider {
                override suspend fun open(key: BinanceKlineKey): Cursor = syntheticTwentyRowCursor(key)
            },
        )

        assertEquals("BTCUSDT", loaded.key.kline.symbol)
        assertEquals(20, loaded.cursor.size)
        assertEquals(20, loaded.stochastic.k.size)
        assertEquals(1, ProcessLocalBinanceStochasticCache.sizeForTests())
    }
}

private fun syntheticTwentyRowCursor(key: BinanceKlineKey): Cursor {
    val block = KlineBlock.mutable()
    repeat(20) { i ->
        block.append(
            Kline(
                symbol = key.symbol,
                timespan = TimeSpan.Hours1,
                openTime = 1_704_067_200_000L + i * 3_600_000L,
                open = 100.0 + i,
                high = 105.0 + i,
                low = 95.0 + i,
                close = 101.0 + i,
                volume = 1000.0 + i,
            ),
        )
    }
    block.seal()
    return BinanceKlineSource(
        symbol = key.symbol,
        interval = key.interval,
        startDate = key.startDate,
        endDate = key.endDate,
    ).blocksToCursor(listOf(block))
}
