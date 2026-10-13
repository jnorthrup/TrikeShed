package borg.trikeshed.graal.subvm

import borg.trikeshed.btrfs.BtrfsWorldStore
import borg.trikeshed.job.CasStore
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
import java.nio.ByteBuffer
import java.nio.file.StandardOpenOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Exercises Graal's actual VFS adapter without requiring a guest-language installation. */
class TrikeShedGraalVfsCasTest {
    private fun vfs(world: BtrfsWorldStore, guest: String) = TrikeShedGraalVfs(
        world.fileOpsFor(guest), world.root, world.subvolumeFor(guest), guest, world.cas,
    )

    @Test
    fun timestampAttributesSurviveRemountWithoutChangingCasIdentityOrAnotherGuest() {
        val world = BtrfsWorldStore.ofMemory()
        val mounted = vfs(world, "vm.times")
        val other = vfs(world, "vm.other")
        val path = mounted.parsePath("/workspace/file")
        mounted.put(path.toString(), byteArrayOf(7))
        other.put(path.toString(), byteArrayOf(7))
        val cid = mounted.btrfsForTools().manifestId("vm.times")
        val original = mounted.readAttributes(path, "basic:*")
        val otherTimes = other.readAttributes(path, "basic:lastModifiedTime,lastAccessTime,creationTime")
        assertTrue(mounted.snapshot("vm.times.snapshot"))
        val modified = FileTime.from(Instant.ofEpochSecond(-2, 123))
        val accessed = FileTime.from(Instant.ofEpochSecond(1_700_000_000, 987_654_321))
        val created = FileTime.fromMillis(42)
        mounted.setAttribute(path, "basic:lastModifiedTime", modified)
        mounted.setAttribute(path, "unix:lastAccessTime", accessed)
        mounted.setAttribute(path, "creationTime", created)
        val reopened = vfs(world, "vm.times")
        assertEquals(mapOf("lastModifiedTime" to modified, "lastAccessTime" to accessed, "creationTime" to created),
            reopened.readAttributes(path, "basic:lastModifiedTime,lastAccessTime,creationTime"))
        assertEquals(mapOf("creationTime" to created), reopened.readAttributes(path, "creationTime"))
        assertEquals(otherTimes, other.readAttributes(path, "basic:lastModifiedTime,lastAccessTime,creationTime"))
        assertEquals(cid, reopened.btrfsForTools().manifestId("vm.times"))
        val snapshot = reopened.btrfsForTools().times("vm.times.snapshot", "workspace/file")!!
        assertEquals(original["lastModifiedTime"], FileTime.from(Instant.ofEpochSecond(
            snapshot.lastModifiedTime.epochSeconds, snapshot.lastModifiedTime.nanosecondsOfSecond.toLong())))
        assertContentEquals(byteArrayOf(7), reopened.fetch(path.toString()))
        reopened.setAttribute(reopened.parsePath("/"), "basic:lastModifiedTime", modified)
        assertEquals(modified, vfs(world, "vm.times").readAttributes(reopened.parsePath("/"), "basic:lastModifiedTime")["lastModifiedTime"])
    }

    @Test
    fun invalidOrFailedAttributeMutationsDoNotAdvanceGenerationOrMetadata() {
        val files = ManifestFiles(InMemoryFileOperations(cwd = "/"))
        val world = BtrfsWorldStore.ofFiles(files, "/attributes", CasStore.inMemory())
        val mounted = vfs(world, "vm.attributes")
        val path = mounted.parsePath("/workspace/file")
        mounted.put(path.toString(), byteArrayOf(9))
        val before = mounted.readAttributes(path, "basic:*")
        val generation = mounted.generation()
        assertFailsWith<NoSuchFileException> {
            mounted.setAttribute(mounted.parsePath("/workspace/missing"), "basic:lastModifiedTime", FileTime.fromMillis(1))
        }
        assertFailsWith<UnsupportedOperationException> { mounted.setAttribute(path, "unix:mode", 0) }
        assertFailsWith<IllegalArgumentException> { mounted.setAttribute(path, "basic:creationTime", 1L) }
        files.reject = true
        assertFails { mounted.setAttribute(path, "basic:lastModifiedTime", FileTime.fromMillis(1)) }
        assertEquals(generation, mounted.generation())
        assertEquals(before, mounted.readAttributes(path, "basic:*"))
        files.reject = false
        assertEquals(before["lastModifiedTime"], vfs(world, "vm.attributes").readAttributes(path, "basic:lastModifiedTime")["lastModifiedTime"])
        assertContentEquals(byteArrayOf(9), mounted.fetch(path.toString()))
    }

