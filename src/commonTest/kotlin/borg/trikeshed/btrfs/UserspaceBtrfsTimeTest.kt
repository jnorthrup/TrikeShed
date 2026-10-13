package borg.trikeshed.btrfs

import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.CasStore
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
import kotlin.time.Instant
import kotlin.test.*

class UserspaceBtrfsTimeTest {
    private val first = Instant.fromEpochSeconds(1_700_000_000, 123_456_789)
    private val second = Instant.fromEpochSeconds(1_700_000_010, 987_654_321)
    private val beforeEpoch = Instant.fromEpochSeconds(-2, 123)

    @Test
    fun timesPreserveContentIdentityAndSnapshotValuesAcrossRemount() {
        val files = InMemoryFileOperations()
        val cas = CasStore.inMemory()
        var now = first
        val mount = UserspaceBtrfs("/times", files, cas) { now }
        assertTrue(mount.createSubvolume("live"))
        assertTrue(mount.writeFile("live", "dir/file", byteArrayOf(42)))
        val original = assertNotNull(mount.times("live", "dir/file"))
        val cid = mount.manifestId("live")
        assertEquals(BtrfsFileTimes.at(first), original)
        assertTrue(mount.snapshot("live", "snapshot"))
        now = second
        assertTrue(mount.setTimes("live", "dir/file", lastModifiedTime = beforeEpoch, lastAccessTime = second))
        assertEquals(cid, mount.manifestId("live"), "time mutation is not a content mutation")
        assertEquals(original, mount.times("snapshot", "dir/file"))
        assertFalse(mount.setTimes("snapshot", "dir/file", creationTime = second))
        val expected = original.copy(lastModifiedTime = beforeEpoch, lastAccessTime = second, changeTime = second)
        assertEquals(expected, UserspaceBtrfs("/times", files, cas).times("live", "dir/file"))
        assertEquals(original, UserspaceBtrfs("/times", files, cas).times("snapshot", "dir/file"))
        assertContentEquals(byteArrayOf(42), mount.fetchFile("live", "dir/file"))
        assertEquals(expected, mount.times("live", "dir/file"), "reads are no-atime")
        assertTrue(mount.receiveManifest("received", assertNotNull(mount.manifest("live"))))
        assertEquals(BtrfsFileTimes.ZERO, mount.times("received", "dir/file"), "content transfer does not invent local times")
    }

    @Test
    fun contentAndDirectoryMutationsUpdateOnlyTheirInodesAndParents() {
        val files = InMemoryFileOperations()
        var now = first
        val mount = UserspaceBtrfs("/inodes", files, clock = { now })
        mount.createSubvolume("live")
        mount.writeFile("live", "dir/file", byteArrayOf(1))
        now = second
        mount.writeFile("live", "dir/file", byteArrayOf(2))
        assertEquals(BtrfsFileTimes.at(first).written(second), mount.times("live", "dir/file"))
        assertEquals(first, mount.times("live", "dir")?.lastModifiedTime)
        assertEquals(first, mount.times("live", "")?.lastModifiedTime)
        mount.writeFile("live", "dir/new", byteArrayOf(3))
        assertEquals(second, mount.times("live", "dir")?.lastModifiedTime)
        assertEquals(first, mount.times("live", "")?.lastModifiedTime)
        mount.createDirectory("live", "other/nested")
        assertEquals(second, mount.times("live", "")?.lastModifiedTime)
        assertEquals(BtrfsFileTimes.at(second), mount.times("live", "other/nested"))
        now = Instant.fromEpochSeconds(1_700_000_020)
        assertTrue(mount.deleteFile("live", "dir/file"))
        assertNull(mount.times("live", "dir/file"))
        assertEquals(now, mount.times("live", "dir")?.lastModifiedTime)
        assertTrue(mount.writeFile("live", "dir/file", byteArrayOf(4)))
        assertEquals(BtrfsFileTimes.at(now), mount.times("live", "dir/file"), "recreation cannot reuse deleted metadata")
        assertTrue(mount.setTimes("live", "", creationTime = beforeEpoch))
        assertEquals(beforeEpoch, UserspaceBtrfs("/inodes", files).times("live", "")?.creationTime)
        assertFalse(mount.setTimes("live", "missing", lastAccessTime = first))
        assertNull(mount.times("live", "missing"))
    }

