package borg.trikeshed.cursor

import borg.trikeshed.isam.RecordMeta
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toArray
import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class CursorTensorTest {
    private fun sampleCursor(): Cursor {
        val metas = arrayOf(
            RecordMeta("price", IOMemento.IoDouble),
            RecordMeta("volume", IOMemento.IoDouble),
        )
        val metaFns = arrayOf<() -> ColumnMeta>(
            { metas[0] },
            { metas[1] },
        ).toSeries()

        return arrayOf(
            arrayOf<Any?>(100.0, 10.0).toSeries().joins(metaFns),
            arrayOf<Any?>(101.5, 20.0).toSeries().joins(metaFns),
            arrayOf<Any?>(99.0, 15.0).toSeries().joins(metaFns),
        ).toSeries()
    }

    @Test
    fun reifiesNumericCursorIntoDenseTensor() {
        val cursor = sampleCursor()
        println(
            "cursor.rows=" +
                listOf(cursor[0][0].a, cursor[0][1].a, cursor[1][0].a, cursor[1][1].a, cursor[2][0].a, cursor[2][1].a)
                    .joinToString(","),
        )
        val tensor = cursor.toCursorTensor()

        println("tensor.values=${tensor.values.joinToString(",")}")
        assertEquals(3, tensor.rowCount)
        assertEquals(2, tensor.columnCount)
        assertContentEquals(intArrayOf(0, 1), tensor.sourceColumnIndices)
        assertContentEquals(arrayOf("price", "volume"), tensor.columnNames.toArray())
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
