package borg.trikeshed.cursor

import borg.trikeshed.lib.*
import borg.trikeshed.isam.meta.IOMemento
import kotlin.test.Test
import kotlin.test.assertEquals

class DoubleSeriesQueryTest {

    @Test
    fun testExtractAndMapDoubleSeries() {
        val meta1: () -> ColumnMeta = { ColumnMeta("a", IOMemento.IoInt) }
        val meta2: () -> ColumnMeta = { ColumnMeta("b", IOMemento.IoDouble) }

        val cursor: Cursor = 3 j { rowIndex ->
            2 j { colIndex ->
                if (colIndex == 0) {
                    rowIndex j meta1
                } else {
                    (rowIndex * 1.5) j meta2
                }
            }
        }

        val doubleSeries = cursor.extractDoubleSeries(1)
        assertEquals(3, doubleSeries.size)
        assertEquals(0.0, doubleSeries[0])
        assertEquals(1.5, doubleSeries[1])
        assertEquals(3.0, doubleSeries[2])
        
        val doubleSeriesByName = cursor.extractDoubleSeries("b")
        assertEquals(3, doubleSeriesByName.size)
        assertEquals(1.5, doubleSeriesByName[1])

        val newCursor = doubleSeries.toCursor("new_b")
        assertEquals(3, newCursor.a)
        
        val row0 = newCursor.b(0)
        assertEquals(1, row0.a)
        assertEquals("new_b", row0.b(0).b().name.toString())
        assertEquals(0.0, row0.b(0).a as Double)

        val row1 = newCursor.b(1)
        assertEquals(1.5, row1.b(0).a as Double)
    }
}
