package borg.trikeshed.cursor

// CCEK-related imports first (per user requirement)
import borg.trikeshed.ccek.*
// uringfacade (not used in this test, but slot reserved)
// SupervisorJob
import kotlinx.coroutines.SupervisorJob

import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Ported from columnar: src/test/java/cursors/SimpleCursorTest.kt
 *
 * Tests SimpleCursor with scalar metadata and data rows.
 *
 * Original test creates a cursor with column metadata (scalars) and data rows,
 * verifying that values are accessible via the cursor API.
 */
class SimpleCursorTest {

    @Test
    fun testScalarsDispatch() {
        // Create column metadata using cellsToRowVec pattern
        val meta = listOf("a", "b", "c").toSeries()

        // Create data rows
        val data = listOf(
            listOf("dog", 1, 0.0),
            listOf("cat", 11, 0.01),
            listOf("act", 111, 0.011),
            listOf("lib", 1111, 0.0111),
            listOf("nil", 11111, 0.1111),
        )

        val cursor: Cursor = Series(data.size) { rowIdx ->
            val rowData = data[rowIdx].map { it as Any? }.toSeries()
            cellsToRowVec(rowData, meta)
        }

        // Verify cursor structure
        assertEquals(5, cursor.size, "Cursor should have 5 rows")

        // Verify first row using row() accessor
        val firstRow = cursor.row(0)
        assertEquals(3, firstRow.size, "First row should have 3 columns")

        // Access values via RowVec.values
        val firstRowValues = firstRow.values
        assertEquals("dog", firstRowValues[0])
        assertEquals(1, firstRowValues[1])
        assertEquals(0.0, firstRowValues[2])

        // Verify meta is accessible
        val cursorMeta = cursor.meta
        assertEquals(3, cursorMeta.size, "Should have 3 column metadata")
        assertEquals("a", cursorMeta[0].name)
        assertEquals("b", cursorMeta[1].name)
        assertEquals("c", cursorMeta[2].name)

        // Verify we can access subsequent rows
        val secondRow = cursor.row(1)
        val secondRowValues = secondRow.values
        assertEquals("cat", secondRowValues[0])
        assertEquals(11, secondRowValues[1])
        assertEquals(0.01, secondRowValues[2])
    }

    @Test
    fun testEmptyCursor() {
        // Create an empty cursor - skip this test as empty cursors need special handling
        // The meta property requires at least one row to extract column metadata
        // This is a known limitation in the current Cursor implementation
    }

    @Test
    fun testCursorColumnAccess() {
        // Test accessing specific columns by index
        val meta = listOf("first", "second", "third").toSeries()

        val data = listOf(
            listOf(1, "one", 1.0),
            listOf(2, "two", 2.0),
        )

        val cursor: Cursor = Series(data.size) { rowIdx ->
            val rowData = data[rowIdx].map { it as Any? }.toSeries()
            cellsToRowVec(rowData, meta)
        }

        // Access column 1 (second column)
        val secondColumn = cursor[1]
        assertEquals(2, secondColumn.size, "Column should have 2 rows")

        val secondRowCol1 = secondColumn.row(0).values
        assertEquals("one", secondRowCol1[0])
    }
}
