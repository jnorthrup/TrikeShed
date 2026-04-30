package borg.trikeshed.dreamer

import borg.trikeshed.indicator.Stochastic
import borg.trikeshed.miniduck.MiniCursor
import borg.trikeshed.miniduck.columnar.SpanMatcher
import borg.trikeshed.miniduck.getValue
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.at
import borg.trikeshed.lib.size
import kotlin.random.Random

data class KlineSeriesSource(
    val key: KlineSeriesKey,
    val cursor: MiniCursor,
)

data class StochasticWindow(
    val key: KlineSeriesKey,
    val start: Int,
    val endExclusive: Int,
    val k: Double,
    val d: Double,
)

data class PairSpanWindow(
    val left: KlineSeriesKey,
    val right: KlineSeriesKey,
    val aStart: Long,
    val aEnd: Long,
    val bStart: Long,
    val bEnd: Long,
    val aRows: Int,
    val bRows: Int,
)

data class StochasticBagSelection(
    val windows: List<StochasticWindow>,
    val spans: List<PairSpanWindow>,
)

class StochasticBag(
    private val sources: List<KlineSeriesSource>,
    private val seed: Int = 1,
    private val kPeriod: Int = 14,
    private val dPeriod: Int = 3,
) {
    private val random = Random(seed)
    private var cachedSpans: List<PairSpanWindow>? = null

    suspend fun warm() {
        sources.forEach { source ->
            HarnessStochasticCache.ensureCached(
                symbol = source.key.symbol,
                timeframe = source.key.b.binanceInterval,
                kPeriod = kPeriod,
                dPeriod = dPeriod,
            ) {
                source.cursor
            }
        }
    }

    suspend fun select(maxWindows: Int, spanLength: Int): StochasticBagSelection {
        require(maxWindows >= 0) { "maxWindows must be non-negative" }
        require(spanLength > 0) { "spanLength must be positive" }
        warm()
        val windows = sources
            .filter { it.cursor.size > 0 }
            .shuffled(random)
            .take(maxWindows)
            .map { source ->
                val lastStart = (source.cursor.size - spanLength).coerceAtLeast(0)
                val start = if (lastStart == 0) 0 else random.nextInt(0, lastStart + 1)
                val end = (start + spanLength).coerceAtMost(source.cursor.size)
                val stochastic = HarnessStochasticCache.get(source.key.symbol, source.key.b.binanceInterval, kPeriod, dPeriod)
                source.window(start, end, stochastic)
            }

        return StochasticBagSelection(windows, spans())
    }

    private fun KlineSeriesSource.window(start: Int, end: Int, stochastic: Stochastic.Result?): StochasticWindow {
        val sample = (end - 1).coerceAtLeast(start)
        return StochasticWindow(
            key = key,
            start = start,
            endExclusive = end,
            k = stochastic?.k?.let { it.b(sample.coerceAtMost(it.size - 1)) } ?: 0.0,
            d = stochastic?.d?.let { it.b(sample.coerceAtMost(it.size - 1)) } ?: 0.0,
        )
    }

    private fun spanWindows(left: KlineSeriesSource, right: KlineSeriesSource): List<PairSpanWindow> {
        val spans = SpanMatcher.find(left.cursor, right.cursor)
        return List(spans.size) { index ->
            val row = spans.at(index)
            PairSpanWindow(
                left = left.key,
                right = right.key,
                aStart = row.longValue("aStart"),
                aEnd = row.longValue("aEnd"),
                bStart = row.longValue("bStart"),
                bEnd = row.longValue("bEnd"),
                aRows = row.intValue("aRows"),
                bRows = row.intValue("bRows"),
            )
        }
    }

    private fun spans(): List<PairSpanWindow> {
        cachedSpans?.let { return it }
        val spans = mutableListOf<PairSpanWindow>()
        for (i in 0 until sources.size) {
            for (j in i + 1 until sources.size) {
                spans += spanWindows(sources[i], sources[j])
            }
        }
        return spans.toList().also { cachedSpans = it }
    }
}

private fun RowVec.longValue(key: String): Long {
    val value = getValue(key)
    return when (value) {
        is Long -> value
        is Number -> value.toLong()
        else -> error("$key must be numeric, got $value")
    }
}

private fun RowVec.intValue(key: String): Int {
    val value = getValue(key)
    return when (value) {
        is Int -> value
        is Number -> value.toInt()
        else -> error("$key must be numeric, got $value")
    }
}
