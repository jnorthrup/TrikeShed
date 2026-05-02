package borg.trikeshed.dreamer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [StochasticBag] — stochastic window/span selection
 * over kline cursors.
 *
 * Covers: select(), rowSpan(), spans(), spanWindows(), caching, edge cases.
 */
class StochasticBagTest {

    // ── rowSpan ──────────────────────────────────────────────────────────────

    @Test
    fun `rowSpan extracts correct openTime range and rowCount`() {
        val source = source("BTC", prices = listOf(100.0, 101.0, 102.0, 103.0, 104.0))
        val bag = StochasticBag(listOf(source), seed = 1)

        val span = bag.run { source.rowSpan(1, 4) }
        assertEquals(source.key, span.key)
        assertEquals(1, span.start)
        assertEquals(4, span.endExclusive)
        assertEquals(3, span.rowCount)
        // openTime = 1_704_067_200_000 + index * 60_000
        assertEquals(1_704_067_200_000L + 60_000L, span.firstOpenTime)
        assertEquals(1_704_067_200_000L + 3 * 60_000L, span.lastOpenTime)
    }

    @Test
    fun `rowSpan single row returns start equals endExclusive minus one`() {
        val source = source("BTC", prices = listOf(100.0, 101.0, 102.0))
        val bag = StochasticBag(listOf(source), seed = 1)

        val span = bag.run { source.rowSpan(2, 3) }
        assertEquals(2, span.start)
        assertEquals(3, span.endExclusive)
        assertEquals(1, span.rowCount)
    }

    // ── select ───────────────────────────────────────────────────────────────

    @Test
    fun `select returns empty windows when maxWindows is zero`() = runTest {
        val source = source("BTC", prices = listOf(100.0, 101.0, 102.0))
        val bag = StochasticBag(listOf(source), seed = 1)

        val result = bag.select(maxWindows = 0, spanLength = 2)
        assertTrue(result.windows.isEmpty())
    }

    @Test
    fun `select window rowCount equals spanLength when source has enough rows`() = runTest {
        val source = source("BTC", prices = (0 until 20).map { 100.0 + it })
        val bag = StochasticBag(listOf(source), seed = 42)

        val result = bag.select(maxWindows = 5, spanLength = 8)
        assertTrue(result.windows.all { it.rowCount == 8 })
    }

    @Test
    fun `select caps spanLength to source size`() = runTest {
        val source = source("BTC", prices = listOf(100.0, 101.0))
        val bag = StochasticBag(listOf(source), seed = 1)

        val result = bag.select(maxWindows = 1, spanLength = 10)
        // Should produce a window but with at most 2 rows
        assertEquals(1, result.windows.size)
        assertTrue(result.windows[0].rowCount <= 2)
    }

    @Test
    fun `select determinism — same seed produces identical results`() = runTest {
        val left = source("BTC", prices = (0 until 16).map { 100.0 + it })
        val right = source("ETH", prices = (0 until 16).map { 10.0 + it * 0.1 })

        val bag1 = StochasticBag(listOf(left, right), seed = 77)
        val bag2 = StochasticBag(listOf(left, right), seed = 77)

        val r1 = bag1.select(maxWindows = 3, spanLength = 4)
        val r2 = bag2.select(maxWindows = 3, spanLength = 4)

        assertEquals(r1.windows, r2.windows)
        assertEquals(r1.spans, r2.spans)
    }

    // ── spans ────────────────────────────────────────────────────────────────

    @Test
    fun `spans returns empty for single source`() {
        val source = source("BTC", prices = listOf(100.0, 101.0, 102.0))
        val bag = StochasticBag(listOf(source), seed = 1)

        val spans = bag.spans()
        assertTrue(spans.isEmpty())
    }

    @Test
    fun `spans returns pairwise windows for two sources`() {
        val left = source("BTC", prices = listOf(100.0, 101.0, 102.0, 103.0))
        val right = source("ETH", prices = listOf(10.0, 10.5, 10.4, 10.7))
        val bag = StochasticBag(listOf(left, right), seed = 1)

        val spans = bag.spans()
        assertTrue(spans.isNotEmpty())
        // Each span should reference both keys
        assertTrue(spans.all { it.left == left.key && it.right == right.key })
    }

    @Test
    fun `spans caching — second call returns same reference`() {
        val left = source("BTC", prices = listOf(100.0, 101.0, 102.0, 103.0))
        val right = source("ETH", prices = listOf(10.0, 10.5, 10.4, 10.7))
        val bag = StochasticBag(listOf(left, right), seed = 1)

        val first = bag.spans()
        val second = bag.spans()
        // Cached: should be the same list reference
        assertEquals(first, second)
        assertTrue(bag.cachedSpans != null)
    }

    @Test
    fun `spans with three sources produces C(3,2)=3 pairs`() {
        val a = source("BTC", prices = listOf(100.0, 101.0, 102.0))
        val b = source("ETH", prices = listOf(10.0, 10.5, 10.4))
        val c = source("SOL", prices = listOf(142.0, 143.0, 144.0))
        val bag = StochasticBag(listOf(a, b, c), seed = 1)

        val spans = bag.spans()
        assertEquals(3, spans.size)
        val pairs: Set<Pair<String, String>> = spans.map { it.left.a.a to it.right.a.a }.toSet()
        assertEquals(setOf("BTC" to "ETH", "BTC" to "SOL", "ETH" to "SOL"), pairs)
    }

    // ── select — span selection ──────────────────────────────────────────────

    @Test
    fun `select includes one span when sources have two or more`() = runTest {
        val left = source("BTC", prices = listOf(100.0, 101.0, 102.0, 103.0))
        val right = source("ETH", prices = listOf(10.0, 10.5, 10.4, 10.7))
        val bag = StochasticBag(listOf(left, right), seed = 7)

        val result = bag.select(maxWindows = 1, spanLength = 2)
        // Should include exactly 1 span from the pairwise spans
        assertEquals(1, result.spans.size)
    }

    // ── empty source ─────────────────────────────────────────────────────────

    @Test
    fun `select filters out empty cursors`() = runTest {
        val empty = source("BTC", prices = emptyList())
        val full = source("ETH", prices = listOf(10.0, 11.0, 12.0))
        val bag = StochasticBag(listOf(empty, full), seed = 1)

        val result = bag.select(maxWindows = 2, spanLength = 2)
        // Only the non-empty source should produce windows
        assertTrue(result.windows.all { it.key == full.key })
    }

    // ── helpers ──────────────────────────────────────────────────────────────

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
