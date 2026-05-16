package borg.trikeshed.miniduck.columnar

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.at
import borg.trikeshed.cursor.getValue
import borg.trikeshed.lib.j
import borg.trikeshed.lib.mutable.SeriesArrayList
import borg.trikeshed.lib.size
import borg.trikeshed.miniduck.DocRowVec
import borg.trikeshed.miniduck.toRowVec
import borg.trikeshed.test.TODOError

/**
 * Columnar type stubs — RED test scaffolding for unimplemented columnar features.
 * These types exist solely so that columnar tests compile; they will throw on use.
 */

enum class ColumnType {
    Long, Double, CharSequence, Bytes
}

data class ColumnSchema(
    val name: CharSequence,
    val type: ColumnType,
    val indexPluginName: CharSequence? = null,
) {
    init {
        require(name.isNotEmpty()) { "ColumnSchema name must not be empty" }
    }
}

object IsamCursor {
    fun open(path: CharSequence): IsamCursor = throw UnsupportedOperationException("IsamCursor.open not implemented")
}

object IsamVolume {
    fun generateIsam(cursor: Cursor, schema: List<ColumnSchema>, tempDir: CharSequence): Unit =
        throw UnsupportedOperationException("IsamVolume.generateIsam not implemented")
}

object SpanMatcher {
    fun find(cursorA: Cursor, cursorB: Cursor): Cursor {
        if (cursorA.size == 0 || cursorB.size == 0) return 0 j { _: Int -> throw IndexOutOfBoundsException("empty") }

        val spans = SeriesArrayList<RowVec>()
        var ia = 0
        var ib = 0

        // Phase 1: find exact timestamp match spans with gap-aware extensions
        while (ia < cursorA.size && ib < cursorB.size) {
            // skip to first match — but don't advance past either cursor's rows
            // that might be needed for Phase 2 proximity matching
            val searchA = ia
            val searchB = ib
            while (ia < cursorA.size && ib < cursorB.size && openTime(cursorA, ia) != openTime(cursorB, ib)) {
                if (openTime(cursorA, ia) < openTime(cursorB, ib)) ia++ else ib++
            }
            if (ia >= cursorA.size || ib >= cursorB.size) {
                // no match found — restore positions for Phase 2
                ia = searchA
                ib = searchB
                break
            }

            // extend exact match
            val a0 = ia
            val b0 = ib
            while (ia < cursorA.size && ib < cursorB.size && openTime(cursorA, ia) == openTime(cursorB, ib)) {
                ia++; ib++
            }
            val aMatchEnd = ia - 1
            val bMatchEnd = ib - 1

            // infer interval
            val interval = if (aMatchEnd > a0) openTime(cursorA, aMatchEnd) - openTime(cursorA, aMatchEnd - 1)
                           else if (ia < cursorA.size) openTime(cursorA, ia) - openTime(cursorA, aMatchEnd)
                           else if (bMatchEnd > b0) openTime(cursorB, bMatchEnd) - openTime(cursorB, bMatchEnd - 1)
                           else 60_000L

            // extend: include contiguous rows from the OTHER cursor when THIS cursor has a gap
            var bExtEnd = bMatchEnd
            val aExpectedNext = openTime(cursorA, aMatchEnd) + interval
            // Only extend B if A has a gap (next A row exceeds expected-next)
            val aHasGap = ia < cursorA.size && openTime(cursorA, ia) > aExpectedNext
            if (aHasGap) {
                while (ib < cursorB.size && openTime(cursorB, ib) <= aExpectedNext) {
                    bExtEnd = ib; ib++
                }
            }

            var aExtEnd = aMatchEnd
            val bExpectedNext = openTime(cursorB, bMatchEnd) + interval
            // Only extend A if B has a gap (next B row exceeds expected-next)
            val bHasGap = ib < cursorB.size && openTime(cursorB, ib) > bExpectedNext
            if (bHasGap) {
                while (ia < cursorA.size && openTime(cursorA, ia) <= bExpectedNext) {
                    aExtEnd = ia; ia++
                }
            }

            // global span boundaries
            val spanStart = minOf(openTime(cursorA, a0), openTime(cursorB, b0))
            val spanEnd = maxOf(openTime(cursorA, aExtEnd), openTime(cursorB, bExtEnd))
            spans.add(DocRowVec(
                keys = listOf("aStart", "aEnd", "bStart", "bEnd", "aRows", "bRows"),
                cells = listOf(
                    spanStart, spanEnd, spanStart, spanEnd,
                    aExtEnd - a0 + 1, bExtEnd - b0 + 1,
                ),
            ).toRowVec())
        }

        // Phase 2: pair remaining gap-free regions from both cursors
        if (ia < cursorA.size && ib < cursorB.size) {
            // collect remaining gap-free regions from A
            val aRegions = SeriesArrayList<IntRange>()
            var start = ia
            for (i in ia until cursorA.size - 1) {
                val delta = openTime(cursorA, i + 1) - openTime(cursorA, i)
                if (delta > 120_000L * 2) { // 2-minute gap threshold
                    aRegions.add(start..i)
                    start = i + 1
                }
            }
            aRegions.add(start until cursorA.size)

            // collect remaining gap-free regions from B
            val bRegions = SeriesArrayList<IntRange>()
            start = ib
            for (i in ib until cursorB.size - 1) {
                val delta = openTime(cursorB, i + 1) - openTime(cursorB, i)
                if (delta > 120_000L * 2) {
                    bRegions.add(start..i)
                    start = i + 1
                }
            }
            bRegions.add(start until cursorB.size)

            // pair regions by temporal overlap/proximity
            var bi = 0
            for (aReg in aRegions) {
                while (bi < bRegions.size) {
                    val bReg = bRegions[bi]
                    val aRegStart = openTime(cursorA, aReg.start)
                    val aRegEnd = openTime(cursorA, aReg.endInclusive)
                    val bRegStart = openTime(cursorB, bReg.start)
                    val bRegEnd = openTime(cursorB, bReg.endInclusive)
                    // check temporal proximity: regions overlap or are within 1 interval
                    if (bRegEnd + 60_000L >= aRegStart && aRegEnd + 60_000L >= bRegStart) {
                        val spanStart = minOf(aRegStart, bRegStart)
                        val spanEnd = maxOf(aRegEnd, bRegEnd)
                        spans.add(DocRowVec(
                            keys = listOf("aStart", "aEnd", "bStart", "bEnd", "aRows", "bRows"),
                            cells = listOf(
                                spanStart, spanEnd, spanStart, spanEnd,
                                aReg.endInclusive - aReg.start + 1, bReg.endInclusive - bReg.start + 1,
                            ),
                        ).toRowVec())
                        bi++
                        break
                    }
                    if (bRegEnd < aRegStart) bi++ else break
                }
            }
        }

        return spans.size j { i -> spans[i] }
    }

