package borg.trikeshed.btrfs

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
import kotlin.test.*
import kotlin.time.Instant

class UserspaceBtrfsBatchTest {
    @Test
    fun sourceMountPublishesOneTreeInsteadOfRetainingEveryIntermediateTree() {
        val files = PublicationFiles(InMemoryFileOperations())
        val cas = BatchCas()
        val mount = UserspaceBtrfs("/batch", files, cas)
        assertTrue(mount.createSubvolume("live"))
        files.publications = 0
        cas.puts = 0
        val sources = 256 j { index: Int -> "pkg/module$index.py" j "VALUE = $index".encodeToByteArray() }

        assertTrue(mount.writeFiles("live", sources))

        assertEquals(1, files.publications)
        assertEquals(sources.size + 1, cas.puts, "only source extents and the final canonical tree belong in CAS")
        val reopened = UserspaceBtrfs("/batch", files, cas)
        assertEquals(256, reopened.listDirectory("live", "pkg")?.size)
        for (index in 0 until sources.size) {
            assertContentEquals("VALUE = $index".encodeToByteArray(), reopened.fetchFile("live", "pkg/module$index.py"))
        }
    }

    @Test
    fun rejectedPathsAndFileDirectoryConflictsStoreNoBatchExtentsOrPartialRoot() {
        val files = PublicationFiles(InMemoryFileOperations())
        val cas = BatchCas()
        val mount = UserspaceBtrfs("/invalid-batch", files, cas)
        assertTrue(mount.createSubvolume("live"))
        assertTrue(mount.writeFile("live", "file", byteArrayOf(1)))
        assertTrue(mount.createDirectory("live", "dir"))
        val before = files.readAllBytes("/invalid-batch/subvolumes/live.manifest")
        val puts = cas.puts
        val publications = files.publications
        val bytes = byteArrayOf(2)
        val rejected = s_[
            s_["accepted" j bytes, "../escape" j bytes],
            s_["accepted" j bytes, "file/child" j bytes],
            s_["accepted" j bytes, "dir" j bytes],
            s_["parent" j bytes, "parent/child" j bytes],
            s_["parent/child" j bytes, "parent" j bytes],
        ]

        for (batch in rejected.view) {
            assertFalse(mount.writeFiles("live", batch))
            assertEquals(puts, cas.puts)
            assertEquals(publications, files.publications)
            assertContentEquals(before, files.readAllBytes("/invalid-batch/subvolumes/live.manifest"))
            assertNull(mount.fetchFile("live", "accepted"))
            assertFalse(mount.isDirectory("live", "parent"))
            assertContentEquals(byteArrayOf(1), mount.fetchFile("live", "file"))
        }
        val empty: Series<Join<String, ByteArray>> = 0 j { error("empty batch accessed") }
        assertTrue(mount.writeFiles("live", empty))
        assertEquals(publications, files.publications, "an empty batch leaves the root untouched")
        assertFalse(mount.writeFiles("missing", empty))
    }

    @Test
    fun batchPreservesInodeTimesContentOwnershipAndSnapshotIsolation() {
        val files = InMemoryFileOperations()
        val cas = BatchCas()
        val first = Instant.fromEpochSeconds(1_700_000_000)
        val second = Instant.fromEpochSeconds(1_700_000_010)
        var now = first
        val mount = UserspaceBtrfs("/batch-times", files, cas) { now }
        assertTrue(mount.createSubvolume("live"))
        assertTrue(mount.writeFile("live", "dir/existing", byteArrayOf(1)))
        assertTrue(mount.snapshot("live", "snapshot"))
        val snapshot = mount.manifestId("snapshot")
        val discarded = byteArrayOf(9)
        val changed = byteArrayOf(2)
        now = second

        assertTrue(mount.writeFiles("live", s_[
            "dir/existing" j discarded,
            "dir/existing" j changed,
            "dir/nested/new" j byteArrayOf(3),
            "top" j byteArrayOf(4),
        ]))
        changed[0] = 99

        val reopened = UserspaceBtrfs("/batch-times", files, cas)
        assertContentEquals(byteArrayOf(2), reopened.fetchFile("live", "dir/existing"))
        assertNull(cas.get(ContentId.of(discarded)), "overwritten batch entries must not retain unused extents")
        assertEquals(BtrfsFileTimes.at(first).written(second), reopened.times("live", "dir/existing"))
        assertEquals(BtrfsFileTimes.at(first).written(second), reopened.times("live", "dir"))
        assertEquals(BtrfsFileTimes.at(first).written(second), reopened.times("live", ""))
        assertEquals(BtrfsFileTimes.at(second), reopened.times("live", "dir/nested"))
        assertEquals(BtrfsFileTimes.at(second), reopened.times("live", "dir/nested/new"))
        assertEquals(snapshot, reopened.manifestId("snapshot"))
        assertContentEquals(byteArrayOf(1), reopened.fetchFile("snapshot", "dir/existing"))
        assertEquals(BtrfsFileTimes.at(first), reopened.times("snapshot", "dir/existing"))
        assertNull(reopened.fetchFile("snapshot", "top"))
        assertFalse(reopened.writeFiles("snapshot", s_["file" j byteArrayOf(5)]))
    }

    @Test
    fun extentAndRootFailuresPreserveThePreviousBatchAndAllowRetry() {
        val files = PublicationFiles(InMemoryFileOperations())
        val cas = BatchCas()
        val mount = UserspaceBtrfs("/batch-failure", files, cas)
        assertTrue(mount.createSubvolume("live"))
        assertTrue(mount.writeFile("live", "old", byteArrayOf(1)))
        val before = files.readAllBytes("/batch-failure/subvolumes/live.manifest")
        val batch = s_["old" j byteArrayOf(2), "new" j byteArrayOf(3)]
        cas.rejected = ContentId.of(byteArrayOf(3))

        assertFails { mount.writeFiles("live", batch) }
        assertContentEquals(before, files.readAllBytes("/batch-failure/subvolumes/live.manifest"))
        assertContentEquals(byteArrayOf(1), mount.fetchFile("live", "old"))
        assertNull(mount.fetchFile("live", "new"))
        cas.rejected = null
        files.reject = true
        assertFails { mount.writeFiles("live", batch) }
        val reopened = UserspaceBtrfs("/batch-failure", files, cas)
        assertContentEquals(byteArrayOf(1), reopened.fetchFile("live", "old"))
        assertNull(reopened.fetchFile("live", "new"))
        files.reject = false
        assertTrue(mount.writeFiles("live", batch))
        assertContentEquals(byteArrayOf(2), mount.fetchFile("live", "old"))
        assertContentEquals(byteArrayOf(3), mount.fetchFile("live", "new"))
    }

    private class BatchCas : CasStore() {
        val backing = CasStore.inMemory()
        var puts = 0
        var rejected: ContentId? = null
        override fun get(cid: ContentId): ByteArray? = backing.get(cid)
        override fun put(bytes: ByteArray): ContentId {
            puts++
            check(ContentId.of(bytes) != rejected) { "extent refused" }
            return backing.put(bytes)
        }
    }

    private class PublicationFiles(val backing: FileOperations) : FileOperations by backing {
        var publications = 0
        var reject = false
        override fun writeAtomically(filename: String, bytes: ByteArray) {
            if (filename.endsWith(".manifest")) {
                check(!reject) { "root refused" }
                publications++
            }
            backing.writeAtomically(filename, bytes)
        }
    }
}
