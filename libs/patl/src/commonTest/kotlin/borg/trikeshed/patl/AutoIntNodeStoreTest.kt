package borg.trikeshed.patl

import kotlin.test.*

class AutoIntNodeStoreTest {
    @Test
    fun `freeze then read-back preserves all node fields`() {
        val store = IntNodeStore()
        store.initRoot()
        store.append(parent = 0, parentId = 0, leftChild = IntNodeStore.NULL, rightChild = IntNodeStore.NULL, skip = 3)
        store.append(parent = 0, parentId = 1, leftChild = IntNodeStore.NULL, rightChild = IntNodeStore.NULL, skip = 7)

        val frozen = AutoIntNodeStore.freeze(store)

        assertEquals(3, frozen.size)

        // Root
        assertEquals(IntNodeStore.NULL, frozen.getParent(0))
        assertEquals(0, frozen.getSkip(0))

        // Node 1 — left child of root
        assertEquals(0, frozen.getParent(1))  // LSB stripped
        assertEquals(3, frozen.getSkip(1))

        // Node 2 — right child of root
        assertEquals(0, frozen.getParent(2))
        assertEquals(7, frozen.getSkip(2))
    }

    @Test
    fun `empty store freezes empty`() {
        val store = IntNodeStore()
        val frozen = AutoIntNodeStore.freeze(store)
        assertEquals(0, frozen.size)
    }
}
