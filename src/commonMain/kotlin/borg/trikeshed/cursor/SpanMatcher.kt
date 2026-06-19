package borg.trikeshed.cursor

import borg.trikeshed.lib.j
import borg.trikeshed.lib.seriesOfAny
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.size

/**
 * Find overlapping time spans between two sorted kline cursors.
 *
 * Strategy:
 * 1. Infer the kline interval from whichever cursor has >= 2 rows.
 * 2. Split cursor A into contiguous segments at its internal gaps.
 * 3. For each A segment, collect B rows within the extended time range.
 * 4. Emit a span only when both cursors contribute rows.
 */
object SpanMatcher {

    fun find(a: Cursor, b: Cursor): Cursor {
        if (a.size == 0 || b.size == 0) return 0 j { _: Int -> throw IndexOutOfBoundsException("empty") }

        val interval = inferInterval(a, b)
        val tolerance = (interval * 0.1).toLong()
        val aSegments = splitSegments(a, interval, tolerance)
        val spans = mutableListOf<SpanRecord>()

        for ((segIdx, aSeg) in aSegments.withIndex()) {
            val aSegFirst = openTime(a, aSeg[0])
            val aSegLast = openTime(a, aSeg[aSeg.size - 1])
            val hasGapBefore = segIdx > 0
            val hasGapAfter = segIdx < aSegments.size - 1

            val searchStart = if (hasGapBefore) aSegFirst - interval else aSegFirst - tolerance
            val searchEnd = if (hasGapAfter) aSegLast + interval else aSegLast + tolerance

            val bRowsInRange = mutableListOf<Int>()
            for (bi in 0 until b.size) {
                val bt = openTime(b, bi)
                if (bt >= searchStart - tolerance && bt <= searchEnd + tolerance) {
                    bRowsInRange.add(bi)
                }
            }
            if (bRowsInRange.isEmpty()) continue

            val aStart = if (hasGapBefore) {
                val candidate = aSegFirst - interval
                if (bRowsInRange.any { openTime(b, it) == candidate }) candidate else aSegFirst
            } else {
                aSegFirst
            }

            val aEnd = if (hasGapAfter) {
                val candidate = aSegLast + interval
                if (bRowsInRange.any { openTime(b, it) == candidate }) candidate else aSegLast
            } else {
                aSegLast
            }

            val aRowsInRange = aSeg.filter { ai ->
                val at = openTime(a, ai)
                at >= aStart - tolerance && at <= aEnd + tolerance
            }
            if (aRowsInRange.isEmpty()) continue

            val bRowsFinal = bRowsInRange.filter { bi ->
                val bt = openTime(b, bi)
                bt >= aStart - tolerance && bt <= aEnd + tolerance
            }

            val bStart = bRowsFinal.minOfOrNull { openTime(b, it) } ?: continue
            val bEnd = bRowsFinal.maxOfOrNull { openTime(b, it) } ?: continue

            val finalAStart: Long
            val finalAEnd: Long
            if (!hasGapBefore && !hasGapAfter) {
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

            val aRowsFinal = aSeg.count { ai ->
                val at = openTime(a, ai)
                at >= finalAStart - tolerance && at <= finalAEnd + tolerance
            }
            val bRowsCount = bRowsInRange.count { bi ->
                val bt = openTime(b, bi)
                bt >= finalAStart - tolerance && bt <= finalAEnd + tolerance
            }

            val finalBStart = bRowsInRange.filter { bi ->
                val bt = openTime(b, bi)
                bt >= finalAStart - tolerance && bt <= finalAEnd + tolerance
            }.minOfOrNull { openTime(b, it) } ?: bStart

            val finalBEnd = bRowsInRange.filter { bi ->
                val bt = openTime(b, bi)
                bt >= finalAStart - tolerance && bt <= finalAEnd + tolerance
            }.maxOfOrNull { openTime(b, it) } ?: bEnd

            spans.add(
                SpanRecord(
                    aStart = finalAStart,
                    aEnd = finalAEnd,
                    bStart = finalBStart,
                    bEnd = finalBEnd,
                    aRows = aRowsFinal,
                    bRows = bRowsCount,
                )
            )
        }

        val spanData = spans.toList()
        return spanData.size j { i ->
            val s = spanData[i]
            cellsToRowVec(
                cells = seriesOfAny(listOf(s.aStart, s.aEnd, s.bStart, s.bEnd, s.aRows, s.bRows)),
                keys = listOf("aStart", "aEnd", "bStart", "bEnd", "aRows", "bRows").toSeries(),
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

    private fun openTime(cursor: Cursor, index: Int): Long {
        val t = cursor.b(index).getValue("openTime")
        return when (t) {
            is Long -> t
            is Number -> t.toLong()
            else -> error("openTime must be a number, got $t")
        }
    }

    private fun inferInterval(a: Cursor, b: Cursor): Long {
        if (a.size >= 2) return openTime(a, 1) - openTime(a, 0)
        if (b.size >= 2) return openTime(b, 1) - openTime(b, 0)
        return 60_000L
    }

    private fun splitSegments(cursor: Cursor, interval: Long, tolerance: Long): List<List<Int>> {
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
