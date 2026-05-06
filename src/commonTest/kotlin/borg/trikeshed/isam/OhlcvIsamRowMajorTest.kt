package borg.trikeshed.isam

import borg.trikeshed.common.Files
import borg.trikeshed.common.mktemp
import borg.trikeshed.common.rm
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.cellsToRowVec
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Row-major OHLCV columnar ISAM: 5 IoDouble columns (open, high, low, close, volume)
 * packed into a single .col file for streamable row-level access.
 *
 * Each row is 40 bytes (5 x 8-byte doubles). One zstd frame per month.
 * Concatenated zstd frames = multi-month span with block-offset access.
 */
class OhlcvIsamRowMajorTest {

    /** Write a small OHLCV cursor and verify all 5 columns land in one .col file. */
    @Test
    fun rowMajorOhlcvWritesSingleColFile() {
        val base = mktemp()
        rm(base)
        try {
            val cursor = ohlcvCursor(
                row(100.0, 105.0, 98.0, 103.0, 50.0),
                row(103.0, 108.0, 101.0, 107.0, 60.0),
                row(107.0, 110.0, 105.0, 109.0, 55.0),
            )
            ColumnarIsam.write(cursor, base)

            // layout should have exactly one .col file (all IoDouble in one group)
            assertTrue(Files.exists("$base.isam3.yaml"))
            val layout = Isam3Layout.read("$base.isam3.yaml")
            val files = layout.partitions[0].files
            val fileNames = (0 until files.size).map { files[it].name }
            assertEquals(1, files.size, "expected single file for all OHLCV doubles, got ${files.size}: $fileNames")
            assertEquals(40, files[0].rowWidth, "5 x 8 bytes = 40 per row")
            assertTrue(Files.exists("$base.ohlcv.col"))
        } finally {
            rm("$base.isam3.yaml")
            rm("$base.ohlcv.col")
        }
    }

    /** Round-trip: write OHLCV cursor, open, verify row values. */
    @Test
    fun rowMajorOhlcvRoundTripsValues() {
        val base = mktemp()
        rm(base)
        try {
            val cursor = ohlcvCursor(
                row(100.0, 105.0, 98.0, 103.0, 50.0),
                row(103.0, 108.0, 101.0, 107.0, 60.0),
            )
            ColumnarIsam.write(cursor, base)
            val reopened = openColumnarIsam(base)
            assertEquals(2, reopened.size)

            // row 0
            assertEquals(100.0, reopened[0][0].a)
            assertEquals(105.0, reopened[0][1].a)
            assertEquals(98.0, reopened[0][2].a)
            assertEquals(103.0, reopened[0][3].a)
            assertEquals(50.0, reopened[0][4].a)

            // row 1
            assertEquals(103.0, reopened[1][0].a)
            assertEquals(108.0, reopened[1][1].a)
            assertEquals(101.0, reopened[1][2].a)
            assertEquals(107.0, reopened[1][3].a)
            assertEquals(60.0, reopened[1][4].a)
        } finally {
            rm("$base.isam3.yaml")
            rm("$base.ohlcv.col")
        }
    }
}

private val OHLCV_KEYS: Series<String> = 5 j { col ->
    when (col) {
        0 -> "open"
        1 -> "high"
        2 -> "low"
        3 -> "close"
        4 -> "volume"
        else -> error("bad col $col")
    }
}

private fun row(open: Double, high: Double, low: Double, close: Double, volume: Double): RowVec {
    val cells: Series<Any?> = 5 j { col ->
        when (col) {
            0 -> open
            1 -> high
            2 -> low
            3 -> close
            4 -> volume
            else -> error("bad col $col")
        }
    }
    return cellsToRowVec(cells = cells, keys = OHLCV_KEYS)
}

private fun ohlcvCursor(vararg rows: RowVec): Cursor = rows.toList().let { list ->
    list.size j { list[it] }
}
