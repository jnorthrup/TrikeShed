package borg.trikeshed.miniduck.columnar

import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.miniduck.DocRowVec
import borg.trikeshed.miniduck.MiniCursor
import borg.trikeshed.miniduck.columnar.GapDetector.openTime
import borg.trikeshed.miniduck.getValue

/**
 * Find gaps in a sorted kline stream.
 *
 * A gap is detected when: actualNext - expected > tolerance
 * where tolerance = max(0, interval * 0.1) — up to 10% jitter is tolerated.
 *
 * @param cursor      sorted MiniCursor (MUST have "openTime" column)
 * @param intervalMs  expected interval between consecutive klines in ms
 * @return MiniCursor of GapRecord rows with keys: [openTime, expected, actual, missingMs]
 */
object GapDetector {


    fun find(cursor: MiniCursor, intervalMs: Long): MiniCursor {
        if (cursor.size < 2) return 0 j { _: Int -> throw IndexOutOfBoundsException("empty") }

        val tolerance = (intervalMs * 0.1).toLong()
        val gaps = mutableListOf<GapRecord>()

        for (i in 0 until cursor.size - 1) {
            val cur = openTime(cursor, i)
            val next = openTime(cursor, i + 1)
            val expected = cur + intervalMs
            val diff = next - expected
            if (diff > tolerance) {
                gaps.add(GapRecord(
                    openTime = cur,
                    expected = expected,
                    actual = next,
                    missingMs = next - cur - intervalMs,
                ))
            }
        }

        val gapData = gaps.toList()
        return gapData.size j { i ->
            val g = gapData[i]
            DocRowVec(
                keys = listOf("openTime", "expected", "actual", "missingMs"),
                cells = listOf(g.openTime, g.expected, g.actual, g.missingMs),
            )
        }
    }

    private fun openTime(cursor: MiniCursor, index: Int): Long {
        val row = cursor.b(index)
        val t = row.getValue("openTime")
        return when (t) {
            is Long -> t
            is Number -> t.toLong()
            else -> error("openTime must be a number, got $t")
        }
    }
}

/** Record of a detected gap in a kline stream. */
data class GapRecord(
    val openTime: Long,
    val expected: Long,
    val actual: Long,
    val missingMs: Long,
)
