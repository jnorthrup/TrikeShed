package borg.trikeshed.btrfs

import borg.trikeshed.cas.CasPaths
import borg.trikeshed.cas.FileExtent
import borg.trikeshed.cas.FileTreeManifest
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
import kotlin.test.*

class UserspaceBtrfsManifestTest {
    @Test
    fun canonical_identity_ignores_provider_name_order_and_snapshot_flag() {
        val shared = CasStore.inMemory()
        val first = UserspaceBtrfs("/first", InMemoryFileOperations(), shared)
        val second = UserspaceBtrfs("/second", InMemoryFileOperations())
        assertTrue(first.createSubvolume("live"))
        assertTrue(second.createSubvolume("other"))
        assertTrue(first.writeFile("live", "dir/b", byteArrayOf(2)))
        assertTrue(first.writeFile("live", "dir/a", byteArrayOf(1)))
        assertTrue(second.writeFile("other", "dir/a", byteArrayOf(1)))
        assertTrue(second.writeFile("other", "dir/b", byteArrayOf(2)))
        assertTrue(first.snapshot("live", "snapshot"))
        val id = assertNotNull(first.manifestId("live"))
        assertEquals(id, first.manifestId("snapshot"))
        assertEquals(id, second.manifestId("other"))
        val manifest = assertNotNull(first.manifest("live"))
        assertContentEquals(manifest.encode(), shared.get(id))
        assertContentEquals(manifest.encode(), FileTreeManifest.decode(manifest.encode()).encode())
        assertNotNull(manifest.document())
    }

    @Test
    fun publication_failure_keeps_every_live_and_reopened_index_unchanged() {
        val memory = InMemoryFileOperations()
        val failures = PublicationFiles(memory)
        val mount = UserspaceBtrfs("/atomic", failures)
        assertTrue(mount.createSubvolume("live"))
        assertTrue(mount.writeFile("live", "file", byteArrayOf(7)))
        val before = memory.readAllBytes("/atomic/subvolumes/live.manifest").copyOf()
        failures.reject = true
        assertFails { mount.createSubvolume("new") }
        assertFails { mount.writeFile("live", "file", byteArrayOf(8)) }
        assertFails { mount.deleteFile("live", "file") }
        assertFails { mount.createDirectory("live", "newdir/child") }
        assertFails { mount.snapshot("live", "snapshot") }
        assertFails { mount.deleteSubvolume("live") }
        assertFalse(mount.receiveManifest("received", assertNotNull(mount.manifest("live"))))
        assertEquals(listOf("live"), mount.listSubvolumes())
        assertEquals(listOf("file"), mount.listDirectory("live"))
        assertContentEquals(byteArrayOf(7), mount.fetchFile("live", "file"))
        assertContentEquals(before, memory.readAllBytes("/atomic/subvolumes/live.manifest"))
        failures.reject = false
        val reopened = UserspaceBtrfs("/atomic", failures)
        assertEquals(listOf("live"), reopened.listSubvolumes())
        assertContentEquals(byteArrayOf(7), reopened.fetchFile("live", "file"))
    }

    @Test
    fun post_publication_error_requires_reopen_instead_of_serving_stale_memory() {
        val memory = InMemoryFileOperations()
        val failures = PublicationFiles(memory)
        val mount = UserspaceBtrfs("/uncertain", failures)
        assertTrue(mount.createSubvolume("live"))
        assertTrue(mount.writeFile("live", "file", byteArrayOf(1)))
        failures.after = true
        assertFails { mount.writeFile("live", "file", byteArrayOf(2)) }
        assertFails { mount.fetchFile("live", "file") }
        assertFails { mount.createSubvolume("other") }
        failures.after = false
        assertContentEquals(byteArrayOf(2), UserspaceBtrfs("/uncertain", failures).fetchFile("live", "file"))
    }

    @Test
    fun receive_requires_all_verified_extents_and_exact_lengths_before_publication() {
        val cas = CasStore.inMemory()
        val files = InMemoryFileOperations()
        val mount = UserspaceBtrfs("/received", files, cas)
        val bytes = byteArrayOf(4, 5, 6)
        val id = ContentId.of(bytes)
        val manifest = fileManifest("file", id, 3)
        assertFalse(mount.receiveManifest("absent", manifest))
        assertFalse(mount.hasSubvolume("absent"))
        cas.put(bytes)
        assertFalse(mount.receiveManifest("length", fileManifest("file", id, 2)))
        assertFalse(files.exists("/received/subvolumes/length.manifest"))
        assertTrue(mount.receiveManifest("valid", manifest))
        assertFalse(mount.writeFile("valid", "file", bytes))
        assertTrue(mount.receiveManifest("mutable", manifest, readOnly = false))
        assertTrue(mount.writeFile("mutable", "file", byteArrayOf(9)))
        assertContentEquals(bytes, mount.fetchFile("valid", "file"))
        cas.corrupt(id)
        assertNull(mount.fetchFile("valid", "file"))
        assertFalse(mount.receiveManifest("corrupt", manifest))
    }

