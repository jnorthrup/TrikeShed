package borg.trikeshed.userspace.btrfs

import borg.trikeshed.context.ElementState
import borg.trikeshed.tinybtrfs.DiskAdapter
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TDD RED: BtrfsBlockDevice tests.
 *
 * Tests a block-device adapter that wraps a UserspaceMemoryBuffer and
 * adds btrfs superblock, checksum validation, and ElementState management.
 */
class BtrfsBlockDeviceTest_red {

    @Test
    fun superblockMagicRoundTrip() = runTest {
        val mem = UserspaceMemoryBuffer(chunkSize = 4096)
        val dev = BtrfsBlockDevice(mem)
        dev.open()

        val sbMagic = dev.readSuperblockMagic()
        assertEquals(0x42445254465D4B_uL, sbMagic)

        dev.close()
    }

    @Test
    fun openAllocatesSuperblockNode() = runTest {
        val mem = UserspaceMemoryBuffer(chunkSize = 4096)
        val dev = BtrfsBlockDevice(mem)
        assertEquals(ElementState.CREATED, dev.state)
        dev.open()
        assertEquals(ElementState.OPEN, dev.state)
        dev.close()
    }

    @Test
    fun rootTreeNodeIdIsStoredInSuperblock() = runTest {
        val mem = UserspaceMemoryBuffer(chunkSize = 4096)
        val dev = BtrfsBlockDevice(mem)
        dev.open()

        val rootNodeId = dev.rootTreeNodeId()
        assertNotNull(rootNodeId)

        dev.close()
    }

    @Test
    fun checksumValidOnCleanRead() = runTest {
        val mem = UserspaceMemoryBuffer(chunkSize = 4096)
        val dev = BtrfsBlockDevice(mem)
        dev.open()

        val nodeId = dev.allocateNode()
        // Write a valid leaf node
        val key = BtrfsKey(1uL, 1u, 0uL)
        val item = BtrfsItem(key, 0u, 4u, byteArrayOf(1, 2, 3, 4))
        val leaf = BtrfsLeaf(listOf(item))
        val buf = ByteArray(4096)
        encodeLeaf(leaf, buf)
        dev.writeNode(nodeId, buf)

        val readBack = dev.readNode(nodeId)
        assertNotNull(readBack)
        assertEquals(4096, readBack.size)

        dev.close()
    }

    @Test
    fun corruptedMagicThrowsOnInvalidNode() = runTest {
        val mem = UserspaceMemoryBuffer(chunkSize = 4096)
        val dev = BtrfsBlockDevice(mem)
        dev.open()

        val nodeId = dev.allocateNode()
        // Write buffer with invalid magic (zeros)
        val badBuf = ByteArray(4096) { 0 }
        dev.writeNode(nodeId, badBuf)

        assertFailsWith<IllegalStateException> {
            dev.readNode(nodeId)
        }

        dev.close()
    }

    @Test
    fun corruptedHeaderThrowsOnTruncatedHeader() = runTest {
        val mem = UserspaceMemoryBuffer(chunkSize = 4096)
        val dev = BtrfsBlockDevice(mem)
        dev.open()

        val nodeId = dev.allocateNode()
        // Write buffer too short for header
        dev.writeNode(nodeId, ByteArray(4) { 0 })

        assertFailsWith<IllegalStateException> {
            dev.readNode(nodeId)
        }

        dev.close()
    }

    @Test
    fun elementStateTransitionsAreSequential() = runTest {
        val mem = UserspaceMemoryBuffer(chunkSize = 4096)
        val dev = BtrfsBlockDevice(mem)

        assertEquals(ElementState.CREATED, dev.state)
        dev.open()
        assertEquals(ElementState.OPEN, dev.state)
        dev.freeNode(dev.allocateNode())
        assertEquals(ElementState.OPEN, dev.state)
        dev.close()
        assertEquals(ElementState.CLOSED, dev.state)
    }

    @Test
    fun littleEndianByteOrderForSuperblock() = runTest {
        val mem = UserspaceMemoryBuffer(chunkSize = 4096)
        val dev = BtrfsBlockDevice(mem)
        dev.open()

        val sbMagic = dev.readSuperblockMagic()
        // LSB should be 0x4B, MSB should be 0x42
        assertEquals(0x4Bu, (sbMagic and 0xFFu).toUByte())
        assertEquals(0x42u, (sbMagic shr 56).toUByte())

        dev.close()
    }

