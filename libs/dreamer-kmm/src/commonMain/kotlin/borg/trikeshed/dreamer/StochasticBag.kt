package borg.trikeshed.dreamer

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

data class KlineRowSpan(
    val key: KlineSeriesKey,
    val start: Int,
    val endExclusive: Int,
    val firstOpenTime: Long,
    val lastOpenTime: Long,
    val rowCount: Int,
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
    val windows: List<KlineRowSpan>,
    val spans: List<PairSpanWindow>,
)

class StochasticBag(
    private val sources: List<KlineSeriesSource>,
    private val seed: Int = 1,
) {
    private val random = Random(seed)
    private var cachedSpans: List<PairSpanWindow>? = null

    suspend fun select(maxWindows: Int, spanLength: Int): StochasticBagSelection {
        require(maxWindows >= 0) { "maxWindows must be non-negative" }
        require(spanLength > 0) { "spanLength must be positive" }
        val windows = sources
            .filter { it.cursor.size > 0 }
            .shuffled(random)
            .take(maxWindows)
            .map { source ->
                val lastStart = (source.cursor.size - spanLength).coerceAtLeast(0)
                val start = if (lastStart == 0) 0 else random.nextInt(0, lastStart + 1)
                val end = (start + spanLength).coerceAtMost(source.cursor.size)
                source.rowSpan(start, end)
            }

        val pairSpans = spans()
        val selectedSpans = when {
            pairSpans.isEmpty() -> emptyList()
            else -> listOf(pairSpans[random.nextInt(pairSpans.size)])
        }
        return StochasticBagSelection(windows, selectedSpans)
    }

    private fun KlineSeriesSource.rowSpan(start: Int, end: Int): KlineRowSpan {
        val first = cursor.at(start)
        val last = cursor.at((end - 1).coerceAtLeast(start))
        return KlineRowSpan(
            key = key,
            start = start,
            endExclusive = end,
            firstOpenTime = first.longValue("openTime"),
            lastOpenTime = last.longValue("openTime"),
            rowCount = end - start,
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