    @Test
    fun guestMountsShareCasIdentityWhileMetadataStaysIsolatedAndRemountable() {
        val cas = CasStore.inMemory()
        val world = BtrfsWorldStore.ofMemory(cas)
        val a = vfs(world, "vm.a")
        val b = vfs(world, "vm.b")
        val bytes = ByteArray(4096) { (it * 37).toByte() }
        a.put("/workspace/library.jar", bytes)
        b.put("/workspace/library.jar", bytes)
        assertSame(cas, a.btrfsForTools().cas)
        assertSame(cas, b.btrfsForTools().cas)
        assertEquals(a.btrfsForTools().manifestId("vm.a"), b.btrfsForTools().manifestId("vm.b"))
        assertTrue(a.snapshot("vm.a.snapshot"))
        a.put("/workspace/library.jar", "changed".encodeToByteArray())
        assertContentEquals(bytes, a.fetchSnapshot("vm.a.snapshot", "/workspace/library.jar"))
        assertFalse(b.btrfsForTools().hasSubvolume("vm.a.snapshot"))
        assertContentEquals("changed".encodeToByteArray(), vfs(world, "vm.a").fetch("/workspace/library.jar"))
        assertContentEquals(bytes, vfs(world, "vm.b").fetch("/workspace/library.jar"))
    }

    private class ManifestFiles(private val backing: FileOperations) : FileOperations by backing {
        var reject = false
        override fun writeAtomically(filename: String, bytes: ByteArray) {
            check(!reject || !filename.endsWith(".manifest")) { "Injected manifest publication failure" }
            backing.writeAtomically(filename, bytes)
        }
    }

    @Test
    fun failedManifestCommitKeepsProviderAndOpenChannelAtThePreviousBytes() {
        for (truncate in arrayOf(false, true)) {
            val files = ManifestFiles(InMemoryFileOperations(cwd = "/"))
            val world = BtrfsWorldStore.ofFiles(files, "/world", CasStore.inMemory())
            val mounted = vfs(world, "vm.atomic")
            val original = "abcdef".encodeToByteArray()
            mounted.put("/workspace/file", original)
            val manifestPath = "/world/subvolumes/vm.atomic.manifest"
            val committed = files.readAllBytes(manifestPath)
            val generation = mounted.generation()
            val channel = mounted.newByteChannel(mounted.parsePath("/workspace/file"),
                setOf(StandardOpenOption.READ, StandardOpenOption.WRITE))
            channel.position(1)
            val source = ByteBuffer.wrap("--NEW--".encodeToByteArray()).apply { position(2); limit(5) }
            files.reject = true
            if (truncate) assertFails { channel.truncate(2) } else assertFails { channel.write(source) }
            assertEquals(1L, channel.position())
            assertEquals(6L, channel.size())
            assertEquals(2, source.position())
            assertEquals(generation, mounted.generation())
            assertContentEquals(committed, files.readAllBytes(manifestPath))
            channel.position(0)
            val readback = ByteBuffer.allocate(6)
            assertEquals(6, channel.read(readback))
            assertContentEquals(original, readback.array())
            channel.close()
            files.reject = false
            assertContentEquals(original, vfs(world, "vm.atomic").fetch("/workspace/file"))
        }
    }

    @Test
    fun sparseWritesAndTruncationDoNotResurrectDiscardedBytes() {
        val world = BtrfsWorldStore.ofMemory()
        val mounted = vfs(world, "vm.sparse")
        mounted.put("/workspace/file", "abcdef".encodeToByteArray())
        mounted.newByteChannel(mounted.parsePath("/workspace/file"),
            setOf(StandardOpenOption.READ, StandardOpenOption.WRITE)).use { channel ->
            channel.truncate(2)
            channel.position(5)
            val generation = mounted.generation()
            assertEquals(0, channel.write(ByteBuffer.allocate(0)))
            assertEquals(generation, mounted.generation())
            assertEquals(2L, channel.size())
            channel.write(ByteBuffer.wrap(byteArrayOf(7)))
            assertContentEquals(byteArrayOf(97, 98, 0, 0, 0, 7), mounted.fetch("/workspace/file"))
            channel.position(10)
            channel.truncate(12)
            assertEquals(10L, channel.position())
            channel.truncate(8)
            assertEquals(8L, channel.position())
            assertEquals(6L, channel.size())
            assertEquals(0, channel.read(ByteBuffer.allocate(0)))
            assertEquals(-1, channel.read(ByteBuffer.allocate(1)))
        }
        assertContentEquals(byteArrayOf(97, 98, 0, 0, 0, 7), vfs(world, "vm.sparse").fetch("/workspace/file"))
    }

    @Test
    fun readOnlyCreateAndMissingPayloadCannotOpenAnInventedEmptyFile() {
        val cas = CasStore.inMemory()
        val mounted = vfs(BtrfsWorldStore.ofMemory(cas), "vm.open")
        assertFailsWith<NoSuchFileException> {
            mounted.newByteChannel(mounted.parsePath("/workspace/missing"),
                setOf(StandardOpenOption.READ, StandardOpenOption.CREATE))
        }
        assertNull(mounted.fetch("/workspace/missing"))
        val bytes = "original bytes".encodeToByteArray()
        mounted.put("/workspace/file", bytes)
        cas.corrupt(borg.trikeshed.job.ContentId.of(bytes))
        assertFails { mounted.newByteChannel(mounted.parsePath("/workspace/file"), setOf(StandardOpenOption.READ)) }
        assertFails { mounted.readAttributes(mounted.parsePath("/workspace/file"), "basic:size") }
    }
}
