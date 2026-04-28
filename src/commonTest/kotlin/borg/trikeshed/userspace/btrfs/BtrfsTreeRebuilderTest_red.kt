package borg.trikeshed.userspace.btrfs

import borg.trikeshed.tinybtrfs.DiskAdapter
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TDD RED: BtrfsTreeRebuilder tests.
 *
 * Tests the tree rebuilder which accepts (BtrfsKey, ByteArray) pairs
 * and maintains them in sorted order via an in-memory B+Tree.
 */
class BtrfsTreeRebuilderTest_red {

    private fun makeTestAdapter(): UserspaceMemoryBuffer {
        return UserspaceMemoryBuffer(chunkSize = 4096)
    }

    @Test
    fun rebuildEmptyTree_findReturnsNull() = runTest {
        val adapter = makeTestAdapter()
        adapter.open()
        val rebuilder = BtrfsTreeRebuilder(adapter)
        val found = rebuilder.find(BtrfsKey(1uL, 1u, 0uL))
        assertNull(found)
        adapter.close()
    }

    @Test
    fun singleInsert_findReturnsValue() = runTest {
        val adapter = makeTestAdapter()
        adapter.open()
        val rebuilder = BtrfsTreeRebuilder(adapter)

        val key = BtrfsKey(1uL, 1u, 0uL)
        val value = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        rebuilder.insert(key, value)

        val found = rebuilder.find(key)
        assertNotNull(found)
        assertTrue(found.contentEquals(value))

        adapter.close()
    }

    @Test
    fun multipleInserts_sortedOrder() = runTest {
        val adapter = makeTestAdapter()
        adapter.open()
        val rebuilder = BtrfsTreeRebuilder(adapter)

        val keys = listOf(
            BtrfsKey(3uL, 1u, 0uL),
            BtrfsKey(1uL, 1u, 0uL),
            BtrfsKey(2uL, 1u, 0uL),
            BtrfsKey(5uL, 1u, 0uL),
            BtrfsKey(4uL, 1u, 0uL)
        )
        keys.forEachIndexed { idx, k ->
            rebuilder.insert(k, byteArrayOf(idx.toByte()))
        }

        // Range query from first to last should return all
        val range = rebuilder.range(BtrfsKey(0uL, 0u, 0uL), BtrfsKey(ULong.MAX_VALUE, UInt.MAX_VALUE, ULong.MAX_VALUE))
        val results = range.toList()
        assertEquals(keys.size, results.size)
        // Should be in sorted order
        for (i in 1 until results.size) {
            assertTrue(results[i - 1].first <= results[i].first)
        }

        adapter.close()
    }

    @Test
    fun rangeQuery_returnsMatchingRange() = runTest {
        val adapter = makeTestAdapter()
        adapter.open()
        val rebuilder = BtrfsTreeRebuilder(adapter)

        (1L..10L).forEach { i ->
            rebuilder.insert(BtrfsKey(i.toULong(), 1u, 0uL), byteArrayOf(i.toByte()))
        }

        val range = rebuilder.range(BtrfsKey(3uL, 1u, 0uL), BtrfsKey(7uL, 1u, 0uL))
        val results = range.toList()
        assertEquals(4, results.size)  // 3, 4, 5, 6
        results.forEach { (k, _) ->
            assertTrue(k.objectId >= 3uL && k.objectId < 7uL)
        }

        adapter.close()
    }

    @Test
    fun unsortedInserts_throwsIllegalStateException() = runTest {
        val adapter = makeTestAdapter()
        adapter.open()
        val rebuilder = BtrfsTreeRebuilder(adapter)

        rebuilder.insert(BtrfsKey(3uL, 1u, 0uL), byteArrayOf(3))
        // Inserting a smaller key should throw
        assertFailsWith<IllegalStateException> {
            rebuilder.insert(BtrfsKey(1uL, 1u, 0uL), byteArrayOf(1))
        }

        adapter.close()
    }

    @Test
    fun emptyRange_returnsEmptySequence() = runTest {
        val adapter = makeTestAdapter()
        adapter.open()
        val rebuilder = BtrfsTreeRebuilder(adapter)

        rebuilder.insert(BtrfsKey(5uL, 1u, 0uL), byteArrayOf(5))

        val range = rebuilder.range(BtrfsKey(1uL, 1u, 0uL), BtrfsKey(3uL, 1u, 0uL))
        val results = range.toList()
        assertTrue(results.isEmpty())

        adapter.close()
    }

    @Test
    fun find_nonExistentKey_returnsNull() = runTest {
        val adapter = makeTestAdapter()
        adapter.open()
        val rebuilder = BtrfsTreeRebuilder(adapter)

        rebuilder.insert(BtrfsKey(5uL, 1u, 0uL), byteArrayOf(5))

        val found = rebuilder.find(BtrfsKey(99uL, 1u, 0uL))
        assertNull(found)

        adapter.close()
    }

    @Test
    fun largeSequentialInserts_allFound() = runTest {
        val adapter = makeTestAdapter()
        adapter.open()
        val rebuilder = BtrfsTreeRebuilder(adapter)

        val count = 100
        (1L..count).forEach { i ->
            rebuilder.insert(BtrfsKey(i.toULong(), 1u, 0uL), byteArrayOf((i and 0xFF).toByte()))
        }

        (1L..count).forEach { i ->
            val found = rebuilder.find(BtrfsKey(i.toULong(), 1u, 0uL))
            assertNotNull(found, "key $i should be found")
            assertEquals((i and 0xFF).toByte(), found[0])
        }

        adapter.close()
    }

    @Test
    fun duplicateKey_replacesValue() = runTest {
        val adapter = makeTestAdapter()
        adapter.open()
        val rebuilder = BtrfsTreeRebuilder(adapter)

        val key = BtrfsKey(1uL, 1u, 0uL)
        rebuilder.insert(key, byteArrayOf(0xAA.toByte()))
        rebuilder.insert(key, byteArrayOf(0xBB.toByte()))

        val found = rebuilder.find(key)
        assertNotNull(found)
        assertEquals(0xBB.toByte(), found[0])

        adapter.close()
    }

    @Test
    fun rangeQuery_boundaryConditions() = runTest {
        val adapter = makeTestAdapter()
        adapter.open()
        val rebuilder = BtrfsTreeRebuilder(adapter)

        rebuilder.insert(BtrfsKey(1uL, 1u, 0uL), byteArrayOf(1))
        rebuilder.insert(BtrfsKey(5uL, 1u, 0uL), byteArrayOf(5))

        // Start == key should be included
        val r1 = rebuilder.range(BtrfsKey(1uL, 1u, 0uL), BtrfsKey(5uL, 1u, 0uL)).toList()
        assertEquals(1, r1.size)  // only key 1, not key 5 (end is exclusive)

        // Start < first key
        val r2 = rebuilder.range(BtrfsKey(0uL, 1u, 0uL), BtrfsKey(3uL, 1u, 0uL)).toList()
        assertEquals(1, r2.size)  // key 1

        // End > last key
        val r3 = rebuilder.range(BtrfsKey(3uL, 1u, 0uL), BtrfsKey(10uL, 1u, 0uL)).toList()
        assertEquals(1, r3.size)  // key 5

        adapter.close()
    }
}
