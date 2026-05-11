package borg.trikeshed.tinybtrfs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BPlusTreeRemoveTest {
    @Test
    fun `test remove`() {
        val tree = BPlusTree<Int, String>(order = 3)
        tree.put(1, "one")
        tree.put(2, "two")
        tree.put(3, "three")
        tree.remove(2)
        assertNull(tree.get(2))
        assertEquals("one", tree.get(1))
        assertEquals("three", tree.get(3))
        assertEquals(2, tree.size())
    }

    @Test
    fun `remove works when stored value is null`() {
        val tree = BPlusTree<Int, String?>(order = 3)
        tree.put(1, null)
        tree.put(2, "two")

        assertTrue(tree.remove(1))
        assertEquals(1, tree.size())
        assertNull(tree.get(1))
        assertEquals("two", tree.get(2))
    }

    @Test
    fun `remove merge preserves null values in sibling leaf`() {
        val tree = BPlusTree<Int, String?>(order = 3)
        tree.put(1, "one")
        tree.put(2, "two")
        tree.put(3, null)
        tree.put(4, "four")

        assertTrue(tree.remove(1))
        assertTrue(tree.remove(2))
        assertEquals(2, tree.size())
        assertNull(tree.get(3))
        assertEquals("four", tree.get(4))
    }
}
