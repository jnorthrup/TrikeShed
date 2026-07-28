@file:Suppress("NonAsciiCharacters")
package borg.trikeshed.cursor

import borg.trikeshed.lib.*
import kotlin.test.*

class ZoomTest {

    private fun createInnerCursor(parentId: Int): Cursor {
        val metaName: () -> ColumnMeta = { ColumnMeta("name", IOMemento.IoString) }
        val metaScore: () -> ColumnMeta = { ColumnMeta("score", IOMemento.IoDouble) }
        
        return 2 j { row: Int ->
            val childId = parentId * 10 + row
            2 j { c: Int ->
                when (c) {
                    0 -> "child_${childId}" j metaName
                    else -> (childId * 1.5) j metaScore
                }
            }
        }
    }

    private fun createNestedCursor(): Cursor {
        val metaId: () -> ColumnMeta = { ColumnMeta("id", IOMemento.IoInt) }
        val innerChildMeta: () -> ColumnMeta = { ColumnMeta("name", IOMemento.IoString) }
        val metaChildren: () -> ColumnMeta = { 
            ColumnMeta("children", IOMemento.IoArray, ColumnMeta("child_schema", IOMemento.IoObject, innerChildMeta())) 
        }

        return 3 j { row: Int ->
            val id = row + 1
            2 j { c: Int ->
                when (c) {
                    0 -> id j metaId
                    else -> createInnerCursor(id) j metaChildren
                }
            }
        }
    }

    @Test
    fun `zoom into non-existent path throws error`() {
        val cursor = createNestedCursor()
        assertFailsWith<IllegalStateException> {
            cursor.zoom("does_not_exist", "another_one")
        }
    }

    @Test
    fun `zoom by column name returns correctly unnested children`() {
        val cursor = createNestedCursor()
        val zoomed = cursor.zoom("children")
        
        assertEquals(6, zoomed.size, "3 parents * 2 children each = 6")
        
        // Check random access property O(log N) unnesting
        val row0 = zoomed[0]
        assertEquals("child_10", row0.b(0).a)
        assertEquals(15.0, row0.b(1).a)
        
        val row3 = zoomed[3]
        assertEquals("child_21", row3.b(0).a)
        assertEquals(31.5, row3.b(1).a)
        
        val row5 = zoomed[5]
        assertEquals("child_31", row5.b(0).a)
        assertEquals(46.5, row5.b(1).a)
    }

    @Test
    fun `zoom by index returns correctly unnested children`() {
        val cursor = createNestedCursor()
        // Column 1 is "children"
        val zoomed = cursor.zoom(1)
        
        assertEquals(6, zoomed.size, "3 parents * 2 children each = 6")
        
        val row2 = zoomed[2]
        assertEquals("child_20", row2.b(0).a)
        assertEquals(30.0, row2.b(1).a)
        
        val row4 = zoomed[4]
        assertEquals("child_30", row4.b(0).a)
        assertEquals(45.0, row4.b(1).a)
    }
    
    @Test
    fun `zoom into empty cursor returns empty cursor`() {
        val empty: Cursor = emptySeries()
        val zoomed = empty.zoom("children")
        assertEquals(0, zoomed.size)
    }

    @Test
    fun `zoom with empty path returns self`() {
        val cursor = createNestedCursor()
        val pathNames: Array<CharSequence> = emptyArray()
        val zoomed = cursor.zoom(*pathNames)
        assertEquals(3, zoomed.size)
        assertEquals(cursor[1].b(0).a, zoomed[1].b(0).a)
    }
}
