package borg.trikeshed.miniduck.columnar

import borg.trikeshed.miniduck.MiniCursor

/**
 * Gap record: one row per gap found in the stream.
 *
 * @property openTime    the openTime of the last known row before the gap
 * @property expected    the openTime that SHOULD have followed openTime
 * @property actual      the openTime of the first row after the gap
 * @property missingMs   gap size in milliseconds (> 0)
 */
data class GapRecord(
    val openTime: Long,
    val expected: Long,
    val actual: Long,
    val missingMs: Long,
)

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
    fun find(cursor: MiniCursor, intervalMs: Long): MiniCursor =
        throw NotImplementedError("GapDetector.find not yet implemented")
}
