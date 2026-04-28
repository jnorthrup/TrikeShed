package borg.trikeshed.miniduck.columnar

import borg.trikeshed.lib.*
import borg.trikeshed.miniduck.DocRowVec
import borg.trikeshed.miniduck.MiniCursor
import borg.trikeshed.miniduck.MiniRowVec
import borg.trikeshed.miniduck.getValue

/**
 * Find overlapping time spans between two sorted kline cursors.
 *
 * Strategy:
 * 1. Infer the kline interval from whichever cursor has ≥2 rows.
 * 2. Split cursor A into contiguous segments at its internal gaps
 *    (gap = next_actual - expected > 10% of interval).
 * 3. For each A segment, collect B rows within the extended time range
 *    [a_first - interval .. a_last + interval] — the ±interval extension
 *    captures B rows that bridge A's gap boundaries.
 * 4. At A gap boundaries, set aStart/aEnd to the extended ±interval value
 *    (instead of A's own row timestamps) so the span covers the bridge.
 * 5. Emit a span only when BOTH cursors contribute rows.
 *
 * @param a  sorted MiniCursor with "openTime" column
 * @param b  sorted MiniCursor with "openTime" column
 * @return MiniCursor of DocRowVec rows with keys [aStart, aEnd, bStart, bEnd, aRows, bRows]
 */
object SpanMatcher {

    fun find(a: MiniCursor, b: MiniCursor): MiniCursor {
        if (a.size == 0 || b.size == 0) return 0 j { _: Int -> throw IndexOutOfBoundsException("empty") }

        val interval = inferInterval(a, b)
        val tolerance = (interval * 0.1).toLong()

        // Split A into contiguous segments
        val aSegments = splitSegments(a, interval, tolerance)

        val spans = mutableListOf<SpanRecord>()

        for ((segIdx, aSeg) in aSegments.withIndex()) {
            val aSegFirst = openTime(a, aSeg[0])
            val aSegLast = openTime(a, aSeg[aSeg.size - 1])
            val hasGapBefore = segIdx > 0
            val hasGapAfter = segIdx < aSegments.size - 1

            // Extended search range: ±interval at gap boundaries, ±tolerance otherwise
            val searchStart = if (hasGapBefore) aSegFirst - interval else aSegFirst - tolerance
            val searchEnd = if (hasGapAfter) aSegLast + interval else aSegLast + tolerance

            // Collect B rows within the extended search range
            val bRowsInRange = mutableListOf<Int>()
            for (bi in 0 until b.size) {
                val bt = openTime(b, bi)
                if (bt >= searchStart - tolerance && bt <= searchEnd + tolerance) {
                    bRowsInRange.add(bi)
                }
            }
            if (bRowsInRange.isEmpty()) continue

            // aStart / aEnd: extend by ±interval at gap boundaries if B has a row there
            val aStart = if (hasGapBefore) {
                val candidate = aSegFirst - interval
                if (bRowsInRange.any { openTime(b, it) == candidate }) candidate
                else aSegFirst
            } else {
                aSegFirst
            }

            val aEnd = if (hasGapAfter) {
                val candidate = aSegLast + interval
                if (bRowsInRange.any { openTime(b, it) == candidate }) candidate
                else aSegLast
            } else {
                aSegLast
            }

            // A rows within [aStart..aEnd]
            val aRowsInRange = aSeg.filter { ai ->
                val at = openTime(a, ai)
                at >= aStart - tolerance && at <= aEnd + tolerance
            }
            if (aRowsInRange.isEmpty()) continue

            // B rows within [aStart..aEnd]
            val bRowsFinal = bRowsInRange.filter { bi ->
                val bt = openTime(b, bi)
                bt >= aStart - tolerance && bt <= aEnd + tolerance
            }

            // But we also need bStart/bEnd to exclude B rows outside the overlap
            // For non-gap cases, overlap = [max(aStart, bFirst)..min(aEnd, bLast)]
            val bStart = bRowsFinal.minOfOrNull { openTime(b, it) } ?: continue
            val bEnd = bRowsFinal.maxOfOrNull { openTime(b, it) } ?: continue

            // For non-gap cases, further constrain aStart/aEnd to the actual overlap
            val finalAStart: Long
            val finalAEnd: Long
            if (!hasGapBefore && !hasGapAfter) {
                // No gaps: aStart/aEnd should be the overlap region
                val aRowsInOverlap = aSeg.filter { ai ->
                    val at = openTime(a, ai)
                    at >= bStart - tolerance && at <= bEnd + tolerance
                }
                finalAStart = if (aRowsInOverlap.isNotEmpty()) openTime(a, aRowsInOverlap[0]) else aStart
                finalAEnd = if (aRowsInOverlap.isNotEmpty()) openTime(a, aRowsInOverlap[aRowsInOverlap.size - 1]) else aEnd
            } else {
                finalAStart = aStart
                finalAEnd = aEnd
            }

            // Final counts
            val aRowsFinal = aSeg.count { ai ->
                val at = openTime(a, ai)
                at >= finalAStart - tolerance && at <= finalAEnd + tolerance
            }
            val bRowsCount = bRowsInRange.count { bi ->
                val bt = openTime(b, bi)
                bt >= finalAStart - tolerance && bt <= finalAEnd + tolerance
            }

            // bStart/bEnd within [finalAStart..finalAEnd]
            val finalBStart = bRowsInRange.filter { bi ->
                val bt = openTime(b, bi)
                bt >= finalAStart - tolerance && bt <= finalAEnd + tolerance
            }.minOfOrNull { openTime(b, it) } ?: bStart

            val finalBEnd = bRowsInRange.filter { bi ->
                val bt = openTime(b, bi)
                bt >= finalAStart - tolerance && bt <= finalAEnd + tolerance
            }.maxOfOrNull { openTime(b, it) } ?: bEnd

            spans.add(SpanRecord(
                aStart = finalAStart,
                aEnd = finalAEnd,
                bStart = finalBStart,
                bEnd = finalBEnd,
                aRows = aRowsFinal,
                bRows = bRowsCount,
            ))
        }

        // Convert to MiniCursor of DocRowVec
        val spanData = spans.toList()
        return spanData.size j { i ->
            val s = spanData[i]
            DocRowVec(
                keys = listOf("aStart", "aEnd", "bStart", "bEnd", "aRows", "bRows"),
                cells = listOf(s.aStart, s.aEnd, s.bStart, s.bEnd, s.aRows, s.bRows),
            )
        }
    }

