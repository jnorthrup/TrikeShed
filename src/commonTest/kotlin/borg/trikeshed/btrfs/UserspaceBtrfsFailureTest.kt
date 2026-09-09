package borg.trikeshed.btrfs

import borg.trikeshed.cas.FileExtent
import borg.trikeshed.cas.FileTreeManifest
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
import kotlin.test.*

class UserspaceBtrfsFailureTest {
    @Test
    fun extent_and_tree_cas_failures_preserve_the_previous_root_and_allow_retry() {
        for (failure in CasFailure.entries) for (treeFailure in listOf(false, true)) {
            val files = InMemoryFileOperations()
            val cas = FailureCas()
            val mount = UserspaceBtrfs("/cas-failure", files, cas)
            assertTrue(mount.createSubvolume("live"))
            assertTrue(mount.writeFile("live", "file", byteArrayOf(1)))
            val before = files.readAllBytes("/cas-failure/subvolumes/live.manifest").copyOf()
            val next = byteArrayOf(2)
            cas.rejected = if (treeFailure) ContentId.of(fileManifest(next).encode()) else ContentId.of(next)
            cas.failure = failure
            assertFails { mount.writeFile("live", "file", next) }
            assertContentEquals(before, files.readAllBytes("/cas-failure/subvolumes/live.manifest"))
            assertContentEquals(byteArrayOf(1), mount.fetchFile("live", "file"))
            assertContentEquals(byteArrayOf(1), UserspaceBtrfs("/cas-failure", files, cas).fetchFile("live", "file"))
            cas.rejected = null
            assertTrue(mount.writeFile("live", "file", next))
            assertContentEquals(next, UserspaceBtrfs("/cas-failure", files, cas).fetchFile("live", "file"))
        }
    }

    @Test
    fun silently_dropped_root_writes_never_report_success_or_remove_previous_data() {
        val files = InMemoryFileOperations()
        val publication = PublicationFiles(files)
        val mount = UserspaceBtrfs("/dropped", publication)
        assertTrue(mount.createSubvolume("live"))
        assertTrue(mount.writeFile("live", "file", byteArrayOf(1)))
        val before = files.readAllBytes("/dropped/subvolumes/live.manifest").copyOf()
        val manifest = assertNotNull(mount.manifest("live"))
        val transfer = assertNotNull(mount.send("live"))
        publication.failure = PublicationFailure.DROP
        assertFails { mount.createSubvolume("new") }
        assertFails { mount.writeFile("live", "file", byteArrayOf(2)) }
        assertFails { mount.deleteFile("live", "file") }
        assertFails { mount.createDirectory("live", "dir") }
        assertFails { mount.snapshot("live", "snapshot") }
        assertFails { mount.deleteSubvolume("live") }
        assertFalse(mount.receiveManifest("received", manifest))
        assertFalse(mount.receive("transferred", transfer))
        assertEquals(listOf("live"), mount.listSubvolumes())
        assertContentEquals(before, files.readAllBytes("/dropped/subvolumes/live.manifest"))
        assertContentEquals(byteArrayOf(1), mount.fetchFile("live", "file"))
        val reopened = UserspaceBtrfs("/dropped", files)
        assertEquals(listOf("live"), reopened.listSubvolumes())
        assertContentEquals(byteArrayOf(1), reopened.fetchFile("live", "file"))
    }

    @Test
    fun post_tombstone_failure_does_not_sweep_or_expose_a_stale_mount() {
        val files = InMemoryFileOperations()
        val publication = PublicationFiles(files)
        val mount = UserspaceBtrfs("/tombstone", publication)
        assertTrue(mount.createSubvolume("live"))
        assertTrue(mount.writeFile("live", "file", byteArrayOf(1)))
        val root = assertNotNull(mount.manifestId("live"))
        val extent = assertNotNull(mount.manifest("live")).references()[0]
        publication.failure = PublicationFailure.AFTER
        assertFails { mount.deleteSubvolume("live") }
        assertFails { mount.listSubvolumes() }
        assertFails { mount.fetchFile("live", "file") }
        assertFails { mount.manifestId("live") }
        assertNotNull(mount.cas.get(root))
        assertContentEquals(byteArrayOf(1), mount.cas.get(extent))
        val reopened = UserspaceBtrfs("/tombstone", files)
        assertEquals(emptyList(), reopened.listSubvolumes())
        assertTrue(reopened.createSubvolume("live"))
        assertEquals(emptyList(), reopened.listDirectory("live"))
    }

