package borg.trikeshed.userspace.btrfs

import borg.trikeshed.context.ElementState
import borg.trikeshed.tinybtrfs.BPlusTree
import borg.trikeshed.userspace.btrfs.BtrfsTreeRebuilder
import borg.trikeshed.userspace.btrfs.UserspaceMemoryBuffer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Red tests for BtrfsTreeRebuilder — basic lifecycle and failure paths.
 * These tests define the expected behavior before implementation is complete.
 */
class BtrfsTreeRebuilderTest_red {

    @Test
    fun initialStateIsCreated() = runTest {
        val buf = UserspaceMemoryBuffer(chunkSize = 4096)
        buf.open()
        val rebuilder = BtrfsTreeRebuilder(buf)
        assertEquals(ElementState.CREATED, rebuilder.state)
        buf.close()
    }

    @Test
    fun rebuildStartsInCreatedState() = runTest {
        val buf = UserspaceMemoryBuffer(chunkSize = 4096)
        buf.open()
        val rebuilder = BtrfsTreeRebuilder(buf)
        rebuilder.beginRebuild()
        assertEquals(ElementState.OPEN, rebuilder.state)
        buf.close()
    }

    @Test
    fun rebuildWithEmptyBufferCompletes() = runTest {
        val buf = UserspaceMemoryBuffer(chunkSize = 4096)
        buf.open()
        val rebuilder = BtrfsTreeRebuilder(buf)
        rebuilder.beginRebuild()
        val result = rebuilder.completeRebuild()
        assertNotNull(result)
        assertEquals(0, result.nodeCount)
        assertEquals(ElementState.CLOSED, rebuilder.state)
        buf.close()
    }

    @Test
    fun rebuildWithSingleNode() = runTest {
        val buf = UserspaceMemoryBuffer(chunkSize = 4096)
        buf.open()
        val rebuilder = BtrfsTreeRebuilder(buf)

        // Insert a node directly into buffer
        val nodeId = buf.allocateNode()
        buf.writeNode(nodeId, byteArrayOf(1, 2, 3, 4))

        rebuilder.beginRebuild()
        val result = rebuilder.completeRebuild()
        assertEquals(1, result.nodeCount)
        assertEquals(ElementState.CLOSED, rebuilder.state)
        buf.close()
    }

    @Test
    fun rebuildFailsWithCorruptMagicBytes() = runTest {
        val buf = UserspaceMemoryBuffer(chunkSize = 4096)
        buf.open()
        val rebuilder = BtrfsTreeRebuilder(buf)

        val nodeId = buf.allocateNode()
        // Write invalid header (bad magic bytes for btrfs node)
        buf.writeNode(nodeId, byteArrayOf(0x00, 0x00, 0x00, 0x00))

        rebuilder.beginRebuild()
        try {
            rebuilder.completeRebuild()
            assertTrue(false, "Expected corruption detection")
        } catch (e: IllegalStateException) {
            // Expected — invalid magic bytes
        }
        buf.close()
    }

    @Test
    fun rebuildDetectsInvalidGeneration() = runTest {
        val buf = UserspaceMemoryBuffer(chunkSize = 4096)
        buf.open()
        val rebuilder = BtrfsTreeRebuilder(buf)

        val nodeId = buf.allocateNode()
        // Write node with generation = -1 (invalid)
        buf.writeNode(nodeId, byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))

        rebuilder.beginRebuild()
        try {
            rebuilder.completeRebuild()
            assertTrue(false, "Expected generation validation failure")
        } catch (e: IllegalArgumentException) {
            // Expected — generation overflow
        }
        buf.close()
    }

    @Test
    fun rebuildWithMultipleNodesCountsCorrectly() = runTest {
        val buf = UserspaceMemoryBuffer(chunkSize = 4096)
        buf.open()
        val rebuilder = BtrfsTreeRebuilder(buf)

        // Create several nodes
        repeat(5) { i ->
            val id = buf.allocateNode()
            buf.writeNode(id, byteArrayOf(i.toByte()))
        }

        rebuilder.beginRebuild()
        val result = rebuilder.completeRebuild()
        assertEquals(5, result.nodeCount)
        assertEquals(ElementState.CLOSED, rebuilder.state)
        buf.close()
    }

    @Test
    fun rebuildAfterCloseIsNotAllowed() = runTest {
        val buf = UserspaceMemoryBuffer(chunkSize = 4096)
        buf.open()
        val rebuilder = BtrfsTreeRebuilder(buf)
        rebuilder.beginRebuild()
        rebuilder.completeRebuild()

        try {
            rebuilder.beginRebuild()
            assertTrue(false, "Should not allow rebuild after close")
        } catch (e: IllegalStateException) {
            // Expected — already closed
        }
        buf.close()
    }
}