    private data class SpanRecord(
        val aStart: Long,
        val aEnd: Long,
        val bStart: Long,
        val bEnd: Long,
        val aRows: Int,
        val bRows: Int,
    )

    /** Extract openTime from a cursor row. */
    private fun openTime(cursor: MiniCursor, index: Int): Long {
        val row = cursor.b(index)
        val t = row.getValue("openTime")
        return when (t) {
            is Long -> t
            is Number -> t.toLong()
            else -> error("openTime must be a number, got $t")
        }
    }

    /** Infer interval from whichever cursor has ≥2 rows. */
    private fun inferInterval(a: MiniCursor, b: MiniCursor): Long {
        if (a.size >= 2) return openTime(a, 1) - openTime(a, 0)
        if (b.size >= 2) return openTime(b, 1) - openTime(b, 0)
        return 60_000L
    }

    /**
     * Split a cursor into contiguous segments.
     * Returns list of index-lists — each is a gap-free run.
     */
    private fun splitSegments(cursor: MiniCursor, interval: Long, tolerance: Long): List<List<Int>> {
        if (cursor.size == 0) return emptyList()
        val segments = mutableListOf<List<Int>>()
        var current = mutableListOf(0)
        for (i in 1 until cursor.size) {
            val prev = openTime(cursor, i - 1)
            val cur = openTime(cursor, i)
            if (cur - prev > interval + tolerance) {
                segments.add(current.toList())
                current = mutableListOf()
            }
            current.add(i)
        }
        segments.add(current.toList())
        return segments.toList()
    }
}