    @Test
    fun unreadable_publication_outcome_requires_reopen() {
        val files = InMemoryFileOperations()
        val publication = PublicationFiles(files)
        val mount = UserspaceBtrfs("/read-failure", publication)
        assertTrue(mount.createSubvolume("live"))
        assertTrue(mount.writeFile("live", "file", byteArrayOf(1)))
        publication.failure = PublicationFailure.READ
        assertFails { mount.writeFile("live", "file", byteArrayOf(2)) }
        assertFails { mount.hasSubvolume("live") }
        assertFails { mount.send("live") }
        assertContentEquals(byteArrayOf(2), UserspaceBtrfs("/read-failure", files).fetchFile("live", "file"))
    }

    @Test
    fun malformed_binary_transfers_publish_no_extents_or_subvolume() {
        val files = InMemoryFileOperations()
        val cas = FailureCas()
        val mount = UserspaceBtrfs("/transfer", files, cas)
        val bytes = byteArrayOf(0, -1, 10)
        val cid = ContentId.of(bytes)
        val row = "F\tfile\t${cid.value}\t3\n"
        fun header(rows: String) = "BTRFS_SEND_V2\n${rows}END\n".encodeToByteArray()
        fun block(length: String = "3") = "EXT\t${cid.value}\t$length\n".encodeToByteArray() + bytes + byteArrayOf(10)
        val invalidRows = listOf(
            row + row, "D\t../escape\n", "D\tdir\textra\n", "D\t\n",
            "F\tdir/file\t${cid.value}\t3\n", "F\tfile\tbad-id\t3\n",
            "F\tfile\t${cid.value}\t-1\n", "F\tfile\t${cid.value}\t2147483648\n",
            "F\tfile\t${cid.value}\t2\n", "D\tfile\n" + row, "X\tfile\n",
        )
        val valid = header(row) + block()
        val invalid = invalidRows.map { header(it) + block() } + listOf(
            header(row), header(row) + block("-1"), header(row) + block("2147483647"),
            header(row) + block("2147483648"), header(row) + block() + block(),
            header("") + block(), valid.copyOf(valid.size - 1), valid + byteArrayOf(1),
            valid.copyOf().also { it[it.lastIndex] = 0 },
            valid.copyOf().also { it[it.lastIndex - 1] = 0 },
            header("D\tbad\u0000path\n"), "BTRFS_SEND_V2\nD\t".encodeToByteArray() + byteArrayOf(-1, 10),
        )
        for ((index, stream) in invalid.withIndex()) {
            assertFalse(mount.receive("bad-$index", stream), "Malformed transfer $index")
            assertEquals(0, cas.puts, "Transfer $index wrote CAS before validation")
            assertFalse(files.exists("/transfer/subvolumes/bad-$index.manifest"))
        }
        assertEquals(emptyList(), UserspaceBtrfs("/transfer", files, cas).listSubvolumes())
        assertTrue(mount.receive("valid", valid))
        assertContentEquals(bytes, UserspaceBtrfs("/transfer", files, cas).fetchFile("valid", "file"))
    }

    @Test
    fun malformed_local_roots_fail_closed_instead_of_becoming_empty_mounts() {
        val files = InMemoryFileOperations()
        val cas = CasStore.inMemory()
        val missing = ContentId.of(byteArrayOf(42))
        val invalidTree = cas.put(byteArrayOf(0))
        val format = "userspace-subvolume-v1"
        val invalid = listOf(
            mapOf("format" to "unknown", "readOnly" to false, "manifest" to missing.value),
            mapOf("format" to format, "readOnly" to 0, "manifest" to missing.value),
            mapOf("format" to format, "readOnly" to false, "manifest" to "../escape"),
            mapOf("format" to format, "readOnly" to false, "manifest" to missing.value),
            mapOf("format" to format, "readOnly" to false, "manifest" to invalidTree.value),
            mapOf("format" to format, "deleted" to false),
            mapOf("format" to format, "deleted" to true, "manifest" to missing.value),
        )
        for (fields in invalid) {
            files.write("/invalid/subvolumes/live.manifest", CanonicalCbor.encodeMap(fields))
            assertFails { UserspaceBtrfs("/invalid", files, cas) }
        }
        val mount = UserspaceBtrfs("/corrupt-tree", files, cas)
        assertTrue(mount.createSubvolume("live"))
        cas.corrupt(assertNotNull(mount.manifestId("live")))
        assertFails { UserspaceBtrfs("/corrupt-tree", files, cas) }
    }

