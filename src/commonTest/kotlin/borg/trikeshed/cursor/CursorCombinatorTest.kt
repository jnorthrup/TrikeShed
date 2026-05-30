@file:Suppress("NonAsciiCharacters", "UNCHECKED_CAST")
package borg.trikeshed.cursor

import borg.trikeshed.lib.*
import kotlin.test.*

/**
 * Cursor combinator tests — ported from columnar's cursor operations
 */
class CursorCombinatorTest {

    /**
     * Helper: create a simple cursor with metadata
     */
    fun createTestCursor(): Cursor {
        // Create metadata suppliers
        val metaId: () -> ColumnMeta = { ColumnMeta("id", IOMemento.IoInt) }
        val metaName: () -> ColumnMeta = { ColumnMeta("name", IOMemento.IoString) }
        val metaValue: () -> ColumnMeta = { ColumnMeta("value", IOMemento.IoDouble) }

        // Create rows
        val row0: RowVec = 3 j { c: Int ->
            when (c) {
                0 -> null j metaId
                1 -> null j metaName
                else -> null j metaValue
            }
        }

        val row1: RowVec = 3 j { c: Int ->
            when (c) {
                0 -> 1 j metaId
                1 -> "alice" j metaName
                else -> 1.5 j metaValue
            }
        }

        val row2: RowVec = 3 j { c: Int ->
            when (c) {
                0 -> 2 j metaId
                1 -> "bob" j metaName
                else -> 2.5 j metaValue
            }
        }

        // Create cursor
        return 3 j { row: Int ->
            when (row) {
                0 -> row0
                1 -> row1
                else -> row2
            }
        }
    }

    @Test
    fun `cursor size and row access`() {
        val cursor = createTestCursor()
        assertEquals(3, cursor.size)

        val row = cursor[1]
        assertEquals(3, row.size)
    }

    @Test
    fun `cursor select by indices`() {
        val cursor = createTestCursor()
        val selected = cursor.select(0, 2)

        assertEquals(3, selected.size)
        val firstRow = selected[0]
        assertEquals(2, firstRow.size)
    }

    @Test
    fun `cursor select by names`() {
        val cursor = createTestCursor()
        val selected = cursor.select("id", "name")

        assertEquals(3, selected.size)
        val firstRow = selected[0]
        assertEquals(2, firstRow.size)
    }

    @Test
    fun `cursor head and tail`() {
        val cursor = createTestCursor()

        val head = cursor.head
        assertEquals(3, head.size)

        val tail = cursor.tail
        assertEquals(2, tail.size)
    }

    @Test
    fun `cursor column names`() {
        val cursor = createTestCursor()
        val names = cursor.columnNames

        assertEquals(3, names.size)
        assertEquals("id", names[0])
        assertEquals("name", names[1])
        assertEquals("value", names[2])
    }

    @Test
    fun `cursor width`() {
        val cursor = createTestCursor()
        assertEquals(3, cursor.width)
    }

    @Test
    fun `cursor minus column`() {
        val cursor = createTestCursor()
        val withoutValue = cursor - "value"

        assertEquals(3, withoutValue.size)
        assertEquals(2, withoutValue.width)
    }

    @Test
    fun `join two cursors`() {
        val cursor1 = createTestCursor()
        val cursor2 = createTestCursor()

        val joined = join(cursor1, cursor2)

        assertEquals(3, joined.size)
        assertEquals(6, joined.width)
    }

    @Test
    fun `combine two cursors`() {
        val cursor1 = createTestCursor()
        val cursor2 = createTestCursor()

        val combined = combine(cursor1, cursor2)

        assertEquals(6, combined.size)
        assertEquals(3, combined.width)
    }

    @Test
    fun `cursor projection with alpha`() {
        val cursor = createTestCursor()
        val projected = cursor α { row -> row.size }

        assertEquals(3, projected.size)
        assertEquals(3, projected[0])
    }

    @Test
    fun `cursor range selection`() {
        val cursor = createTestCursor()
        val range = cursor[0..1]

        assertEquals(2, range.size)
    }
}