    private fun openTime(cursor: Cursor, index: Int): Long =
        (cursor.at(index) as DocRowVec)["openTime"] as Long
}

object GapDetector {
    fun find(cursor: Cursor, intervalMs: Long): Cursor {
        if (cursor.size < 2) return 0 j { _: Int -> throw IndexOutOfBoundsException("empty") }
        val threshold = (intervalMs * 1.1).toLong()
        val gaps = (0 until cursor.size - 1).mapNotNull { i ->
            val cur = cursor.at(i)
            val nxt = cursor.at(i + 1)
            val curTime = cur.getValue("openTime") as Long
            val nxtTime = nxt.getValue("openTime") as Long
            val delta = nxtTime - curTime
            if (delta > threshold) {
                val expected = curTime + intervalMs
                DocRowVec(
                    keys = listOf("openTime", "expected", "actual", "missingMs"),
                    cells = listOf(curTime, expected, nxtTime, nxtTime - expected),
                ).toRowVec()
            } else null
        }
        return gaps.size j { i -> gaps[i] }
    }
}

interface IndexCursor {
    fun seek(blockOffset: Long)
    fun next(): Boolean
    fun current(): Long
}

object IndexPluginRegistry {
    fun resolve(name: CharSequence): IndexPlugin = when (name) {
        "ZranIndex" -> ZranIndex()
        "Lz4Index" -> Lz4Index()
        else -> throw IllegalArgumentException("Unknown index plugin: $name")
    }
}

interface IndexPlugin

class ZranIndex : IndexPlugin {
    fun openIndexCursor(fd: Int, path: CharSequence): IndexCursor =
        throw TODOError("ZranIndex.openIndexCursor not implemented")
}

class Lz4Index : IndexPlugin {
    fun openIndexCursor(fd: Int, path: CharSequence): IndexCursor =
        throw TODOError("Lz4Index.openIndexCursor not implemented")
}
