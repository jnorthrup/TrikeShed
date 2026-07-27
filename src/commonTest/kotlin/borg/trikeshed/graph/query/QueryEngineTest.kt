package borg.trikeshed.graph.query

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.j
import borg.trikeshed.parse.confix.widenNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QueryEngineTest {
    
    private fun createTestRow(id: Int, score: Double): RowVec {
        return widenNode(
            borg.trikeshed.parse.confix.FacetDescriptor("id", IOMemento.IoInt, id),
            borg.trikeshed.parse.confix.FacetDescriptor("score", IOMemento.IoDouble, score),
        )
    }

    @Test
    fun extractDoubleColumn_extracts_primitive_doubles_from_Cursor() {
        val row1 = createTestRow(1, 95.5)
        val row2 = createTestRow(2, 88.0)
        val row3 = createTestRow(3, 100.0)
        val cursor: Cursor = 3 j { i -> 
            when (i) {
                0 -> row1
                1 -> row2
                else -> row3
            }
        }
        
        val engine = QueryEngine(cursor)
        val series = engine.extractDoubleColumn("score")
        
        assertEquals(3, series.size)
        assertEquals(95.5, series[0])
        assertEquals(88.0, series[1])
        assertEquals(100.0, series[2])
    }
    
    @Test
    fun extractDoubleColumn_throws_for_missing_column() {
        val row = createTestRow(1, 95.5)
        val cursor: Cursor = 1 j { row }
        
        val engine = QueryEngine(cursor)
        assertFailsWith<IllegalArgumentException> {
            engine.extractDoubleColumn("missing")
        }
    }
    
    @Test
    fun extractDoubleColumn_returns_empty_series_for_empty_cursor() {
        val cursor: Cursor = borg.trikeshed.lib.emptySeries()
        val engine = QueryEngine(cursor)
        val series = engine.extractDoubleColumn("score")
        assertEquals(0, series.size)
    }
}
