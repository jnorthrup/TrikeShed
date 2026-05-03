package borg.trikeshed.tinybtrfs

import borg.trikeshed.userspace.btrfs.UserspaceMemoryBuffer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DiskAdapterPerimeterTest {
    @Test
    fun inMemoryAdapterCopiesAcrossReadWriteBoundary() {
        val adapter = InMemoryDiskAdapter()
        val id = adapter.allocateNode()
        val written = byteArrayOf(1, 2, 3)

        adapter.writeNode(id, written)
        written[0] = 9

        val firstRead = adapter.readNode(id)!!
        assertEquals(1, firstRead[0])

        firstRead[1] = 8
        val secondRead = adapter.readNode(id)!!
        assertEquals(2, secondRead[1])
    }

    @Test
    fun userspaceMemoryBufferCopiesAcrossReadWriteBoundary() = runTest {
        val buffer = UserspaceMemoryBuffer(chunkSize = 16)
        buffer.open()
        val id = buffer.allocateNode()
        val written = byteArrayOf(4, 5, 6)

        buffer.writeNode(id, written)
        written[0] = 9

        val firstRead = buffer.readNode(id)!!
        assertEquals(4, firstRead[0])

        firstRead[1] = 8
        val secondRead = buffer.readNode(id)!!
        assertEquals(5, secondRead[1])

        buffer.close()
    }

    @Test
    fun volatileReadRetriesUntilTwoSnapshotsMatch() {
        val id = "volatile".toNodeId()
        val adapter = ScriptedVolatileAdapter(
            listOf(
                byteArrayOf(1, 0),
                byteArrayOf(2, 0),
                byteArrayOf(3, 0),
                byteArrayOf(3, 0),
            ),
        )

        val image = adapter.readNodeImage(id, maxAttempts = 3)!!

        assertTrue(image.contentEquals(byteArrayOf(3, 0)))
    }

    @Test
    fun volatileReadFailsWhenSnapshotsNeverSettle() {
        val id = "volatile".toNodeId()
        val adapter = ScriptedVolatileAdapter(
            listOf(
                byteArrayOf(1),
                byteArrayOf(2),
                byteArrayOf(3),
                byteArrayOf(4),
            ),
        )

        assertFailsWith<IllegalStateException> {
            adapter.readNodeImage(id, maxAttempts = 2)
        }
    }

    @Test
    fun volatileImageHelperWorksWithoutDiskAdapter() {
        val images = listOf(
            byteArrayOf(9),
            byteArrayOf(8),
            byteArrayOf(8),
        )
        var index = 0

        val image = readVolatileImage(
            label = "external mmap page",
            maxAttempts = 2,
            read = {
                val current = images[index.coerceAtMost(images.lastIndex)]
                index++
                current
            },
            verify = {},
        )!!

        assertTrue(image.contentEquals(byteArrayOf(8)))
    }

    @Test
    fun mountVolatileFlagControlsPlainAdapterReads() {
        val id = "volatile".toNodeId()
        val adapter = ScriptedPlainAdapter(
            listOf(
                byteArrayOf(1),
                byteArrayOf(2),
                byteArrayOf(4),
                byteArrayOf(4),
            ),
        )
        val mount = BtrfsMount(adapter, volatileReads = true, maxReadAttempts = 2)

        val image = mount.readNodeImage(id)!!

        assertTrue(image.contentEquals(byteArrayOf(4)))
    }

    private class ScriptedVolatileAdapter(
        private val images: List<ByteArray>,
    ) : VolatileDiskAdapter {
        private var readIndex = 0

        override fun readNode(nodeId: NodeId): ByteArray? {
            val index = readIndex.coerceAtMost(images.lastIndex)
            readIndex++
            return images[index]
        }

        override fun writeNode(nodeId: NodeId, bytes: ByteArray) = Unit

        override fun allocateNode(): NodeId = "volatile".toNodeId()

        override fun freeNode(nodeId: NodeId) = Unit
    }

    private class ScriptedPlainAdapter(
        private val images: List<ByteArray>,
    ) : DiskAdapter {
        private var readIndex = 0

        override fun readNode(nodeId: NodeId): ByteArray? {
            val index = readIndex.coerceAtMost(images.lastIndex)
            readIndex++
            return images[index]
        }

        override fun writeNode(nodeId: NodeId, bytes: ByteArray) = Unit

        override fun allocateNode(): NodeId = "volatile".toNodeId()

        override fun freeNode(nodeId: NodeId) = Unit
    }
}