    @Test
    fun shared_cas_survives_subvolume_deletion_and_foreign_roots() {
        val cas = CasStore.inMemory()
        val files = InMemoryFileOperations()
        val first = UserspaceBtrfs("/shared-a", files, cas)
        val second = UserspaceBtrfs("/shared-b", files, cas)
        first.createSubvolume("live")
        first.writeFile("live", "file", byteArrayOf(42))
        val manifest = assertNotNull(first.manifest("live"))
        val root = assertNotNull(first.manifestId("live"))
        val extent = manifest.references()[0]
        assertTrue(second.receiveManifest("copy", manifest))
        assertTrue(first.deleteSubvolume("live"))
        assertTrue(second.deleteSubvolume("copy"))
        assertContentEquals(byteArrayOf(42), cas.get(extent))
        assertContentEquals(manifest.encode(), cas.get(root))
        assertFalse(files.exists("/shared-a/extents"))
        assertEquals(emptyList(), UserspaceBtrfs("/shared-a", files, cas).listSubvolumes())
    }

    @Test
    fun local_reclamation_keeps_other_mounts_durable_roots_and_manifest_blobs() {
        val files = InMemoryFileOperations()
        val first = UserspaceBtrfs("/multi", files)
        first.createSubvolume("first")
        first.writeFile("first", "a", byteArrayOf(1))
        val second = UserspaceBtrfs("/multi", files)
        second.createSubvolume("second")
        second.writeFile("second", "b", byteArrayOf(2))
        val root = assertNotNull(second.manifestId("second"))
        assertTrue(first.deleteSubvolume("first"))
        val reopened = UserspaceBtrfs("/multi", files)
        assertEquals(listOf("second"), reopened.listSubvolumes())
        assertContentEquals(byteArrayOf(2), reopened.fetchFile("second", "b"))
        assertNotNull(reopened.cas.get(root))
    }

    @Test
    fun private_extents_and_canonical_roots_migrate_into_injected_cas_on_upgrade() {
        val files = InMemoryFileOperations()
        val old = UserspaceBtrfs("/upgrade", files)
        old.createSubvolume("guest")
        old.writeFile("guest", "file", byteArrayOf(3, 4))
        val root = assertNotNull(old.manifestId("guest"))
        val extent = assertNotNull(old.manifest("guest")).references()[0]
        val shared = CasStore.inMemory()
        val upgraded = UserspaceBtrfs("/upgrade", files, shared)
        assertNotNull(shared.get(root))
        assertContentEquals(byteArrayOf(3, 4), upgraded.fetchFile("guest", "file"))
        assertContentEquals(byteArrayOf(3, 4), shared.get(extent))
        assertEquals(root, upgraded.manifestId("guest"))
        files.deleteRecursively("/upgrade/extents")
        assertContentEquals(byteArrayOf(3, 4), UserspaceBtrfs("/upgrade", files, shared).fetchFile("guest", "file"))
    }

    @Test
    fun corrupt_authoritative_cas_does_not_fall_back_to_valid_private_bytes() {
        val files = InMemoryFileOperations()
        val old = UserspaceBtrfs("/corrupt-shared", files)
        old.createSubvolume("guest")
        old.writeFile("guest", "file", byteArrayOf(3, 4))
        val root = assertNotNull(old.manifestId("guest"))
        val extent = assertNotNull(old.manifest("guest")).references()[0]
        val shared = CasStore.inMemory()
        shared.put(assertNotNull(old.cas.get(root)))
        shared.put(byteArrayOf(3, 4))
        shared.corrupt(extent)
        val upgraded = UserspaceBtrfs("/corrupt-shared", files, shared)
        assertNull(upgraded.fetchFile("guest", "file"))
        assertFalse(upgraded.receiveManifest("copy", assertNotNull(upgraded.manifest("guest"))))
    }

