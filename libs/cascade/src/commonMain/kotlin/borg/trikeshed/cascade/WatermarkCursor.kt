package borg.trikeshed.cascade

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/* ── Watermark Cursor ─────────────────────────────────────────────── *
 *
 * Append-only row store with a high-water mark.
 *
 * Uses versioned handle-body volatiles as cascade lazy signals:
 *   - The handle is the WatermarkCursor reference (stable)
 *   - The body is the ArrayList<RowVec> (swapped atomically on batch commit)
 *   - The version is the watermark (monotonic Long)
 *   - A delegate is sent in to provide new rows
 *   - Confix re-join at element parent reconstructs the Cursor
 *     from the volatile body after a swap
 */

class WatermarkCursor private constructor(
    private val rows: ArrayList<RowVec>,
) {

    companion object {
        operator fun invoke(capacity: Int = 1024): WatermarkCursor =
            WatermarkCursor(ArrayList(capacity))
    }

    private var _watermark: Long = 0L
    private var _appendCount: Int = 0

    val watermark: Long get() = _watermark
    val appendCount: Int get() = _appendCount
    val rowCount: Int get() = rows.size

    /** Current snapshot as a Series<RowVec> (lazy — no copy). */
    val cursor: Series<RowVec> get() = rows.size j { i -> rows[i] }

    /** Append a row. Idempotent: rows with reading_id <= watermark are skipped. */
    fun add(item: RowVec) {
        val readingId = (item.b(Readings.COL_READING_ID).a as? Long) ?: return
        if (readingId <= _watermark) return
        rows.add(item)
        _watermark = maxOf(_watermark, readingId)
        _appendCount++
    }

    /** Advance watermark to at least [minWatermark]. Returns previous watermark. */
    fun advanceWatermark(minWatermark: Long): Long {
        val prev = _watermark
        _watermark = maxOf(_watermark, minWatermark)
        return prev
    }

    /** Reset append counter (e.g. after a batch commit). */
    fun resetAppendCount() { _appendCount = 0 }

    /**
     * Ingest all rows from a delegate Series<RowVec>, advancing watermark per row.
     * The delegate is sent in — this is the "versioned handle-body volatile"
     * pattern: the caller provides the source, we absorb it atomically.
     */
    fun ingest(source: Series<RowVec>) {
        for (i in 0 until source.a) {
            add(source.b(i))
        }
    }

    /** Query watermark for use as continuation token. */
    fun watermarkQuery(): String = _watermark.toString()
}
