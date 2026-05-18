package borg.trikeshed.patl

import kotlin.test.*

class IntNodeStoreTest {
    @Test
    fun `initRoot creates single node with NULL parent and no children`() {
        val store = IntNodeStore()
        val root = store.initRoot()
        assertEquals(0, root)
        assertEquals(1, store.size)
        assertTrue(store.getParent(root) == IntNodeStore.NULL)
        assertTrue(store.getLeftChild(root) == IntNodeStore.NULL)
        assertTrue(store.getRightChild(root) == IntNodeStore.NULL)
        assertTrue(store.getSkip(root) == 0)
    }

    @Test
    fun `append creates second node with distinct index and correct parent`() {
        val store = IntNodeStore()
        val root = store.initRoot()
        val child = store.append(
            parent = root,
            parentId = 1,  // right child
            leftChild = IntNodeStore.NULL,
            rightChild = IntNodeStore.NULL,
            skip = 7
        )
        assertEquals(1, child)
        assertEquals(2, store.size)
        // parent stored with LSB = parentId (1 for right child)
        assertTrue(store.getParentRaw(child) == (root or 1))
        assertTrue(store.getLeftChild(child) == IntNodeStore.NULL)
        assertTrue(store.getRightChild(child) == IntNodeStore.NULL)
        assertTrue(store.getSkip(child) == 7)
    }

    @Test
    fun `getChild dispatches on id 0 for left and 1 for right`() {
        val store = IntNodeStore()
        store.initRoot()
        val left = store.append(parent = 0, parentId = 0, leftChild = IntNodeStore.NULL, rightChild = IntNodeStore.NULL, skip = 0)
        val right = store.append(parent = 0, parentId = 1, leftChild = IntNodeStore.NULL, rightChild = IntNodeStore.NULL, skip = 0)
        store.setLeftChild(0, left)
        store.setRightChild(0, right)

        assertTrue(store.getChild(0, 0) == left)
        assertTrue(store.getChild(0, 1) == right)
    }

    @Test
    fun `getParent strips LSB to return parent index only`() {
        val store = IntNodeStore()
        store.initRoot()
        store.append(parent = 0, parentId = 1, leftChild = IntNodeStore.NULL, rightChild = IntNodeStore.NULL, skip = 0)

        assertTrue(store.getParent(1) == 0)
    }
}