    private class PublicationFiles(private val memory: FileOperations) : FileOperations by memory {
        var reject = false
        var after = false
        override fun writeAtomically(filename: String, bytes: ByteArray) {
            if (filename.endsWith(".manifest")) check(!reject) { "publication refused" }
            memory.writeAtomically(filename, bytes)
            if (filename.endsWith(".manifest")) check(!after) { "post-publication failure" }
        }
    }

    @Test
    fun failedTimePublicationKeepsBothMetadataAndContentAtThePreviousRoot() {
        val files = InMemoryFileOperations()
        val publication = PublicationFiles(files)
        val mount = UserspaceBtrfs("/atomic-times", publication, clock = { first })
        mount.createSubvolume("live")
        mount.writeFile("live", "file", byteArrayOf(7))
        val original = files.readAllBytes("/atomic-times/subvolumes/live.manifest")
        publication.reject = true
        assertFails { mount.setTimes("live", "file", lastModifiedTime = second) }
        assertFails { mount.setTimes("live", "", creationTime = second) }
        assertEquals(BtrfsFileTimes.at(first), mount.times("live", "file"))
        assertContentEquals(original, files.readAllBytes("/atomic-times/subvolumes/live.manifest"))
        assertContentEquals(byteArrayOf(7), mount.fetchFile("live", "file"))
        assertEquals(BtrfsFileTimes.at(first), UserspaceBtrfs("/atomic-times", files).times("live", "file"))
        publication.reject = false
        publication.after = true
        assertFails { mount.setTimes("live", "file", lastModifiedTime = second) }
        assertFails { mount.times("live", "file") }
        val reopened = UserspaceBtrfs("/atomic-times", files)
        assertEquals(second, reopened.times("live", "file")?.lastModifiedTime)
        assertContentEquals(byteArrayOf(7), reopened.fetchFile("live", "file"))
    }

    @Test
    fun versionOneLocalRootsRemainReadableAndUpgradeOnlyAtMutation() {
        val files = InMemoryFileOperations()
        val cas = CasStore.inMemory()
        val seed = UserspaceBtrfs("/v1-times", files, cas)
        seed.createSubvolume("live")
        seed.writeFile("live", "file", byteArrayOf(8))
        val cid = assertNotNull(seed.manifestId("live"))
        val path = "/v1-times/subvolumes/live.manifest"
        val v1 = CanonicalCbor.encodeMap(mapOf("format" to "userspace-subvolume-v1", "readOnly" to false, "manifest" to cid.value))
        files.write(path, v1)
        val mount = UserspaceBtrfs("/v1-times", files, cas) { first }
        assertEquals(BtrfsFileTimes.ZERO, mount.times("live", "file"))
        assertContentEquals(v1, files.readAllBytes(path), "reading an old root must not rewrite it")
        assertTrue(mount.setTimes("live", "file", lastModifiedTime = second))
        assertEquals("userspace-subvolume-v2", CanonicalCbor.decodeMap(files.readAllBytes(path))["format"])
        assertEquals(cid, mount.manifestId("live"))
        assertEquals(BtrfsFileTimes.ZERO.copy(lastModifiedTime = second, changeTime = first),
            UserspaceBtrfs("/v1-times", files, cas).times("live", "file"))
        assertContentEquals(byteArrayOf(8), mount.fetchFile("live", "file"))
    }

    @Test
    fun malformedOrUnownedTimeRowsFailClosed() {
        val files = InMemoryFileOperations()
        val cas = CasStore.inMemory()
        val seed = UserspaceBtrfs("/bad-times", files, cas)
        seed.createSubvolume("live")
        seed.writeFile("live", "file", byteArrayOf(1))
        val cid = assertNotNull(seed.manifestId("live"))
        val row = List(8) { 0L }
        val invalid: List<Any?> = listOf(null, emptyMap<String, Any?>(),
            mapOf("" to row, "missing" to row), mapOf("" to row, "../escape" to row),
            mapOf("" to row.drop(1)), mapOf("" to (listOf(0L, -1L) + row.drop(2))),
            mapOf("" to (listOf(0L, 1_000_000_000L) + row.drop(2))),
            mapOf("" to (listOf(Long.MAX_VALUE, 0L) + row.drop(2))),
            mapOf("" to (listOf("0") + row.drop(1))), mapOf("" to (listOf(0.5) + row.drop(1))))
        for (times in invalid) {
            files.write("/bad-times/subvolumes/live.manifest", CanonicalCbor.encodeMap(mapOf(
                "format" to "userspace-subvolume-v2", "readOnly" to false, "manifest" to cid.value, "times" to times)))
            assertFails { UserspaceBtrfs("/bad-times", files, cas) }
        }
    }
}