    @Test
    fun legacy_text_and_flat_extent_import_remain_readable_and_upgrade_on_mutation() {
        val files = InMemoryFileOperations()
        val bytes = byteArrayOf(0, -1, 10, 13, 9)
        val cid = ContentId.of(bytes)
        files.write("/legacy/subvolumes/old.manifest", "BTRFS_SEND_V2\nRO\t0\nD\tdir\nF\tdir/file\t${cid.value}\t${bytes.size}\n")
        files.write("/legacy/extents/${cid.value.replace(':', '_')}", bytes)
        val shared = CasStore.inMemory()
        val mount = UserspaceBtrfs("/legacy", files, shared)
        assertContentEquals(bytes, mount.fetchFile("old", "dir/file"))
        assertContentEquals(bytes, shared.get(cid))
        val transfer = assertNotNull(mount.send("old"))
        val destination = UserspaceBtrfs("/legacy-copy", files)
        assertTrue(destination.receive("copy", transfer))
        assertContentEquals(bytes, destination.fetchFile("copy", "dir/file"))
        assertFalse(destination.receive("truncated", transfer.copyOf(transfer.size - 1)))
        assertTrue(mount.writeFile("old", "new", byteArrayOf(7)))
        assertTrue(files.readAllBytes("/legacy/subvolumes/old.manifest")[0] != 'B'.code.toByte())
        assertContentEquals(bytes, UserspaceBtrfs("/legacy", files, shared).fetchFile("old", "dir/file"))
    }

    @Test
    fun corrupt_local_manifests_and_legacy_paths_fail_closed_on_reopen() {
        val files = InMemoryFileOperations()
        files.write("/bad/subvolumes/guest.manifest", "BTRFS_SEND_V2\nRO\t0\nD\t../escape\n")
        assertFails { UserspaceBtrfs("/bad", files) }
        files.write("/bad/subvolumes/guest.manifest", byteArrayOf(0))
        assertFails { UserspaceBtrfs("/bad", files) }
        val mount = UserspaceBtrfs("/safe", files)
        assertTrue(mount.createSubvolume("live"))
        assertTrue(mount.writeFile("live", "file", byteArrayOf(1)))
        assertFalse(mount.writeFile("live", "file/child", byteArrayOf(2)))
        assertFalse(mount.createDirectory("live", "file/child"))
        assertFalse(mount.createDirectory("live", "file"))
        assertFalse(mount.createSubvolume("bad\nname"))
        assertContentEquals(byteArrayOf(1), mount.fetchFile("live", "file"))
    }

    @Test
    fun reasserting_existing_corrupt_default_blob_repairs_cas_before_publication() {
        val files = InMemoryFileOperations()
        val mount = UserspaceBtrfs("/repair", files)
        val bytes = byteArrayOf(1, 2, 3)
        val cid = ContentId.of(bytes)
        mount.createSubvolume("live")
        mount.writeFile("live", "file", bytes)
        val path = "/repair/extents/${CasPaths.blob(cid)}"
        files.write(path, byteArrayOf(9))
        assertNull(mount.fetchFile("live", "file"))
        assertTrue(mount.writeFile("live", "file", bytes))
        assertContentEquals(bytes, files.readAllBytes(path))
        assertContentEquals(bytes, UserspaceBtrfs("/repair", files).fetchFile("live", "file"))
    }

    @Test
    fun colliding_subvolume_and_path_keys_survive_mutation_snapshot_and_reopen() {
        val files = InMemoryFileOperations()
        val mount = UserspaceBtrfs("/collisions", files)
        // Every concatenation of six Aa/BB pairs has the same String hash.
        val names = Array(64) { value -> (0..5).joinToString("") { bit -> if (value and (1 shl bit) == 0) "Aa" else "BB" } }
        assertTrue(names.all { it.hashCode() == names[0].hashCode() })
        for (name in names) assertTrue(mount.createSubvolume(name))
        for ((index, path) in names.withIndex()) assertTrue(mount.writeFile(names[0], path, byteArrayOf(index.toByte())))
        val id = mount.manifestId(names[0])
        assertTrue(mount.snapshot(names[0], "snapshot"))
        for (index in names.indices step 2) assertTrue(mount.deleteFile(names[0], names[index]))
        val reopened = UserspaceBtrfs("/collisions", files)
        assertEquals(id, reopened.manifestId("snapshot"))
        for ((index, path) in names.withIndex()) {
            assertTrue(reopened.hasSubvolume(path))
            assertContentEquals(byteArrayOf(index.toByte()), reopened.fetchFile("snapshot", path))
            if (index % 2 == 0) assertNull(reopened.fetchFile(names[0], path))
        }
    }

    private fun fileManifest(path: String, cid: ContentId, length: Long): FileTreeManifest {
        val extent: FileExtent = cid j length
        return FileTreeManifest.of(s_[path j extent])
    }

    private class PublicationFiles(private val memory: FileOperations) : FileOperations by memory {
        var reject = false
        var after = false
        override fun writeAtomically(filename: String, bytes: ByteArray) {
            if (filename.endsWith(".manifest") && reject) error("Injected failure before publication")
            memory.writeAtomically(filename, bytes)
            if (filename.endsWith(".manifest") && after) error("Injected failure after publication")
        }
    }
}
