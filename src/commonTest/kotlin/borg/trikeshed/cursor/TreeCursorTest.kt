package borg.trikeshed.cursor

import borg.trikeshed.lib.j
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TreeCursorTest {

    private fun dummyRow(id: Int): RowVec {
        val meta: () -> ColumnMeta = { ColumnMeta("id", IOMemento.IoInt) }
        return 1 j { id j meta }
    }

    @Test
    fun `navigate descends into children based on path`() {
        val leaf1 = TreeCursor(row = dummyRow(3))
        val leaf2 = TreeCursor(row = dummyRow(4))

        val child1 = TreeCursor(
            row = dummyRow(1),
            children = sequenceOf(leaf1, leaf2)
        )

        val child2 = TreeCursor(
            row = dummyRow(2)
        )

        val root = TreeCursor(
            row = dummyRow(0),
            children = sequenceOf(child1, child2)
        )

        val target = root.navigate(intArrayOf(0, 1))

        assertEquals(4, target.row.b(0).a, "Should navigate to leaf2 which has id=4")
    }

    @Test
    fun `navigate returns this for empty path`() {
        val root = TreeCursor(row = dummyRow(0))
        val target = root.navigate(intArrayOf())
        assertEquals(root, target)
    }

    @Test
    fun `navigate throws on out of bounds path`() {
        val root = TreeCursor(row = dummyRow(0))
        assertFailsWith<IndexOutOfBoundsException> {
            root.navigate(intArrayOf(0))
        }
    }
}
