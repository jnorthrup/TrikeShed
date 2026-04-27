package borg.trikeshed.miniduck.columnar

import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.miniduck.DocRowVec
import borg.trikeshed.miniduck.MiniCursor
import borg.trikeshed.miniduck.at
import kotlin.test.*

/**
 * RED tests: Stage 1b — Span matching between two kline streams.
 *
 * Pipeline: BzSortedCursor (GREEN) → GapDetector (RED) → SpanMatcher (RED) → IsamVolume (RED)
 */

/* ═══════════════════════════════════════════════════════════════════════
   SYNTHETIC TEST DATA
   ═══════════════════════════════════════════════════════════════════════ */

private fun klineRow(openTime: Long): DocRowVec = DocRowVec(
    keys = listOf("openTime", "open", "high", "low", "close", "volume", "symbol", "interval"),
    cells = listOf<Any?>(openTime, 69000.0, 69100.0, 68900.0, 69000.0, 100.0, "BTCUSDT", "1m"),
)

/* ═══════════════════════════════════════════════════════════════════════
   STAGE 1b: SPAN MATCHING RED TESTS
   ═══════════════════════════════════════════════════════════════════════ */

class SpanMatcherPipelineTest {

    /** Identical streams return one span. */
    @Test fun `identical streams return one span`() {
        val rowsA = listOf(
            klineRow(1709251200000L),
            klineRow(1709251260000L),
            klineRow(1709251320000L),
            klineRow(1709251380000L),
        )
        val rowsB = listOf(
            klineRow(1709251200000L),
            klineRow(1709251260000L),
            klineRow(1709251320000L),
            klineRow(1709251380000L),
        )
        val cursorA: MiniCursor = rowsA.size j { i -> rowsA[i] }
        val cursorB: MiniCursor = rowsB.size j { i -> rowsB[i] }
        val spans: MiniCursor = SpanMatcher.find(cursorA, cursorB)
        assertEquals(1, spans.size)
    }

    /** Partial overlap returns one span. */
    @Test fun `partial overlap returns one span`() {
        val rowsA = listOf(
            klineRow(1709251200000L),
            klineRow(1709251260000L),
            klineRow(1709251320000L),
            klineRow(1709251380000L),
        )
        val rowsB = listOf(
            klineRow(1709251260000L),
            klineRow(1709251320000L),
            klineRow(1709251380000L),
            klineRow(1709251440000L),
            klineRow(1709251500000L),
        )
        val cursorA: MiniCursor = rowsA.size j { i -> rowsA[i] }
        val cursorB: MiniCursor = rowsB.size j { i -> rowsB[i] }
        val spans: MiniCursor = SpanMatcher.find(cursorA, cursorB)
        assertEquals(1, spans.size)
    }

    /** Partial overlap span covers correct region. */
    @Test fun `partial overlap span covers correct region`() {
        val rowsA = listOf(
            klineRow(1709251200000L),
            klineRow(1709251260000L),
            klineRow(1709251320000L),
            klineRow(1709251380000L),
        )
        val rowsB = listOf(
            klineRow(1709251260000L),
            klineRow(1709251320000L),
            klineRow(1709251380000L),
            klineRow(1709251440000L),
            klineRow(1709251500000L),
        )
        val cursorA: MiniCursor = rowsA.size j { i -> rowsA[i] }
        val cursorB: MiniCursor = rowsB.size j { i -> rowsB[i] }
        val spans: MiniCursor = SpanMatcher.find(cursorA, cursorB)
        assertEquals(1709251260000L, (spans.at(0) as DocRowVec)["aStart"])
        assertEquals(1709251380000L, (spans.at(0) as DocRowVec)["aEnd"])
        assertEquals(1709251260000L, (spans.at(0) as DocRowVec)["bStart"])
        assertEquals(1709251380000L, (spans.at(0) as DocRowVec)["bEnd"])
        assertEquals(3, (spans.at(0) as DocRowVec)["aRows"])
        assertEquals(3, (spans.at(0) as DocRowVec)["bRows"])
    }

    /** Non-overlapping streams return zero spans. */
    @Test fun `no overlap returns zero spans`() {
        val rowsA = listOf(
            klineRow(1709251200000L),
            klineRow(1709251260000L),
            klineRow(1709251320000L),
            klineRow(1709251380000L),
        )
        val rowsC = listOf(
            klineRow(1709251560000L),
            klineRow(1709251620000L),
            klineRow(1709251680000L),
        )
        val cursorA: MiniCursor = rowsA.size j { i -> rowsA[i] }
        val cursorC: MiniCursor = rowsC.size j { i -> rowsC[i] }
        val spans: MiniCursor = SpanMatcher.find(cursorA, cursorC)
        assertEquals(0, spans.size)
    }

    /** Stream with internal gaps returns separate spans per contiguous region. */
    @Test fun `stream with gaps returns separate spans`() {
        val rowsD = listOf(
            klineRow(1709251200000L),
            klineRow(1709251260000L),
            // gap
            klineRow(1709251440000L),
            klineRow(1709251500000L),
        )
        val rowsB = listOf(
            klineRow(1709251200000L),
            klineRow(1709251260000L),
            klineRow(1709251320000L),
            klineRow(1709251380000L),
        )
        val cursorD: MiniCursor = rowsD.size j { i -> rowsD[i] }
        val cursorB: MiniCursor = rowsB.size j { i -> rowsB[i] }
        val spans: MiniCursor = SpanMatcher.find(cursorD, cursorB)
        assertEquals(2, spans.size)
    }

