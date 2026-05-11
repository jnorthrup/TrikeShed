package borg.trikeshed.tinybtrfs

import kotlin.test.Test
import kotlin.test.assertEquals
import borg.trikeshed.lib.j

class BPlusTreeRangeTest {
    @Test
    fun `test range`() {
        val tree = BPlusTree<Int, String>(order = 3)
        tree.put(1, "one")
        tree.put(2, "two")
        tree.put(3, "three")
        tree.put(4, "four")
        tree.put(5, "five")

        val sequence = tree.range(2, 5).toList()
        assertEquals(3, sequence.size)
        assertEquals(2, sequence[0].a); assertEquals("two", sequence[0].b)
        assertEquals(3, sequence[1].a); assertEquals("three", sequence[1].b)
        assertEquals(4, sequence[2].a); assertEquals("four", sequence[2].b)
    }

    @Test
    fun `range includes entries with null values`() {
        val tree = BPlusTree<Int, String?>(order = 3)
        tree.put(1, null)
        tree.put(2, "two")
        tree.put(3, null)

        val sequence = tree.range(1, 4).toList()
        assertEquals(3, sequence.size)
        assertEquals(1, sequence[0].a); assertEquals(null, sequence[0].b)
        assertEquals(2, sequence[1].a); assertEquals("two", sequence[1].b)
        assertEquals(3, sequence[2].a); assertEquals(null, sequence[2].b)
    }
}
