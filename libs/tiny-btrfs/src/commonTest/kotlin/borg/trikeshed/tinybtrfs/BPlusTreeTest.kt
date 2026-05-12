package borg.trikeshed.tinybtrfs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BPlusTreeTest {
    @Test
    fun `put and get simple`() {
        val tree = BPlusTree<Int, CharSequence>(order = 3)
        assertEquals(0, tree.size())
        assertNull(tree.get(1))
        tree.put(1, "one")
        assertEquals("one", tree.get(1))
        tree.put(2, "two")
        tree.put(3, "three")
        tree.put(4, "four")
        assertEquals(4, tree.size())
        assertEquals("three", tree.get(3))
        // replace existing
        tree.put(3, "THREE")
        assertEquals("THREE", tree.get(3))
        assertEquals(4, tree.size())
    }
}
