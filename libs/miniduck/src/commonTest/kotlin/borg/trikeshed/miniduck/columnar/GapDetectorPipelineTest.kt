package borg.trikeshed.miniduck.columnar

import borg.trikeshed.collections.s_
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.at
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.miniduck.DocRowVec
import borg.trikeshed.miniduck.toRowVec
import kotlin.test.*

/**
 * RED tests: Stage 0a — Gap detection in sorted kline streams.
 *
 * Pipeline: zip → BzSortedCursor (GREEN) → GapDetector.find → SpanMatcher → IsamVolume
 */

/* ═══════════════════════════════════════════════════════════════════════
   SYNTHETIC TEST DATA
   ═══════════════════════════════════════════════════════════════════════ */
val INTERVAL_1M = 60_000L
val INTERVAL_5M = 300_000L
private fun klineRow(openTime: Long): borg.trikeshed.cursor.RowVec = DocRowVec(
    keys = listOf("openTime", "open", "high", "low", "close", "volume", "symbol", "interval"),
    cells = listOf<Any?>(openTime, 69000.0, 69100.0, 68900.0, 69000.0, 100.0, "BTCUSDT", "1m"),
).toRowVec()

/* ═══════════════════════════════════════════════════════════════════════
   STAGE 0a: GAP DETECTION RED TESTS
   ═══════════════════════════════════════════════════════════════════════ */

class GapDetectorPipelineTest {

    /** No gaps in continuous stream returns empty cursor. */
    @Test fun `continuous stream returns zero gaps`() {
        val rows = listOf(
            klineRow(1709251200000L),
            klineRow(1709251260000L),
            klineRow(1709251320000L),
            klineRow(1709251380000L),
            klineRow(1709251440000L),
            klineRow(1709251500000L),
        )
        val cursor: Cursor = rows.size j { i -> rows[i] }
        val result: Cursor = GapDetector.find(cursor, INTERVAL_1M)
        assertEquals(0, result.size)
    }

    /** One 5-minute gap in 1m stream returns exactly one gap record. */
    @Test fun `5-minute gap returns one gap record`() {
        val rows = s_[
            klineRow(1709251200000L),
            klineRow(1709251260000L),
            klineRow(1709251320000L),
            klineRow(1709251380000L),
            klineRow(1709251680000L),  // gap: expected 1709251440000
            klineRow(1709251740000L),
            klineRow(1709251800000L),
            klineRow(1709251860000L),
        ]
        val cursor: Cursor = rows.size j { i -> rows[i] }
        val result: Cursor = GapDetector.find(cursor, INTERVAL_1M)
        assertEquals(1, result.size )
    }

    /** Gap record fields are correct. */
    @Test fun `gap record fields are correct`() {
        val rows = listOf(
            klineRow(1709251200000L),
            klineRow(1709251260000L),
            klineRow(1709251320000L),
            klineRow(1709251380000L),
            klineRow(1709251680000L),
            klineRow(1709251740000L),
            klineRow(1709251800000L),
            klineRow(1709251860000L),
        )
        val cursor: Cursor = rows.size j { i -> rows[i] }
        val result: Cursor = GapDetector.find(cursor, INTERVAL_1M)
        assertEquals(1709251380000L, result.at(0).getValue("openTime"))
        assertEquals(1709251440000L, result.at(0).getValue("expected"))
        assertEquals(1709251680000L, result.at(0).getValue("actual"))
        assertEquals(240_000L, result.at(0).getValue("missingMs"))
    }

    /** Jitter within 10% tolerance produces zero gaps. */
    @Test fun `sub-interval jitter does not trigger gap`() {
        val rows = listOf(
            klineRow(1709251200000L),
            klineRow(1709251261000L),  // +1s
            klineRow(1709251319000L),  // -1s
            klineRow(1709251380000L),
            klineRow(1709251441000L),  // +1s
        )
        val cursor: Cursor = rows.size j { i -> rows[i] }
        val result: Cursor = GapDetector.find(cursor, INTERVAL_1M)
        assertEquals(0, result.size)
    }

    /** 10% jitter exactly at boundary produces zero gaps. */
    @Test fun `10 percent jitter tolerated`() {
        val rows = (0 until 100).map { i ->
            val base = 1709251200000L + i * INTERVAL_1M
            val jitter = if (i % 2 == 0) (INTERVAL_1M / 10) else 0L
            klineRow(base + jitter)
        }
        val cursor: Cursor = rows.size j { i -> rows[i] }
        val result: Cursor = GapDetector.find(cursor, INTERVAL_1M)
        assertEquals(0, result.size)
    }

    /** Gap larger than 10% tolerance triggers detection. */
    @Test fun `11 percent jitter triggers gap`() {
        val rows = (0 until 100).map { i ->
            val base = 1709251200000L + i * INTERVAL_1M
            val jitter = if (i == 50) (INTERVAL_1M * 11 / 100) else 0L
            klineRow(base + jitter)
        }
        val cursor: Cursor = rows.size j { i -> rows[i] }
        val result: Cursor = GapDetector.find(cursor, INTERVAL_1M)
        assertEquals(1, result.size)
    }

    /** Empty cursor returns empty result. */
    @Test fun `empty cursor returns zero gaps`() {
        val empty: Cursor = 0 j { _: Int -> throw IndexOutOfBoundsException("empty") }
        val result: Cursor = GapDetector.find(empty, INTERVAL_1M)
        assertEquals(0, result.size)
    }

    /** Single-row cursor returns zero gaps. */
    @Test fun `single row returns zero gaps`() {
        val cursor: Cursor = 1 j { _: Int -> klineRow(1709251200000L) }
        val result: Cursor = GapDetector.find(cursor, INTERVAL_1M)
        assertEquals(0, result.size)
    }

    /** Works with 5m interval on 5m kline stream. */
    @Test fun `5m interval on 5m stream detects gaps`() {
        val rows = listOf(
            klineRow(1709251200000L),
            klineRow(1709251500000L),
            klineRow(1709251800000L),
            klineRow(1709252700000L),  // gap: expected 1709252100000
        )
        val cursor: Cursor = rows.size j { i -> rows[i] }
        val result: Cursor = GapDetector.find(cursor, INTERVAL_5M)
        assertEquals(1, result.size)
    }

    /** Result is a MiniCursor enabling further algebra. */
    @Test fun `result is a MiniCursor enabling further algebra`() {
        val rows = listOf(
            klineRow(1709251200000L),
            klineRow(1709251260000L),
            klineRow(1709251320000L),
            klineRow(1709251380000L),
            klineRow(1709251680000L),
            klineRow(1709251740000L),
            klineRow(1709251800000L),
            klineRow(1709251860000L),
        )
        val cursor: Cursor = rows.size j { i -> rows[i] }
        val result: Cursor = GapDetector.find(cursor, INTERVAL_1M)
        val projected: Cursor = result.size j { i -> result.at(i) }
        assertEquals(1, projected.size)
    }
}
