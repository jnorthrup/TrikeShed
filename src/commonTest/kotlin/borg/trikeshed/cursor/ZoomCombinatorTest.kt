package borg.trikeshed.cursor

import borg.trikeshed.lib.*
import kotlin.test.*

class ZoomCombinatorTest {

    private fun createNestedCursor(): Cursor {
        // Parent schema
        val metaId: () -> ColumnMeta = { ColumnMeta("id", IOMemento.IoInt) }
        val metaName: () -> ColumnMeta = { ColumnMeta("name", IOMemento.IoString) }
        
        // Child schema
        val metaItemId: () -> ColumnMeta = { ColumnMeta("item_id", IOMemento.IoInt) }
        val metaItemName: () -> ColumnMeta = { ColumnMeta("item_name", IOMemento.IoString) }
        
        val nestedMeta: () -> ColumnMeta = { ColumnMeta("items", IOMemento.IoArray, metaItemId()) }

        // Child rows
        val childRow1: RowVec = 2 j { c -> if (c == 0) 101 j metaItemId else "apple" j metaItemName }
        val childRow2: RowVec = 2 j { c -> if (c == 0) 102 j metaItemId else "banana" j metaItemName }
        val childRow3: RowVec = 2 j { c -> if (c == 0) 103 j metaItemId else "cherry" j metaItemName }

        // Child cursors
        val childCursor1: Cursor = 2 j { r -> if (r == 0) childRow1 else childRow2 }
        val childCursor2: Cursor = 1 j { r -> childRow3 }
        val childCursorEmpty: Cursor = 0 j { throw IndexOutOfBoundsException() }
        val childCursorNull: Cursor? = null

        // Parent rows
        val parentRow1: RowVec = 3 j { c ->
            when (c) {
                0 -> 1 j metaId
                1 -> "Alice" j metaName
                else -> childCursor1 j nestedMeta
            }
        }
        val parentRow2: RowVec = 3 j { c ->
            when (c) {
                0 -> 2 j metaId
                1 -> "Bob" j metaName
                else -> childCursor2 j nestedMeta
            }
        }
        val parentRow3: RowVec = 3 j { c ->
            when (c) {
                0 -> 3 j metaId
                1 -> "Charlie" j metaName
                else -> childCursorEmpty j nestedMeta
            }
        }
        val parentRow4: RowVec = 3 j { c ->
            when (c) {
                0 -> 4 j metaId
                1 -> "David" j metaName
                else -> childCursorNull j nestedMeta
            }
        }

        return 4 j { r ->
            when (r) {
                0 -> parentRow1
                1 -> parentRow2
                2 -> parentRow3
                else -> parentRow4
            }
        }
    }

    @Test
    fun `zoom extracts nested cursor rows`() {
        val cursor = createNestedCursor()
        val zoomed = cursor.zoom("items")

        assertEquals(3, zoomed.size)
        
        // Check first row (from Alice)
        val row0 = zoomed[0]
        assertEquals(2, row0.size)
        assertEquals(101, row0.b(0).a)
        assertEquals("apple", row0.b(1).a)

        // Check second row (from Alice)
        val row1 = zoomed[1]
        assertEquals(102, row1.b(0).a)
        assertEquals("banana", row1.b(1).a)

        // Check third row (from Bob)
        val row2 = zoomed[2]
        assertEquals(103, row2.b(0).a)
        assertEquals("cherry", row2.b(1).a)
    }

    @Test
    fun `zoom on non-existent column returns empty cursor`() {
        val cursor = createNestedCursor()
        val zoomed = cursor.zoom("non_existent")
        assertEquals(0, zoomed.size)
    }

    @Test
    fun `zoom on empty cursor returns empty cursor`() {
        val emptyCursor: Cursor = 0 j { throw IndexOutOfBoundsException() }
        val zoomed = emptyCursor.zoom("items")
        assertEquals(0, zoomed.size)
    }
}
