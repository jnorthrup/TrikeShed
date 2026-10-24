package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.common.createTempDirectory
import kotlinx.cinterop.convert
import platform.posix.chmod
import platform.posix.symlink
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class LinuxFileOperationsTest {

    @Test
    fun deleteRecursivelyHandlesUnreadableDirectories() {
        val files = LinuxFileOperations()
        val baseDir = createTempDirectory("trikeshed-native")
        val path = "$baseDir/unreadable_dir"
        files.mkdirs(path)
        assertTrue(files.isDir(path))

        // Make the directory unreadable so opendir fails
        chmod(path, 0u.convert())

        try {
            // This should not throw an exception (such as NullPointerException) if opendir returns null
            files.deleteRecursively(path)
        } finally {
            // Restore permissions so we can clean up
            chmod(path, 0x1FFu.convert())
            files.deleteRecursively(baseDir)
        }
    }

    @Test
    fun testReadAllBytesFileNotFound() {
        val ops = LinuxFileOperations()
        assertFailsWith<IllegalArgumentException> {
            ops.readAllBytes("/does/not/exist/very/unlikely/to/exist.txt")
        }
    }

    @Test
    fun testListDirErrorPath() {
        val ops = LinuxFileOperations()
        // opendir on a file or non-existent dir should return emptyList()
        val result = ops.listDir("/this/path/does/not/exist/hopefully")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `readAllBytes fails on non-existent file`() {
        val ops = LinuxFileOperations()
        val exception = assertFailsWith<IllegalArgumentException> {
            ops.readAllBytes("/does/not/exist/foo.txt")
        }
        assertEquals("open(/does/not/exist/foo.txt) failed", exception.message)
    }

    @Test
    fun `write fails on invalid path`() {
        val ops = LinuxFileOperations()
        val exception = assertFailsWith<IllegalArgumentException> {
            ops.write("/does/not/exist/foo.txt", ByteArray(0))
        }
        assertEquals("open(/does/not/exist/foo.txt) failed", exception.message)
    }

    @Test
    fun `write fails on full device`() {
        val ops = LinuxFileOperations()
        val exception = assertFailsWith<IllegalArgumentException> {
            ops.write("/dev/full", ByteArray(10) { 1 })
        }
        assertTrue(exception.message!!.startsWith("short write on /dev/full:"))
    }

    // What the commonMain stores (FileCasStore, MissionJournal, the Couch, btrfs and mesh volumes) require of this adapter.
    // Reads and plain writes ride the ring; publication, directories, leases and canonical paths are the POSIX sequence.

    private inline fun inTempDir(body: (LinuxFileOperations, String) -> Unit) {
        val files = LinuxFileOperations()
        val dir = files.createTempDir("trikeshed-linux-ops-")
        try { body(files, dir) } finally { files.deleteRecursively(dir) }
    }

    @Test
    fun writeAtomicallyCreatesMissingAncestorsAndLeavesOnlyTheTarget() = inTempDir { files, dir ->
        val target = "$dir/a/b/c/blob.bin"
        val first = ByteArray(300_000) { (it * 31).toByte() }
        files.writeAtomically(target, first)
        assertContentEquals(first, files.readAllBytes(target), "the ring-based reader sees what the atomic publisher wrote")
        val second = byteArrayOf(1, 2, 3)
        files.writeAtomically(target, second)
        assertContentEquals(second, files.readAllBytes(target), "a replacement is whole, never a prefix of the old bytes")
        assertEquals(listOf("blob.bin"), files.listDir("$dir/a/b/c"), "no temporary file is left behind")
    }

    @Test
    fun mkdirsCreatesEveryMissingAncestorAndRefusesThroughAFile() = inTempDir { files, dir ->
        files.mkdirs("$dir/x/y/z")
        assertTrue(files.isDir("$dir/x/y/z"))
        files.mkdirs("$dir/x/y/z")
        files.write("$dir/blocker", "not a directory")
        assertFailsWith<IllegalStateException> { files.mkdirs("$dir/blocker/q") }
    }

    @Test
    fun durabilityIsDurableBecauseWriteAtomicallyIs() {
        assertEquals(StorageDurability.DURABLE, LinuxFileOperations().durability)
    }

    @Test
    fun canonicalPathResolvesSymlinksIncludingAncestorsOfANewPath() = inTempDir { files, dir ->
        files.mkdirs("$dir/real")
        assertEquals(0, symlink("$dir/real", "$dir/link"))
        val root = files.canonicalPath(dir)
        assertEquals("$root/real/new/child", files.canonicalPath("$dir/link/new/child"))
    }

    @Test
    fun resolvePathNormalizesDotDotSoAWalkToTheFirstExistingAncestorTerminates() = inTempDir { files, dir ->
        // BoardFileWal's walk, bounded: an adapter that only joins "..", as this one once did, never reaches an existing path,
        // and the unbounded form of this loop is what ran a whole test binary to 6 GB. A test must fail, not spin.
        var ancestor = files.resolvePath(dir, "new", "board")
        var steps = 0
        while (!files.exists(ancestor)) {
            val parent = files.resolvePath(ancestor, "..")
            assertTrue(parent != ancestor && parent.length < ancestor.length, "the walk must shrink: $ancestor -> $parent")
            ancestor = parent
            assertTrue(++steps <= 8, "the walk is not converging: $ancestor")
        }
        assertEquals(files.resolvePath(dir), ancestor)
        assertEquals("/a", files.resolvePath("/a/b", ".."))
        assertEquals("/", files.resolvePath("/", ".."))
        assertEquals("/a/c", files.resolvePath("/a", "./b", "..", "c"))
    }

    @Test
    fun anExclusiveLeaseRefusesASecondHolderUntilTheFirstCloses() = inTempDir { files, dir ->
        val path = "$dir/journal.lock"
        val first = files.acquireExclusiveLease(path)
        try {
            assertFailsWith<IllegalStateException> { files.acquireExclusiveLease(path) }
        } finally {
            first.close()
        }
        files.acquireExclusiveLease(path).close()
    }
}