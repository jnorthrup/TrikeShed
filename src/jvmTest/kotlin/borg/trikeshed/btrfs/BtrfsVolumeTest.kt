package borg.trikeshed.btrfs

import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Legacy API coverage uses real canonical snapshots; native interoperability has a separate oracle. */
class BtrfsVolumeTest {
    private val imageBytes = BtrfsFilesystemImage.MINIMUM_BYTES

    private suspend fun image(file: File, payload: ByteArray = ByteArray(4096) { it.toByte() }) {
        RandomAccessFile(file, "rw").use { it.setLength(imageBytes) }
        val volume = BtrfsUringFileVolume(file.path, capacity = imageBytes / 4096)
        try { BtrfsFilesystemImage.write(volume, listOf(BtrfsImageEntry("payload", payload))) }
        finally { volume.drain() }
    }

    @Test
    fun canonical_superblock_is_read_at_64k_and_capacity_is_in_blocks() = runBlocking {
        val file = File.createTempFile("btrfs-volume-", ".img")
        try {
            image(file)
            val volume = BtrfsVolume(file.path)
            assertEquals(4096, volume.blockSize)
            assertEquals(imageBytes / 4096, volume.capacity)
        } finally { file.delete() }
    }

    @Test
    fun reads_native_chunk_mapping_and_rejects_gaps_and_writes() = runBlocking<Unit> {
        val file = File.createTempFile("btrfs-volume-", ".img")
        try {
            val expected = ByteArray(4096) { (it * 17).toByte() }
            image(file, expected)
            val volume = BtrfsVolume(file.path)
            // Canonical writer's DATA block group is located at logical 80MiB.
            assertContentEquals(expected, volume.read(80L * 1024 * 1024 / 4096, 1))
            assertFailsWith<IllegalStateException> { volume.read(0, 1) }
            assertFailsWith<UnsupportedOperationException> { volume.write(0, ByteArray(4096)) }
        } finally { file.delete() }
    }

    @Test
    fun old_local_magic_fixture_is_not_accepted_as_native_btrfs() {
        val file = File.createTempFile("btrfs-volume-invalid-", ".img")
        try {
            val bytes = ByteArray(65536 + 4096)
            "_BHRfS_M".encodeToByteArray().copyInto(bytes, 16)
            file.writeBytes(bytes)
            assertFailsWith<IllegalArgumentException> { BtrfsVolume(file.path) }
        } finally { file.delete() }
    }
}
