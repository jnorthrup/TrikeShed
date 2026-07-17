package borg.trikeshed.collections.btree

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.job.JobId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CowBPlusTreeTest {

    @Test
    fun testCodecGoldenBytes() {
        val key = BTreeKey("facet", byteArrayOf(1, 2, 3), JobId.of("job1"), 42L)
        val value = BTreeValue(ContentId("sha256:0000000000000000000000000000000000000000000000000000000000000000"), 100L)

        val leaf = BTreeNode.Leaf(listOf(key), listOf(value))
        val leafBytes = CowBPlusTreeCodec.encode(leaf)
        val decodedLeaf = CowBPlusTreeCodec.decode(leafBytes)

        assertEquals(leaf, decodedLeaf)

        val internal = BTreeNode.Internal(
            listOf(key),
            listOf(
                ContentId("sha256:1111111111111111111111111111111111111111111111111111111111111111"),
                ContentId("sha256:2222222222222222222222222222222222222222222222222222222222222222")
            )
        )
        val internalBytes = CowBPlusTreeCodec.encode(internal)
        val decodedInternal = CowBPlusTreeCodec.decode(internalBytes)

        assertEquals(internal, decodedInternal)
    }

    @Test
    fun testInsertAndGet() {
        val casStore = CasStore.inMemory()
        val tree = CowBPlusTree(casStore, maxDegree = 3) // Small degree to force splits

        var rootCid: ContentId? = null
        val keys = mutableListOf<BTreeKey>()

        for (i in 1..10) {
            val key = BTreeKey("f$i", byteArrayOf(i.toByte()), JobId.of("j$i"), i.toLong())
            val value = BTreeValue(ContentId("sha256:00000000000000000000000000000000000000000000000000000000000000" + i.toString().padStart(2, '0')), i.toLong())
            keys.add(key)
            rootCid = tree.insert(rootCid, key, value)
        }

        assertNotNull(rootCid)

        // Verify all keys can be fetched
        for (i in 1..10) {
            val key = keys[i - 1]
            val value = tree.get(rootCid!!, key)
            assertNotNull(value)
            assertEquals(i.toLong(), value.sequence)
        }

        // Non-existent key
        val nonExistentKey = BTreeKey("f99", byteArrayOf(99), JobId.of("j99"), 99L)
        assertNull(tree.get(rootCid!!, nonExistentKey))
    }

    @Test
    fun testRange() {
        val casStore = CasStore.inMemory()
        val tree = CowBPlusTree(casStore, maxDegree = 4)

        var rootCid: ContentId? = null
        val keys = mutableListOf<BTreeKey>()

        for (i in 1..20) {
            val key = BTreeKey("facet", byteArrayOf(i.toByte()), JobId.of("job1"), i.toLong())
            val value = BTreeValue(ContentId("sha256:0000000000000000000000000000000000000000000000000000000000000000"), i.toLong())
            keys.add(key)
            rootCid = tree.insert(rootCid, key, value)
        }

        assertNotNull(rootCid)

        keys.sort() // Ensure they are sorted for our check

        val startKey = keys[5] // i=6
        val endKey = keys[15] // i=16

        val result = tree.range(rootCid!!, startKey, endKey)

        assertEquals(11, result.size)
        for (i in 0..10) {
            assertEquals(keys[5 + i], result[i].first)
        }
    }

    @Test
    fun testDeterminismAndSnapshotStability() {
        val casStore1 = CasStore.inMemory()
        val tree1 = CowBPlusTree(casStore1, maxDegree = 4)

        val casStore2 = CasStore.inMemory()
        val tree2 = CowBPlusTree(casStore2, maxDegree = 4)

        val entries = (1..15).map { i ->
            Pair(
                BTreeKey("f$i", byteArrayOf(i.toByte()), JobId.of("j$i"), i.toLong()),
                BTreeValue(ContentId("sha256:0000000000000000000000000000000000000000000000000000000000000000"), i.toLong())
            )
        }

        // Insert in order
        var root1: ContentId? = null
        for ((k, v) in entries) {
            root1 = tree1.insert(root1, k, v)
        }

        // Insert in same order, should have exactly same root CID
        var root2: ContentId? = null
        for ((k, v) in entries) {
            root2 = tree2.insert(root2, k, v)
        }

        assertEquals(root1, root2)

        // Snapshot stability
        val snapshotRoot = root1!!
        val val5 = tree1.get(snapshotRoot, entries[4].first)
        assertNotNull(val5)
        assertEquals(5L, val5.sequence)

        // Update key 5 in new snapshot
        val updatedVal = BTreeValue(ContentId("sha256:0000000000000000000000000000000000000000000000000000000000000000"), 999L)
        val newRoot = tree1.insert(snapshotRoot, entries[4].first, updatedVal)

        // Old snapshot still has old value
        val val5Old = tree1.get(snapshotRoot, entries[4].first)
        assertNotNull(val5Old)
        assertEquals(5L, val5Old.sequence)

        // New snapshot has new value
        val val5New = tree1.get(newRoot, entries[4].first)
        assertNotNull(val5New)
        assertEquals(999L, val5New.sequence)
    }
}
