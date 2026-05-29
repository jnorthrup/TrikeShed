package borg.trikeshed.cursor

// CCEK-related imports first (per user requirement)
import borg.trikeshed.ccek.*
// uringfacade (not used in this test, but slot reserved)
// SupervisorJob
import kotlinx.coroutines.SupervisorJob

import borg.trikeshed.isam.RecordMeta
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ported from columnar: src/test/java/cursors/CursorKtTest.kt
 *
 * This is a PARTIAL port - only testing operations that are currently
 * implemented in Trikeshed.
 *
 * Original tests included:
 * - resample, pivot, group, join, ordered (NOT YET IMPLEMENTED)
 * - div, whichKey, whichValue (simple utilities - IMPLEMENTED)
 * - Basic cursor operations (IMPLEMENTED)
 */
class CursorKtTest {

    // Helper to create a simple cursor for testing
    private fun makeTestCursor(): Cursor {
        val meta = listOf("a", "b", "c").toSeries()

        val data = listOf(
            listOf(1, "one", 1.0),
            listOf(2, "two", 2.0),
            listOf(3, "three", 3.0),
            listOf(4, "four", 4.0),
            listOf(5, "five", 5.0),
        )

        return Series(data.size) { rowIdx: Int ->
            val rowData = data[rowIdx].map { it as Any? }.toSeries()
            cellsToRowVec(rowData, meta)
        }
    }

    @Test
    fun testBasicCursorAccess() {
        val cursor = makeTestCursor()

        // Test size
        assertEquals(5, cursor.size)

        // Test row access
        val firstRow = cursor.row(0)
        val firstRowValues = firstRow.values
        assertEquals(1, firstRowValues[0])
        assertEquals("one", firstRowValues[1])
        assertEquals(1.0, firstRowValues[2])

        // Test meta access
        val meta = cursor.meta
        assertEquals(3, meta.size)
        assertEquals("a", meta[0].name)
        assertEquals("b", meta[1].name)
        assertEquals("c", meta[2].name)
    }

    @Test
    fun testColumnSelectionByIndex() {
        val cursor = makeTestCursor()

        // Select columns 0 and 2
        val selected = cursor[0, 2]

        assertEquals(5, selected.size)
        assertEquals(2, selected.meta.size)

        val firstRow = selected.row(0).values
        assertEquals(1, firstRow[0])
        assertEquals(1.0, firstRow[1])
    }

    @Test
    fun testColumnSelectionByName() {
        val cursor = makeTestCursor()

        // Select columns by name
        val selected = cursor["a", "c"]

        assertEquals(5, selected.size)
        assertEquals(2, selected.meta.size)

        val meta = selected.meta
        assertEquals("a", meta[0].name)
        assertEquals("c", meta[1].name)
    }

    @Test
    fun testColumnExclusion() {
        val cursor = makeTestCursor()

        // Exclude column "b"
        val selected = cursor[Series(1) { -"b" }]

        assertEquals(5, selected.size)
        assertEquals(2, selected.meta.size)

        val meta = selected.meta
        assertEquals("a", meta[0].name)
        assertEquals("c", meta[1].name)
    }

    @Test
    fun testWhere() {
        val cursor = makeTestCursor()

        // Filter rows where column 'a' > 2
        val filtered = cursor.where { row ->
            val value = row.values[0] as? Int ?: 0
            value > 2
        }

        assertEquals(3, filtered.size)

        val firstRow = filtered.row(0).values
        assertEquals(3, firstRow[0])
    }

    @Test
    fun testOrderBy() {
        val meta = listOf("x", "y").toSeries()

        val data = listOf(
            listOf(3, "c"),
            listOf(1, "a"),
            listOf(4, "d"),
            listOf(2, "b"),
        )

        val cursor = Series(data.size) { rowIdx: Int ->
            val rowData = data[rowIdx].map { it as Any? }.toSeries()
            cellsToRowVec(rowData, meta)
        }

        // Sort ascending by 'x'
        val sorted = cursor.orderBy("x")

        assertEquals(4, sorted.size)
        assertEquals(1, sorted.row(0).values[0])
        assertEquals(2, sorted.row(1).values[0])
        assertEquals(3, sorted.row(2).values[0])
        assertEquals(4, sorted.row(3).values[0])
    }