    /** First span covers [0,120). */
    @Test fun `first span covers first gap-free region`() {
        val rowsD = listOf(
            klineRow(1709251200000L),
            klineRow(1709251260000L),
            klineRow(1709251440000L),
            klineRow(1709251500000L),
        )
        val rowsB = listOf(
            klineRow(1709251200000L),
            klineRow(1709251260000L),
            klineRow(1709251320000L),
            klineRow(1709251380000L),
        )
        val cursorD: MiniCursor = rowsD.size j { i -> rowsD[i] }
        val cursorB: MiniCursor = rowsB.size j { i -> rowsB[i] }
        val spans: MiniCursor = SpanMatcher.find(cursorD, cursorB)
        assertEquals(1709251200000L, (spans.at(0) as DocRowVec)["aStart"])
        assertEquals(1709251320000L, (spans.at(0) as DocRowVec)["aEnd"])
        assertEquals(2, (spans.at(0) as DocRowVec)["aRows"])
    }

    /** Second span covers [180,240). */
    @Test fun `second span covers second gap-free region`() {
        val rowsD = listOf(
            klineRow(1709251200000L),
            klineRow(1709251260000L),
            klineRow(1709251440000L),
            klineRow(1709251500000L),
        )
        val rowsB = listOf(
            klineRow(1709251200000L),
            klineRow(1709251260000L),
            klineRow(1709251320000L),
            klineRow(1709251380000L),
        )
        val cursorD: MiniCursor = rowsD.size j { i -> rowsD[i] }
        val cursorB: MiniCursor = rowsB.size j { i -> rowsB[i] }
        val spans: MiniCursor = SpanMatcher.find(cursorD, cursorB)
        assertEquals(1709251380000L, (spans.at(1) as DocRowVec)["aStart"])
        assertEquals(1709251500000L, (spans.at(1) as DocRowVec)["aEnd"])
        assertEquals(2, (spans.at(1) as DocRowVec)["aRows"])
    }

    /** Empty cursor A returns zero spans. */
    @Test fun `empty cursor A returns zero spans`() {
        val rowsB = listOf(klineRow(1709251200000L))
        val emptyA: MiniCursor = 0 j { _: Int -> throw IndexOutOfBoundsException("empty") }
        val cursorB: MiniCursor = rowsB.size j { i -> rowsB[i] }
        val spans: MiniCursor = SpanMatcher.find(emptyA, cursorB)
        assertEquals(0, spans.size)
    }

    /** Empty cursor B returns zero spans. */
    @Test fun `empty cursor B returns zero spans`() {
        val rowsA = listOf(klineRow(1709251200000L))
        val emptyB: MiniCursor = 0 j { _: Int -> throw IndexOutOfBoundsException("empty") }
        val cursorA: MiniCursor = rowsA.size j { i -> rowsA[i] }
        val spans: MiniCursor = SpanMatcher.find(cursorA, emptyB)
        assertEquals(0, spans.size)
    }

    /** B starts before A — overlap starts at A's first row. */
    @Test fun `B starts before A overlaps from A start`() {
        val rowsA = listOf(
            klineRow(1709251200000L),
            klineRow(1709251260000L),
            klineRow(1709251320000L),
        )
        val rowsBearly = listOf(
            klineRow(1709251140000L),
            klineRow(1709251200000L),
            klineRow(1709251260000L),
            klineRow(1709251320000L),
        )
        val cursorA: MiniCursor = rowsA.size j { i -> rowsA[i] }
        val cursorBearly: MiniCursor = rowsBearly.size j { i -> rowsBearly[i] }
        val spans: MiniCursor = SpanMatcher.find(cursorA, cursorBearly)
        assertEquals(1, spans.size)
        assertEquals(1709251200000L, (spans.at(0) as DocRowVec)["aStart"])
        assertEquals(1709251200000L, (spans.at(0) as DocRowVec)["bStart"])
        assertEquals(3, (spans.at(0) as DocRowVec)["aRows"])
        assertEquals(3, (spans.at(0) as DocRowVec)["bRows"])
    }

    /** A ends before B — overlap ends at A's last row. */
    @Test fun `A ends before B overlaps until A end`() {
        val rowsA = listOf(
            klineRow(1709251200000L),
            klineRow(1709251260000L),
            klineRow(1709251320000L),
            klineRow(1709251380000L),
        )
        val rowsBend = listOf(
            klineRow(1709251260000L),
            klineRow(1709251320000L),
            klineRow(1709251380000L),
            klineRow(1709251440000L),
            klineRow(1709251500000L),
        )
        val cursorA: MiniCursor = rowsA.size j { i -> rowsA[i] }
        val cursorBend: MiniCursor = rowsBend.size j { i -> rowsBend[i] }
        val spans: MiniCursor = SpanMatcher.find(cursorA, cursorBend)
        assertEquals(1, spans.size)
        assertEquals(1709251380000L, (spans.at(0) as DocRowVec)["aEnd"])
        assertEquals(1709251380000L, (spans.at(0) as DocRowVec)["bEnd"])
        assertEquals(3, (spans.at(0) as DocRowVec)["aRows"])
        assertEquals(3, (spans.at(0) as DocRowVec)["bRows"])
    }

    /** Result is a MiniCursor enabling further algebra. */
    @Test fun `result is a MiniCursor enabling further algebra`() {
        val rowsA = listOf(klineRow(1709251200000L), klineRow(1709251260000L))
        val rowsB = listOf(klineRow(1709251200000L), klineRow(1709251260000L))
        val cursorA: MiniCursor = rowsA.size j { i -> rowsA[i] }
        val cursorB: MiniCursor = rowsB.size j { i -> rowsB[i] }
        val spans: MiniCursor = SpanMatcher.find(cursorA, cursorB)
        val projected: MiniCursor = spans.size j { i -> spans.at(i) }
        assertEquals(1, projected.size)
    }
}
