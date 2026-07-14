package borg.trikeshed.collections.btree

import borg.trikeshed.job.ContentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * I1 — COW B+tree RED tests.
 *
 * Proves: copy-on-write B+tree with CAS-backed immutable pages,
 * deterministic splits, ordered/range queries, and snapshot stability.
 *
 * None of these types exist yet.
 */
class CowBPlusTreeTest {

    @Test
    fun insertAndExactLookupByKey() {
        val tree = CowBPlusTree<String, String>(maxLeafKeys = 4)
        tree.insert("alpha", "val-a")
        tree.insert("beta", "val-b")
        tree.insert("gamma", "val-g")

        assertEquals("val-a", tree.get("alpha"))
        assertEquals("val-b", tree.get("beta"))
        assertEquals("val-g", tree.get("gamma"))
        assertNull(tree.get("delta"))
    }

    @Test
    fun insertTriggersLeafSplitWhenExceedingCapacity() {
        val tree = CowBPlusTree<String, Int>(maxLeafKeys = 3)
        tree.insert("a", 1)
        tree.insert("b", 2)
        tree.insert("c", 3)
        tree.insert("d", 4) // exceeds capacity=3, triggers split

        assertEquals(4, tree.size)
        assertEquals(1, tree.get("a"))
        assertEquals(4, tree.get("d"))
    }

    @Test
    fun orderedRangeQuery() {
        val tree = CowBPlusTree<String, Int>(maxLeafKeys = 4)
        listOf("a" to 1, "c" to 3, "e" to 5, "b" to 2, "d" to 4).forEach { (k, v) -> tree.insert(k, v) }

        val range = tree.range("b", "d").toList()
        assertEquals(listOf("b" to 2, "c" to 3, "d" to 4), range)
    }

    @Test
    fun snapshotRemainsStableAfterMutation() {
        val tree = CowBPlusTree<String, Int>(maxLeafKeys = 4)
        tree.insert("a", 1)
        tree.insert("b", 2)

        val snap = tree.snapshot()
        val snapRoot = snap.rootCid

        tree.insert("c", 3)
        tree.insert("d", 4)

        // Old snapshot must be unchanged
        assertEquals(2, snap.size)
        assertEquals(1, snap.get("a"))
        assertEquals(2, snap.get("b"))
        assertNull(snap.get("c"))
        assertEquals(snapRoot, snap.rootCid, "snapshot root CID must not change")

        // Current tree has all 4
        assertEquals(4, tree.size)
    }

    @Test
    fun deterministicSplitProducesSameRootCid() {
        val tree1 = CowBPlusTree<String, Int>(maxLeafKeys = 3)
        val tree2 = CowBPlusTree<String, Int>(maxLeafKeys = 3)

        listOf("a" to 1, "b" to 2, "c" to 3, "d" to 4).forEach { (k, v) ->
            tree1.insert(k, v)
            tree2.insert(k, v)
        }

        assertEquals(tree1.rootCid, tree2.rootCid,
            "deterministic insertion must produce the same root CID")
    }

    @Test
    fun pageSplitIsDeterministicAcrossInsertionOrder() {
        // Insert same keys in different orders — same final root CID
        val tree1 = CowBPlusTree<String, Int>(maxLeafKeys = 3)
        val tree2 = CowBPlusTree<String, Int>(maxLeafKeys = 3)

        listOf("d" to 4, "a" to 1, "c" to 3, "b" to 2).forEach { (k, v) -> tree1.insert(k, v) }
        listOf("a" to 1, "b" to 2, "c" to 3, "d" to 4).forEach { (k, v) -> tree2.insert(k, v) }

        assertEquals(tree1.rootCid, tree2.rootCid,
            "same keys in different order must produce same root CID (B+tree is sorted)")
    }
}