    @Test
    fun snapshot_and_received_manifests_capture_values_across_reopen() {
        val files = InMemoryFileOperations()
        val cas = CasStore.inMemory()
        val mount = UserspaceBtrfs("/immutable", files, cas)
        val bytes = byteArrayOf(1, 2)
        assertTrue(mount.createSubvolume("live"))
        assertTrue(mount.writeFile("live", "file", bytes))
        val captured = assertNotNull(mount.manifest("live"))
        val root = assertNotNull(mount.manifestId("live"))
        assertTrue(mount.snapshot("live", "snapshot"))
        bytes[0] = 9
        assertNotNull(mount.fetchFile("snapshot", "file"))[0] = 8
        assertTrue(mount.writeFile("live", "file", byteArrayOf(3)))
        assertEquals(root, ContentId.of(captured.encode()))
        assertTrue(mount.deleteSubvolume("live"))
        var extentId = cas.put(byteArrayOf(4))
        val mutableExtent: FileExtent = object : Join<ContentId, Long> {
            override val a get() = extentId
            override val b get() = 1L
        }
        assertTrue(mount.receiveManifest("received", FileTreeManifest.of(s_["file" j mutableExtent])))
        extentId = cas.put(byteArrayOf(5))
        val reopened = UserspaceBtrfs("/immutable", files, cas)
        assertEquals(root, reopened.manifestId("snapshot"))
        assertContentEquals(byteArrayOf(1, 2), reopened.fetchFile("snapshot", "file"))
        assertContentEquals(byteArrayOf(4), reopened.fetchFile("received", "file"))
        assertFalse(reopened.writeFile("snapshot", "file", byteArrayOf(0)))
        assertFalse(reopened.deleteFile("snapshot", "file"))
        assertFalse(reopened.createDirectory("snapshot", "dir"))
    }

    @Test
    fun paths_must_round_trip_without_aliases_or_lossy_unicode() {
        val files = InMemoryFileOperations()
        val mount = UserspaceBtrfs("/paths", files)
        assertTrue(mount.createSubvolume("live"))
        val invalid = listOf("/file", "../file", "dir/../file", "dir//file", "dir/./file", "dir/", "dir\\file", "bad\u0000path", "bad\npath", "bad\tpath", "bad\rpath", "bad\uD800", "bad\uDC00")
        for (path in invalid) {
            assertFalse(mount.createSubvolume(path))
            assertFalse(mount.writeFile("live", path, byteArrayOf(1)))
            assertFalse(mount.createDirectory("live", path))
        }
        val name = "valid-\uD83D\uDE00"
        assertTrue(mount.createSubvolume(name))
        assertTrue(mount.writeFile(name, "file..name", byteArrayOf(2)))
        assertContentEquals(byteArrayOf(2), UserspaceBtrfs("/paths", files).fetchFile(name, "file..name"))
        assertEquals(emptyList(), mount.listDirectory("live"))
    }

    private fun fileManifest(bytes: ByteArray): FileTreeManifest {
        val extent: FileExtent = ContentId.of(bytes) j bytes.size.toLong()
        return FileTreeManifest.of(s_["file" j extent])
    }

    private enum class CasFailure { BEFORE, AFTER, DROP, ID, BYTES }

    private class FailureCas : CasStore() {
        val backing = CasStore.inMemory()
        var rejected: ContentId? = null
        var failure = CasFailure.BEFORE
        var puts = 0
        override fun get(cid: ContentId): ByteArray? = backing.get(cid)
        override fun put(bytes: ByteArray): ContentId {
            puts++
            val cid = ContentId.of(bytes)
            if (cid == rejected) when (failure) {
                CasFailure.BEFORE -> error("Injected CAS failure before write")
                CasFailure.AFTER -> { backing.put(bytes); error("Injected CAS failure after write") }
                CasFailure.DROP -> return cid
                CasFailure.ID -> return ContentId.of(byteArrayOf(99))
                CasFailure.BYTES -> { backing.put(byteArrayOf(99)); return cid }
            }
            return backing.put(bytes)
        }
    }

    private enum class PublicationFailure { NONE, DROP, AFTER, READ }

    private class PublicationFiles(val backing: FileOperations) : FileOperations by backing {
        var failure = PublicationFailure.NONE
        var unreadable: String? = null
        override fun writeAtomically(filename: String, bytes: ByteArray) {
            if (!filename.endsWith(".manifest")) return backing.writeAtomically(filename, bytes)
            if (failure == PublicationFailure.DROP) return
            backing.writeAtomically(filename, bytes)
            if (failure == PublicationFailure.AFTER) error("Injected post-publication failure")
            if (failure == PublicationFailure.READ) unreadable = filename
        }
        override fun readAllBytes(filename: String): ByteArray {
            check(filename != unreadable) { "Injected manifest read failure" }
            return backing.readAllBytes(filename)
        }
    }
}
