package borg.trikeshed.userspace.btrfs

import borg.trikeshed.context.ElementState
import borg.trikeshed.tinybtrfs.BPlusTree
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserspaceMemoryBufferTest {

    @Test
    fun lifecycleTransitions() = runTest {
        val buf = UserspaceMemoryBuffer(chunkSize = 256)
        assertEquals(ElementState.CREATED, buf.state)

        buf.open()
        assertEquals(ElementState.OPEN, buf.state)

        buf.close()
        assertEquals(ElementState.CLOSED, buf.state)
    }

    @Test
    fun allocateWriteReadFree() = runTest {
        val buf = UserspaceMemoryBuffer(chunkSize = 256)
        buf.open()

        val id1 = buf.allocateNode()
        val id2 = buf.allocateNode()
        assertTrue(id1 != id2, "node IDs must be unique")

        // Write to id1, id2 stays empty
        buf.writeNode(id1, byteArrayOf(1, 2, 3))
        assertNotNull(buf.readNode(id1))
        assertEquals(3, buf.readNode(id1)!!.size)
        assertEquals(0, buf.readNode(id2)!!.size) // allocated with empty

        // Free id1 and reuse
        buf.freeNode(id1)
        assertNull(buf.readNode(id1))
        val id3 = buf.allocateNode()
        assertEquals(id1, id3) // reused from free-list

        buf.close()
    }

    @Test
    fun writeExceedingChunkSizeThrows() = runTest {
        val buf = UserspaceMemoryBuffer(chunkSize = 4)
        buf.open()
        val id = buf.allocateNode()

        try {
            buf.writeNode(id, ByteArray(5))
            // Should not reach here
            assertTrue(false, "Expected exception for oversized write")
        } catch (e: IllegalStateException) {
            // Expected
        }

        buf.close()
    }

    @Test
    fun readBeforeOpenThrows() = runTest {
        val buf = UserspaceMemoryBuffer()
        try {
            buf.readNode("any")
            assertTrue(false, "Expected exception")
        } catch (e: IllegalStateException) {
            // Expected — buffer not open
        }
    }

    @Test
    fun closeClearsAllData() = runTest {
        val buf = UserspaceMemoryBuffer(chunkSize = 128)
        buf.open()
        val id = buf.allocateNode()
        buf.writeNode(id, byteArrayOf(42))
        assertEquals(1, buf.chunkCount())

        buf.close()
        assertEquals(0, buf.chunkCount())
        assertEquals(0, buf.freeCount())
        assertEquals(ElementState.CLOSED, buf.state)
    }

    @Test
    fun bplusTreeUsesBufferAsDiskAdapter() = runTest {
        val buf = UserspaceMemoryBuffer(chunkSize = 4096)
        buf.open()

        // Simulate B+Tree using the buffer as backing store.
        // The tree itself is in-memory; the buffer stores serialized node blobs.
        val tree = BPlusTree<Long, String>(order = 4)

        // Insert some entries
        for (i in 1L..20L) {
            tree.put(i, "value-$i")
        }

        // Serialize root node and store in buffer
        val rootId = buf.allocateNode()
        val serialized = serializeTree(tree)
        buf.writeNode(rootId, serialized)

        // Read back
        val restored = buf.readNode(rootId)
        assertNotNull(restored)
        assertEquals(serialized.size, restored.size)

        // Free unused nodes
        val unusedId = buf.allocateNode()
        buf.freeNode(unusedId)
        assertEquals(1, buf.chunkCount()) // only rootId is active
    }

    // Minimal serialization for the BPlusTree<Long, String> smoke test.
    private fun serializeTree(tree: BPlusTree<Long, String>): ByteArray {
        val sb = StringBuilder()
        sb.append("${tree.size()}\n")
        // Walk in-order and serialize each entry
        for (i in 1L..tree.size().toLong()) {
            val v = tree.get(i)
            if (v != null) sb.append("$i=$v\n")
        }
        return sb.toString().encodeToByteArray()
    }
}
