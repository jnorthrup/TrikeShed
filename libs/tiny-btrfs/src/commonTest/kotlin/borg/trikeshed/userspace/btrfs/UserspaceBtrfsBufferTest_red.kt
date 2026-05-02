package borg.trikeshed.userspace.btrfs

import borg.trikeshed.collections._a
import borg.trikeshed.collections.s_
import borg.trikeshed.context.ElementState
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Red tests for UserspaceBtrfsBuffer — lifecycle + freelist + extent mapping.
 *
 * ## Join algebra throughout
 * - `BtrfsItem(Key j DataBytes)` — leaf item smart constructor
 * - `BtrfsChildPointer(Key j BlockPtr)` — internal node child pointer
 * - `encodeLeaf(BtrfsLeaf(items), buf)` → validates + writes
 * - `decodeLeaf(buf)` → BtrfsLeaf — validates + reads
 */
class UserspaceBtrfsBufferTest_red {

    @Test
    fun initialStateIsCreated() = runTest {
        val buf = UserspaceBtrfsBuffer(chunkSize = 4096)
        assertEquals(ElementState.CREATED, buf.state)
    }

    @Test
    fun openTransitionsToOpen() = runTest {
        val buf = UserspaceBtrfsBuffer(chunkSize = 4096)
        buf.open()
        assertEquals(ElementState.OPEN, buf.state)
        buf.close()
    }

    @Test
    fun allocateAndFree_freelistReusesId() = runTest {
        val buf = UserspaceBtrfsBuffer(chunkSize = 4096)
        buf.open()

        val id1 = buf.allocateNode()
        buf.freeNode(id1)
        val id2 = buf.allocateNode()

        assertEquals(id1, id2, "freed ID should be reused")
        assertEquals(1, buf.chunkCount())
        buf.close()
    }

    @Test
    fun writeLeaf_andReadBack() = runTest {
        val buf = UserspaceBtrfsBuffer(chunkSize = 4096)
        buf.open()

        val key = BtrfsKey(1uL, 1u, 0uL)
        val data = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val item: BtrfsItem = BtrfsItem(key, data)
        val leaf = BtrfsLeaf(s_[item])

        val nodeId = buf.allocateNode()
        buf.writeLeaf(nodeId, leaf)

        val decoded = buf.readLeaf(nodeId)
        assertEquals(1, decoded.items.size)
        assertEquals(key.objectId, decoded.items[0].a.objectId)
        assertTrue(decoded.items[0].b.contentEquals(data))

        buf.close()
    }

    @Test
    fun writeMultipleItems_leafEncodeDecodeRoundTrip() = runTest {
        val buf = UserspaceBtrfsBuffer(chunkSize = 4096)
        buf.open()

        val items = 4 j { i:Int ->
            BtrfsItem(BtrfsKey(i.inc().toULong(), 1u, 0uL), _a[(i.inc().toByte())])
        }
        val leaf = BtrfsLeaf(items)
        val nodeId = buf.allocateNode()
        buf.writeLeaf(nodeId, leaf)

        val decoded = buf.readLeaf(nodeId)
        assertEquals(5, decoded.items.size)
        assertTrue(decoded.items.view.all { it.b.size == 1 })

        buf.close()
    }

    @Test
    fun closeClearsStore() = runTest {
        val buf = UserspaceBtrfsBuffer(chunkSize = 4096)
        buf.open()
        val id = buf.allocateNode()
        buf.writeNode(id, ByteArray(4096))
        assertEquals(1, buf.chunkCount())
        buf.close()
        assertEquals(0, buf.chunkCount())
    }

    @Test
    fun readNonExistentNode_returnsNull() = runTest {
        val buf = UserspaceBtrfsBuffer(chunkSize = 4096)
        buf.open()
        assertNull(buf.readNode("does-not-exist"))
        buf.close()
    }

    @Test
    fun freelistAccumulatesFreedIds() = runTest {
        val buf = UserspaceBtrfsBuffer(chunkSize = 4096)
        buf.open()

        repeat(3) { buf.allocateNode() }
        assertEquals(0, buf.freeCount())

        buf.freeNode("n-1")
        buf.freeNode("n-2")
        assertEquals(2, buf.freeCount())

        buf.close()
    }

    @Test
    fun totalAllocatedCountsAllAllocations() = runTest {
        val buf = UserspaceBtrfsBuffer(chunkSize = 4096)
        buf.open()
        repeat(7) { buf.allocateNode() }
        assertEquals(7L, buf.totalAllocated())
        buf.close()
    }
}
