package borg.trikeshed.userspace.btrfs

import borg.trikeshed.collections._a
import borg.trikeshed.context.ElementState
import borg.trikeshed.lib.*
import borg.trikeshed.tinybtrfs.toNodeId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Red tests for UserspaceBtrfsBuffer — lifecycle + freelist + extent mapping.
 *
 * ## Join algebra throughout
 * - `BtrfsItem(Key j DataBytes)` — leaf item smart constructor
 * - `BtrfsChildPointer(Key j BlockPtr)` — internal node child pointer
 * - `encodeLeaf(items, buf)` → validates + writes
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

        val key: BtrfsKeyAtom = 1uL j (1u j 0uL)
        val data = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val items: Series<Join<BtrfsKeyAtom, ByteArray>> = 1 j { key j data }
        val leaf: Series<Join<BtrfsKeyAtom, ByteArray>> = items

        val nodeId = buf.allocateNode()
        buf.writeLeaf(nodeId, leaf)

        val decoded = buf.readLeaf(nodeId)
        assertEquals(1, decoded.size)
        assertEquals(key.objectId, decoded[0].a.objectId)
        assertTrue(decoded[0].b.contentEquals(data))

        buf.close()
    }

    @Test
    fun writeInternal_andReadBack() = runTest {
        val buf = UserspaceBtrfsBuffer(chunkSize = 4096)
        buf.open()

        val internal: BtrfsInternal = 2 j { i: Int ->
            ((i + 1).toULong() j (1u j 0uL)) j "child-$i".toNodeId()
        }

        val nodeId = buf.allocateNode()
        buf.writeInternal(nodeId, internal)

        val decoded = buf.readInternal(nodeId)
        assertEquals(2, decoded.size)
        assertEquals(internal[0].a.objectId, decoded[0].a.objectId)
        assertEquals(internal[0].b, decoded[0].b)
        assertEquals(internal[1].a.objectId, decoded[1].a.objectId)
        assertEquals(internal[1].b, decoded[1].b)

        buf.close()
    }

    @Test
    fun writeMultipleItems_leafEncodeDecodeRoundTrip() = runTest {
        val buf = UserspaceBtrfsBuffer(chunkSize = 4096)
        buf.open()

        val items: Series<Join<BtrfsKeyAtom, ByteArray>> = 4 j { i:Int ->
            (i.inc().toULong() j (1u j 0uL)) j _a[(i.inc().toByte())]
        }
        val leaf: Series<Join<BtrfsKeyAtom, ByteArray>> = items
        val nodeId = buf.allocateNode()
        buf.writeLeaf(nodeId, leaf)

        val decoded = buf.readLeaf(nodeId)
        assertEquals(4, decoded.size)
        assertTrue(decoded.view.all { it.b.size == 1 })

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
        assertNull(buf.readNode("does-not-exist".toNodeId()))
        buf.close()
    }

    @Test
    fun freelistAccumulatesFreedIds() = runTest {
        val buf = UserspaceBtrfsBuffer(chunkSize = 4096)
        buf.open()

        repeat(3) { buf.allocateNode() }
        assertEquals(0, buf.freeCount())

        buf.freeNode("n-1".toNodeId())
        buf.freeNode("n-2".toNodeId())
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

    @Test
    fun decodeLeafRejectsInternalNodeMagic() {
        val buf = ByteArray(BTRFS_NODE_SIZE)
        buf[0] = (BTRFS_INTERNAL_MAGIC and 0xFF).toByte()
        buf[1] = ((BTRFS_INTERNAL_MAGIC shr 8) and 0xFF).toByte()
        buf[2] = ((BTRFS_INTERNAL_MAGIC shr 16) and 0xFF).toByte()
        buf[3] = ((BTRFS_INTERNAL_MAGIC shr 24) and 0xFF).toByte()

        assertFailsWith<IllegalArgumentException> {
            decodeLeaf(buf)
        }
    }

    @Test
    fun decodeInternalRejectsLeafNodeMagic() {
        val buf = ByteArray(BTRFS_NODE_SIZE)
        buf[0] = (BTRFS_LEAF_MAGIC and 0xFF).toByte()
        buf[1] = ((BTRFS_LEAF_MAGIC shr 8) and 0xFF).toByte()
        buf[2] = ((BTRFS_LEAF_MAGIC shr 16) and 0xFF).toByte()
        buf[3] = ((BTRFS_LEAF_MAGIC shr 24) and 0xFF).toByte()

        assertFailsWith<IllegalArgumentException> {
            decodeInternal(buf)
        }
    }
}
