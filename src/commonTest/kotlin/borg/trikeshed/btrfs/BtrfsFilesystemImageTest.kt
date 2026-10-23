package borg.trikeshed.btrfs

import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.Volume
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BtrfsFilesystemImageTest {
    private class Image(override val capacity: Long = BtrfsFilesystemImage.MINIMUM_BYTES / 4096) : Volume {
        override val blockSize = 4096
        val blocks = mutableMapOf<Long, ByteArray>()
        var writes = 0
        override suspend fun read(lba: Long, count: Int): ByteBuffer {
            require(lba >= 0 && count >= 0 && lba + count <= capacity)
            return ByteBuffer.wrap(ByteArray(count * blockSize).also { output ->
                repeat(count) { i -> blocks[lba + i]?.copyInto(output, i * blockSize) }
            })
        }
        override suspend fun write(lba: Long, data: ByteBuffer) {
            val bytes = ByteArray(data.remaining()).also(data::get)
            require(bytes.size % blockSize == 0 && lba >= 0 && lba + bytes.size / blockSize <= capacity)
            repeat(bytes.size / blockSize) { i -> blocks[lba + i] = bytes.copyOfRange(i * blockSize, (i + 1) * blockSize) }
            writes++
        }
        override suspend fun sync() = Unit
        fun corrupt(address: Long) {
            val bytes = blocks.getValue(address / blockSize)
            val offset = (address % blockSize).toInt()
            bytes[offset] = (bytes[offset].toInt() xor 64).toByte()
        }
        fun patchLeaf(address: Long, patch: (ByteArray) -> Unit) {
            val node = ByteArray(16384)
            repeat(4) { blocks.getValue(address / blockSize + it).copyInto(node, it * blockSize) }
            patch(node)
            BtrfsDisk.checksum(node)
            repeat(4) { blocks[address / blockSize + it] = node.copyOfRange(it * blockSize, (it + 1) * blockSize) }
        }
    }

    @Test
    fun space_accounts_for_reserved_groups_sector_padding_and_payload_budget() {
        val snapshot = BtrfsImageSnapshot(1u, "", listOf(BtrfsImageEntry("dir"), BtrfsImageEntry("file", ByteArray(4097)), BtrfsImageEntry("empty", byteArrayOf())))
        val small = BtrfsFilesystemImage.space(BtrfsFilesystemImage.MINIMUM_BYTES, snapshot)
        assertEquals(4096, small.blockSize)
        assertEquals(32768L, small.totalBlocks)
        assertEquals(20482L, small.allocatedBlocks)
        assertEquals(12286L, small.freeDataBlocks)
        assertEquals(small.freeDataBlocks, small.availableDataBlocks)
        val large = BtrfsFilesystemImage.space(BtrfsFilesystemImage.MAXIMUM_BYTES, snapshot)
        assertEquals((BtrfsFilesystemImage.MAX_SNAPSHOT_DATA_BYTES - 4097) / 4096, large.availableDataBlocks)
        assertTrue(large.availableDataBlocks < large.freeDataBlocks)
    }

    @Test
    fun checksum_index_bound_covers_payload_sectors_and_every_possible_file_tail() {
        // Every file needs a 160-byte inode in the fixed 16MiB metadata group, even before
        // keys, names, extents, and references consume space. This deliberately overestimates
        // the writer's file count while testing the near-quota/tiny-file case without huge arrays.
        val inodeUpperBound = 16L * 1024 * 1024 / 160
        val sectorUpperBound = BtrfsFilesystemImage.MAX_SNAPSHOT_DATA_BYTES / 4096 + inodeUpperBound
        assertTrue(sectorUpperBound > BtrfsFilesystemImage.MAX_SNAPSHOT_DATA_BYTES / 4096)
        assertTrue(BtrfsFilesystemImage.MAX_CHECKSUM_ENTRIES >= sectorUpperBound)
    }

    @Test
    fun snapshot_preserves_fsid_modes_times_empty_files_and_tree_splits() = runTest {
        val input = (0 until 200).map { index ->
            BtrfsImageEntry("dir/file-$index", ByteArray(index) { it.toByte() }, 0x81e8u, 123456789L, 123456789,
                uid = 1001u, gid = 1002u, accessedSeconds = 111, accessedNanoseconds = 222,
                changedSeconds = 333, changedNanoseconds = 444, createdSeconds = 555, createdNanoseconds = 666)
        } + BtrfsImageEntry("empty")
        val first = Image()
        val fsid = ByteArray(16) { (it + 1).toByte() }
        val root = BtrfsImageEntry("", mode = 0x41c0u, modifiedSeconds = 999, uid = 2001u, gid = 2002u, accessedSeconds = 111, changedSeconds = 222, createdSeconds = 333)
        BtrfsFilesystemImage.write(first, input, "snapshot", 7u, fsid, root)
        val reopened = BtrfsFilesystemImage.read(first)
        assertEquals(7uL, reopened.generation)
        assertContentEquals(fsid, reopened.fsid)
        assertEquals(root, reopened.rootMetadata)
        assertEquals(202, reopened.entries.size)
        for (entry in input) {
            val actual = reopened.entries.single { it.path == entry.path }
            assertEquals(entry.mode, actual.mode)
            assertEquals(entry.modifiedSeconds, actual.modifiedSeconds)
            assertEquals(entry.modifiedNanoseconds, actual.modifiedNanoseconds)
            assertEquals(entry.copy(data = null), actual.copy(data = null))
            assertContentEquals(entry.data, actual.data)
        }
        val second = Image()
        BtrfsFilesystemImage.write(second, reopened.entries, reopened.label, 8u, reopened.fsid, reopened.rootMetadata)
        val rewritten = BtrfsFilesystemImage.read(second)
        assertContentEquals(fsid, rewritten.fsid)
        assertEquals(root, rewritten.rootMetadata)
        assertContentEquals("_BHRfS_M".encodeToByteArray(), first.blocks.getValue(16).copyOfRange(64, 72))
    }

    @Test
    fun invalid_input_and_existing_image_are_rejected_before_mutation() = runTest {
        val image = Image()
        assertFailsWith<IllegalArgumentException> { BtrfsFilesystemImage.write(image, listOf(BtrfsImageEntry("../escape"))) }
        assertFailsWith<IllegalArgumentException> { BtrfsFilesystemImage.write(image, listOf(BtrfsImageEntry("dir", byteArrayOf(1)), BtrfsImageEntry("dir/file"))) }
        assertFailsWith<IllegalArgumentException> { BtrfsFilesystemImage.write(image, emptyList(), rootMetadata = BtrfsImageEntry("not-root")) }
        assertEquals(0, image.writes)
        val oversized = Image(BtrfsFilesystemImage.MAXIMUM_BYTES / 4096 + 1)
        assertFailsWith<IllegalArgumentException> { BtrfsFilesystemImage.write(oversized, emptyList()) }
        assertEquals(0, oversized.writes)
        BtrfsFilesystemImage.write(image, emptyList())
        val writes = image.writes
        assertFailsWith<IllegalArgumentException> { BtrfsFilesystemImage.write(image, emptyList()) }
        assertEquals(writes, image.writes)
    }

    @Test
    fun unsupported_native_metadata_is_rejected_instead_of_dropped() = runTest {
        suspend fun fixture() = Image().also { BtrfsFilesystemImage.write(it, listOf(BtrfsImageEntry("file", byteArrayOf(42)))) }
        val hardlinks = fixture()
        hardlinks.patchLeaf(5L * 1024 * 1024) { node ->
            val index = (0 until BtrfsDisk.u32(node, 96).toInt()).single { BtrfsDisk.key(node, BtrfsDisk.HEADER + it * 25) == BtrfsKey(257u, 1u, 0u) }
            val data = BtrfsDisk.HEADER + BtrfsDisk.u32(node, BtrfsDisk.HEADER + index * 25 + 17).toInt()
            BtrfsDisk.put(node, data + 40, 2u, 4)
        }
        assertTrue(assertFailsWith<IllegalArgumentException> { BtrfsFilesystemImage.read(hardlinks) }.message.orEmpty().contains("hard links"))
        val xattrs = fixture()
        xattrs.patchLeaf(5L * 1024 * 1024) { node ->
            val pos = BtrfsDisk.HEADER + 25 // root inode reference follows its inode item
            node[pos + 8] = 24
        }
        assertTrue(assertFailsWith<IllegalArgumentException> { BtrfsFilesystemImage.read(xattrs) }.message.orEmpty().contains("extended attributes"))
        val subvolumes = fixture()
        val root = BtrfsSuperblock.parse(subvolumes.blocks.getValue(16)).root.toLong()
        subvolumes.patchLeaf(root) { node ->
            val index = (0 until BtrfsDisk.u32(node, 96).toInt()).single { BtrfsDisk.key(node, BtrfsDisk.HEADER + it * 25) == BtrfsKey(9u, 132u, 0u) }
            BtrfsDisk.put(node, BtrfsDisk.HEADER + index * 25, 256u)
        }
        assertTrue(assertFailsWith<IllegalArgumentException> { BtrfsFilesystemImage.read(subvolumes) }.message.orEmpty().contains("subvolumes"))
    }

    @Test
    fun checksums_reject_metadata_and_data_corruption() = runTest {
        val metadata = Image()
        BtrfsFilesystemImage.write(metadata, listOf(BtrfsImageEntry("payload", byteArrayOf(1, 2, 3))))
        val root = BtrfsSuperblock.parse(metadata.blocks.getValue(16)).root.toLong()
        metadata.corrupt(root + 100)
        assertFailsWith<IllegalArgumentException> { BtrfsFilesystemImage.read(metadata) }
        val data = Image()
        BtrfsFilesystemImage.write(data, listOf(BtrfsImageEntry("payload", byteArrayOf(1, 2, 3))))
        // Writer's DATA chunk begins at 80MiB; this is payload damage, not a tree fixture.
        data.corrupt(80L * 1024 * 1024)
        assertFailsWith<IllegalArgumentException> { BtrfsFilesystemImage.read(data) }
    }

    @Test
    fun unknown_incompatibility_flags_are_rejected_after_valid_checksum() = runTest {
        val image = Image()
        BtrfsFilesystemImage.write(image, emptyList())
        val sb = image.blocks.getValue(16)
        BtrfsDisk.put(sb, 188, BtrfsDisk.u64(sb, 188) or (1uL shl 63))
        BtrfsDisk.checksum(sb)
        val failure = assertFailsWith<IllegalArgumentException> { BtrfsFilesystemImage.read(image) }
        assertTrue(failure.message.orEmpty().contains("incompatibility"))
    }

    @Test
    fun pending_native_log_and_oversized_inode_are_rejected() = runTest {
        val logged = Image()
        BtrfsFilesystemImage.write(logged, emptyList())
        val sb = logged.blocks.getValue(16)
        BtrfsDisk.put(sb, 96, 5uL * 1024uL * 1024uL)
        BtrfsDisk.put(sb, 104, 1u)
        BtrfsDisk.checksum(sb)
        assertTrue(assertFailsWith<IllegalArgumentException> { BtrfsFilesystemImage.read(logged) }.message.orEmpty().contains("log replay"))

        val oversized = Image()
        BtrfsFilesystemImage.write(oversized, listOf(BtrfsImageEntry("file", byteArrayOf(1))))
        oversized.patchLeaf(5L * 1024 * 1024) { node ->
            val index = (0 until BtrfsDisk.u32(node, 96).toInt()).single { BtrfsDisk.key(node, BtrfsDisk.HEADER + it * 25) == BtrfsKey(257u, 1u, 0u) }
            val data = BtrfsDisk.HEADER + BtrfsDisk.u32(node, BtrfsDisk.HEADER + index * 25 + 17).toInt()
            BtrfsDisk.put(node, data + 16, BtrfsFilesystemImage.MAX_SNAPSHOT_DATA_BYTES.toULong() + 1u)
        }
        assertTrue(assertFailsWith<IllegalArgumentException> { BtrfsFilesystemImage.read(oversized) }.message.orEmpty().contains("file-data budget"))
    }
}
