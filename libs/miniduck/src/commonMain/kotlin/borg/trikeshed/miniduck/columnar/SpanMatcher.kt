package borg.trikeshed.miniduck.columnar

import borg.trikeshed.miniduck.MiniCursor

/**
 * One row in the span-matching result.
 *
 * @property aStart   inclusive start openTime in cursor A
 * @property aEnd     exclusive end openTime in cursor A
 * @property bStart   inclusive start openTime in cursor B
 * @property bEnd     exclusive end openTime in cursor B
 * @property aRows    row count from A in this span
 * @property bRows    row count from B in this span
 */
data class SpanRecord(
    val aStart: Long,
    val aEnd: Long,
    val bStart: Long,
    val bEnd: Long,
    val aRows: Int,
    val bRows: Int,
)

/**
 * Find overlapping time spans between two sorted kline cursors.
 *
 * A span is a maximal contiguous interval where BOTH cursors have at least
 * one row. Rows with the same openTime are included in both spans.
 *
 * @param a       sorted MiniCursor with "openTime" column
 * @param b       sorted MiniCursor with "openTime" column
 * @return MiniCursor of SpanRecord rows
 */
object SpanMatcher {
    fun find(a: MiniCursor, b: MiniCursor): MiniCursor =
        throw NotImplementedError("SpanMatcher.find not yet implemented")
}