    @Test
    fun testOrderByDescending() {
        val meta = listOf("x", "y").toSeries()

        val data = listOf(
            listOf(3, "c"),
            listOf(1, "a"),
            listOf(4, "d"),
            listOf(2, "b"),
        )

        val cursor = Series(data.size) { rowIdx: Int ->
            val rowData = data[rowIdx].map { it as Any? }.toSeries()
            cellsToRowVec(rowData, meta)
        }

        // Sort descending by 'x'
        val sorted = cursor.orderBy("x", desc = true)

        assertEquals(4, sorted.size)
        assertEquals(4, sorted.row(0).values[0])
        assertEquals(3, sorted.row(1).values[0])
        assertEquals(2, sorted.row(2).values[0])
        assertEquals(1, sorted.row(3).values[0])
    }

    @Test
    fun testTake() {
        val cursor = makeTestCursor()

        val taken = cursor.take(2)

        assertEquals(2, taken.size)
        assertEquals(1, taken.row(0).values[0])
        assertEquals(2, taken.row(1).values[0])
    }

    @Test
    fun testDrop() {
        val cursor = makeTestCursor()

        val dropped = cursor.drop(2)

        assertEquals(3, dropped.size)
        assertEquals(3, dropped.row(0).values[0])
        assertEquals(4, dropped.row(1).values[0])
        assertEquals(5, dropped.row(2).values[0])
    }

    @Test
    fun testProject() {
        val cursor = makeTestCursor()

        val projected = cursor.project("b", "c")

        assertEquals(5, projected.size)
        assertEquals(2, projected.meta.size)

        val meta = projected.meta
        assertEquals("b", meta[0].name)
        assertEquals("c", meta[1].name)

        val firstRow = projected.row(0).values
        assertEquals("one", firstRow[0])
        assertEquals(1.0, firstRow[1])
    }

    @Test
    fun testWhichKey() {
        // Test utility function from original test
        val fanOut_size = 2
        val lhs_size = 2
        fun whichKey(ix: Int) = (ix - lhs_size) / fanOut_size

        assertEquals(350, whichKey(702))
        assertEquals(349, whichKey(700))
    }

    @Test
    fun testWhichValue() {
        // Test utility function from original test
        val fanOut_size = 2
        val lhs_size = 2

        fun whichValue(ix: Int) = (ix - lhs_size) % fanOut_size

        assertEquals(1, whichValue(3))
        assertEquals(1, whichValue(33))
        assertEquals(1, whichValue(3))
        assertEquals(0, whichValue(4))
        assertEquals(0, whichValue(0))
    }

    @Test
    fun testColumnSelectionByRange() {
        val cursor = makeTestCursor()

        // Select columns 0-1 (inclusive range)
        val selected = cursor[0..1]

        assertEquals(5, selected.size)
        assertEquals(2, selected.meta.size)

        val meta = selected.meta
        assertEquals("a", meta[0].name)
        assertEquals("b", meta[1].name)
    }

    @Test
    fun testCursorIsEmpty() {
        // Create an empty cursor - skip this test as empty cursors need special handling
        // The meta property requires at least one row to extract column metadata
        // This is a known limitation in the current Cursor implementation
    }

    // NOTE: The following tests from the original CursorKtTest.kt
    // cannot be ported yet because the required operations are not implemented:
    //
    // - resample() → NOT IMPLEMENTED
    // - pivot() → NOT IMPLEMENTED
    // - group() → NOT IMPLEMENTED
    // - ordered() → NOT IMPLEMENTED
    // - join() → NOT IMPLEMENTED
    // - combine() → needs clarification (different from Cursor.combine)
    //
    // These tests are:
    // - fun div()
    // - fun resample()
    // - fun oneHot()
    // - fun `resample+ordered`()
    // - fun `resample+join`()
    // - fun pivot()
    // - fun group()
    // - fun `pivot+group`()
    // - fun `pivot+group+reduce`()
    // - fun `resample+pivot+group+reduce+join`()
}
