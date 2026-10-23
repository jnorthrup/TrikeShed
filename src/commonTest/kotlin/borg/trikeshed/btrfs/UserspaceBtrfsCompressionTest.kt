package borg.trikeshed.btrfs

import borg.trikeshed.job.CasStore
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
import kotlin.test.*

class UserspaceBtrfsCompressionTest {
    @Test fun compressedManifestsRetainCanonicalIdentityAndReopenWithoutACompressionSetting() {
        for (codec in arrayOf(BtrfsMetadataCompression.ZSTD, BtrfsMetadataCompression.LZ4)) {
            val files = InMemoryFileOperations()
            val cas = CasStore.inMemory()
            val mount = UserspaceBtrfs("/compressed", files, cas, metadataCompression = codec)
            val plain = UserspaceBtrfs("/plain", files, cas)
            for (volume in arrayOf(mount, plain)) {
                assertTrue(volume.createSubvolume("live"))
                assertTrue(volume.writeFile("live", "directory/source.txt", "metadata body".encodeToByteArray()))
                assertTrue(volume.snapshot("live", "saved"))
            }
            assertEquals(plain.manifestId("live"), mount.manifestId("live"))
            assertEquals(plain.manifestId("saved"), mount.manifestId("saved"))
            assertTrue(BtrfsMetadataCompression.isEncoded(files.readAllBytes("/compressed/subvolumes/live.manifest")))
            assertFalse(BtrfsMetadataCompression.isEncoded(files.readAllBytes("/plain/subvolumes/live.manifest")))
            val reopened = UserspaceBtrfs("/compressed", files, cas)
            assertContentEquals("metadata body".encodeToByteArray(), reopened.fetchFile("saved", "directory/source.txt"))
            assertFalse(reopened.writeFile("saved", "immutable", byteArrayOf(1)))
            assertTrue(reopened.deleteSubvolume("live"))
            assertEquals(listOf("saved"), UserspaceBtrfs("/compressed", files, cas).listSubvolumes())
        }
    }

    @Test fun legacyManifestsCanBeUpdatedWithEitherCodecAndLocalSweepReadsMixedRecords() {
        val files = InMemoryFileOperations()
        val plain = UserspaceBtrfs("/mixed", files)
        assertTrue(plain.createSubvolume("live"))
        assertTrue(plain.writeFile("live", "file", byteArrayOf(4)))
        assertTrue(plain.snapshot("live", "legacy"))
        val zstd = UserspaceBtrfs("/mixed", files, metadataCompression = BtrfsMetadataCompression.ZSTD)
        assertTrue(zstd.writeFile("live", "file", byteArrayOf(5)))
        assertTrue(zstd.snapshot("live", "compressed"))
        val lz4 = UserspaceBtrfs("/mixed", files, metadataCompression = BtrfsMetadataCompression.LZ4)
        assertTrue(lz4.deleteSubvolume("live"))
        val reopened = UserspaceBtrfs("/mixed", files)
        assertContentEquals(byteArrayOf(4), reopened.fetchFile("legacy", "file"))
        assertContentEquals(byteArrayOf(5), reopened.fetchFile("compressed", "file"))
        assertEquals(listOf("compressed", "legacy"), reopened.listSubvolumes())
    }

    @Test fun corruptionAndFailedCompressedPublicationCannotExposeAnUncommittedIndex() {
        val backing = InMemoryFileOperations()
        var fail = false
        val files = object : FileOperations by backing {
            override fun writeAtomically(filename: String, bytes: ByteArray) {
                if (fail) error("publication rejected")
                backing.writeAtomically(filename, bytes)
            }
        }
        val mount = UserspaceBtrfs("/failure", files, metadataCompression = BtrfsMetadataCompression.ZSTD)
        mount.createSubvolume("live")
        mount.writeFile("live", "file", byteArrayOf(1))
        val path = "/failure/subvolumes/live.manifest"
        val before = backing.readAllBytes(path).copyOf()
        fail = true
        assertFails { mount.writeFile("live", "file", byteArrayOf(2)) }
        assertContentEquals(byteArrayOf(1), mount.fetchFile("live", "file"))
        assertContentEquals(before, backing.readAllBytes(path))
        fail = false
        assertContentEquals(byteArrayOf(1), UserspaceBtrfs("/failure", files).fetchFile("live", "file"))
        for (offset in arrayOf(0, 16, 32, before.lastIndex)) {
            val corrupt = before.copyOf()
            corrupt[offset] = (corrupt[offset].toInt() xor 1).toByte()
            backing.write(path, corrupt)
            assertFails("damaged byte $offset") { UserspaceBtrfs("/failure", files) }
        }
    }
}
