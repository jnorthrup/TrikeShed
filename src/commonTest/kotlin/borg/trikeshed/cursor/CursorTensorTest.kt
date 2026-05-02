package borg.trikeshed.cursor

import borg.trikeshed.isam.RecordMeta
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.zip
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class CursorTensorTest {

    /** Build a typed RowVec: values zipped with metadata. */
    @Suppress("UNCHECKED_CAST")
   fun rowVecOf(vararg pairs: Pair<Any?, IOMemento>): RowVec {
        val values: Series<Any?> = pairs.size j { pairs[it].first }
        val metas: Series<ColumnMeta> = pairs.size j { RecordMeta("", pairs[it].second) }
        return values.zip(metas) as RowVec
    }

    /** Build a Cursor from vararg rows (safe-cast to RowVec). */
    @Suppress("UNCHECKED_CAST")
   fun typedCursorOf(vararg rows: Any?): Cursor =
        Join<Int, (Int) -> RowVec>(rows.size) { i: Int -> rows[i] as RowVec } as Cursor

    @Test
    fun reifiesNumericCursorIntoDenseTensor() {
        // Cursor = Series<RowVec>, RowVec = Series2<Any?, ()`ColumnMeta↻`>
        val cursor = typedCursorOf(
            rowVecOf(100.0 to IOMemento.IoDouble, 10.0 to IOMemento.IoDouble),
            rowVecOf(101.5 to IOMemento.IoDouble, 20.0 to IOMemento.IoDouble),
            rowVecOf(99.0 to IOMemento.IoDouble, 15.0 to IOMemento.IoDouble),
        )
        val tensor = cursor.toCursorTensor()

        assertEquals(3, tensor.rowCount)
        assertEquals(2, tensor.columnCount)
        assertContentEquals(doubleArrayOf(100.0, 10.0, 101.5, 20.0, 99.0, 15.0), tensor.values)
        assertEquals(55.0, tensor.rowMean(0), absoluteTolerance = 1e-12)
        assertEquals(300.5 / 3.0, tensor.columnMean(0), absoluteTolerance = 1e-12)
        assertEquals(15.0, tensor.columnMean(1), absoluteTolerance = 1e-12)
        assertEquals(100.75, tensor.windowMean(0, 0, 2), absoluteTolerance = 1e-12)
    }

    @Test
    fun preservesDenseRowMajorTensorSemantics() {
        val tensor = WasmDoubleTensor(
            rows = 2,
            columns = 3,
            values = doubleArrayOf(
                1.0, 2.0, 3.0,
                4.0, 5.0, 6.0,
            ),
        )

        assertEquals(5.0, tensor[1, 1])
        assertContentEquals(doubleArrayOf(4.0, 5.0, 6.0), tensor.row(1))
        assertContentEquals(doubleArrayOf(2.0, 5.0), tensor.column(1))
        assertEquals(2.0, tensor.rowMean(0), absoluteTolerance = 1e-12)
        assertEquals(3.5, tensor.columnMean(1), absoluteTolerance = 1e-12)
        assertEquals(3.5, tensor.windowMean(1, 0, 2), absoluteTolerance = 1e-12)
    }
}