    @Test
    fun closeClearsBlockDevice() = runTest {
        val mem = UserspaceMemoryBuffer(chunkSize = 4096)
        val dev = BtrfsBlockDevice(mem)
        dev.open()

        val nodeId = dev.allocateNode()
        val buf = ByteArray(16) { it.toByte() }
        dev.writeNode(nodeId, buf)
        assertNotNull(dev.readNode(nodeId))

        dev.close()

        assertEquals(ElementState.CLOSED, dev.state)
        assertFailsWith<IllegalStateException> {
            dev.readNode(nodeId)
        }
    }

    @Test
    fun reopenAfterCloseReinitializes() = runTest {
        val mem = UserspaceMemoryBuffer(chunkSize = 4096)
        val dev = BtrfsBlockDevice(mem)
        dev.open()

        val nodeId = dev.allocateNode()
        dev.writeNode(nodeId, byteArrayOf(1, 2))
        dev.close()

        dev.open()
        val newNodeId = dev.allocateNode()
        assertTrue(newNodeId != nodeId, "new nodeId should differ after re-open")
        dev.close()
    }

    @Test
    fun getNodeDelegatesToAdapter() = runTest {
        val mem = UserspaceMemoryBuffer(chunkSize = 4096)
        val dev = BtrfsBlockDevice(mem)
        dev.open()

        val nodeId = dev.allocateNode()
        dev.writeNode(nodeId, byteArrayOf(1, 2, 3))

        val result = dev.readNode(nodeId)
        assertNotNull(result)
        assertEquals(3, result.size)

        dev.close()
    }

    @Test
    fun putNodeDelegatesToAdapter() = runTest {
        val mem = UserspaceMemoryBuffer(chunkSize = 4096)
        val dev = BtrfsBlockDevice(mem)
        dev.open()

        val nodeId = dev.allocateNode()
        dev.writeNode(nodeId, byteArrayOf(0xFE.toByte(), 0xED.toByte()))
        val result = dev.readNode(nodeId)
        assertNotNull(result)
        assertEquals(0xFE.toByte(), result[0])

        dev.close()
    }

    @Test
    fun allocateNodeCreatesUniqueNodes() = runTest {
        val mem = UserspaceMemoryBuffer(chunkSize = 4096)
        val dev = BtrfsBlockDevice(mem)
        dev.open()

        val id1 = dev.allocateNode()
        val id2 = dev.allocateNode()
        val id3 = dev.allocateNode()

        assertTrue(id1 != id2 && id2 != id3 && id1 != id3, "all nodeIds must be unique")

        dev.close()
    }

    @Test
    fun freeNodeRemovesNodeFromStorage() = runTest {
        val mem = UserspaceMemoryBuffer(chunkSize = 4096)
        val dev = BtrfsBlockDevice(mem)
        dev.open()

        val nodeId = dev.allocateNode()
        dev.writeNode(nodeId, byteArrayOf(0x99.toByte()))
        assertNotNull(dev.readNode(nodeId))

        dev.freeNode(nodeId)
        assertNull(dev.readNode(nodeId))

        dev.close()
    }

    @Test
    fun readBeforeOpenThrows() = runTest {
        val mem = UserspaceMemoryBuffer(chunkSize = 4096)
        val dev = BtrfsBlockDevice(mem)
        assertFailsWith<IllegalStateException> {
            dev.readNode("n-1")
        }
    }

    @Test
    fun writeBeforeOpenThrows() = runTest {
        val mem = UserspaceMemoryBuffer(chunkSize = 4096)
        val dev = BtrfsBlockDevice(mem)
        assertFailsWith<IllegalStateException> {
            dev.writeNode("n-1", byteArrayOf(1))
        }
    }

    @Test
    fun diskAdapterContract_allocationAndFree() = runTest {
        val mem = UserspaceMemoryBuffer(chunkSize = 4096)
        val dev = BtrfsBlockDevice(mem)
        dev.open()

        val id = dev.allocateNode()
        dev.writeNode(id, byteArrayOf(0xAA.toByte()))
        assertNotNull(dev.readNode(id))
        dev.freeNode(id)
        assertNull(dev.readNode(id))

        dev.freeNode(id)  // idempotent free should not throw
        dev.close()
    }
}
