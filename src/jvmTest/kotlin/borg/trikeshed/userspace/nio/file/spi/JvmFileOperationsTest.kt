package borg.trikeshed.userspace.nio.file.spi

import kotlinx.coroutines.*
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JVM adapter contract: pure java.nio.file behind FileOperations.
 */
class JvmFileOperationsTest {

    private val ops = JvmFileOperations()

    @Test
    fun `write read exists delete via nio adapter`() {
        val dir = ops.createTempDir("jvm-file-ops-")
        val path = ops.resolvePath(dir, "note.txt")
        try {
            ops.write(path, "hello nio")
            assertTrue(ops.exists(path))
            assertTrue(ops.isFile(path))
            assertEquals("hello nio", ops.readString(path))
            assertEquals(listOf("hello nio"), ops.readAllLines(path))
            ops.deleteRecursively(path)
            assertFalse(ops.exists(path))
        } finally {
            ops.deleteRecursively(dir)
        }
    }

    @Test
    fun `open close size channel registry`() {
        val dir = ops.createTempDir("jvm-file-ops-fd-")
        val path = ops.resolvePath(dir, "blob.bin")
        try {
            val payload = byteArrayOf(1, 2, 3, 4, 5)
            ops.write(path, payload)
            val fd = ops.open(path, readOnly = true)
            try {
                assertEquals(5L, ops.size(fd))
            } finally {
                assertEquals(0, ops.close(fd))
            }
            assertEquals(-1L, ops.size(fd))
        } finally {
            ops.deleteRecursively(dir)
        }
    }

    @Test
    fun `listDir mkdirs isDir`() {
        val root = ops.createTempDir("jvm-file-ops-dir-")
        try {
            val child = ops.resolvePath(root, "sub")
            ops.mkdirs(child)
            assertTrue(ops.isDir(child))
            ops.write(ops.resolvePath(child, "a.txt"), "a")
            val names = ops.listDir(child)
            assertTrue(names.contains("a.txt"))
        } finally {
            ops.deleteRecursively(root)
        }
    }

    @Test
    fun `streamLines yields offset joins`() {
        val dir = ops.createTempDir("jvm-file-ops-lines-")
        val path = ops.resolvePath(dir, "lines.txt")
        try {
            ops.write(path, "one\ntwo\n")
            val lines = ops.streamLines(path, 8).toList()
            assertEquals(2, lines.size)
            assertEquals(0L, lines[0].a)
            assertEquals("one\n", lines[0].b.decodeToString())
            assertEquals("two\n", lines[1].b.decodeToString())
        } finally {
            ops.deleteRecursively(dir)
        }
    }

    @Test
    fun createDirectoryRefusesExistingEntriesWithoutChangingThem() {
        val directory = ops.createTempDir("jvm-file-ops-create-")
        val root = java.nio.file.Path.of(directory)
        val fresh = root.resolve("fresh")
        val existing = root.resolve("existing")
        val file = root.resolve("file")
        val live = root.resolve("live")
        val dangling = root.resolve("dangling")
        val absent = root.resolve("absent")
        try {
            ops.createDirectory(fresh.toString())
            assertTrue(java.nio.file.Files.isDirectory(fresh, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertTrue(ops.listDir(fresh.toString()).isEmpty())
            ops.createDirectory(existing.toString())
            val marker = existing.resolve("marker")
            ops.write(marker.toString(), "directory owner")
            ops.write(file.toString(), "file owner")
            java.nio.file.Files.createSymbolicLink(live, existing)
            java.nio.file.Files.createSymbolicLink(dangling, absent)

            assertFailsWith<java.nio.file.FileAlreadyExistsException> { ops.createDirectory(fresh.toString()) }
            assertFailsWith<java.nio.file.FileAlreadyExistsException> { ops.createDirectory(existing.toString()) }
            assertFailsWith<java.nio.file.FileAlreadyExistsException> { ops.createDirectory(file.toString()) }
            assertFailsWith<java.nio.file.FileAlreadyExistsException> { ops.createDirectory(live.toString()) }
            assertFailsWith<java.nio.file.FileAlreadyExistsException> { ops.createDirectory(dangling.toString()) }

            assertTrue(java.nio.file.Files.isDirectory(fresh, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertTrue(ops.listDir(fresh.toString()).isEmpty())
            assertEquals("directory owner", ops.readString(marker.toString()))
            assertTrue(java.nio.file.Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertEquals("file owner", ops.readString(file.toString()))
            assertTrue(java.nio.file.Files.isSymbolicLink(live))
            assertEquals(existing, java.nio.file.Files.readSymbolicLink(live))
            assertTrue(java.nio.file.Files.isSymbolicLink(dangling))
            assertEquals(absent, java.nio.file.Files.readSymbolicLink(dangling))
            assertFalse(java.nio.file.Files.exists(absent, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        } finally {
            ops.deleteRecursively(directory)
        }
    }

    @Test
    fun independentAdaptersHaveExactlyOneFreshDirectoryWinner(): Unit = runBlocking {
        val directory = ops.createTempDir("jvm-file-ops-contend-")
        val path = ops.resolvePath(directory, "scratch")
        val leftFiles: FileOperations = JvmFileOperations()
        val rightFiles: FileOperations = JvmFileOperations()
        val leftReady = CompletableDeferred<Unit>()
        val rightReady = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val left = async(Dispatchers.IO) {
            leftReady.complete(Unit)
            release.await()
            runCatching { leftFiles.createDirectory(path) }
        }
        val right = async(Dispatchers.IO) {
            rightReady.complete(Unit)
            release.await()
            runCatching { rightFiles.createDirectory(path) }
        }
        try {
            withTimeout(5_000) { leftReady.await(); rightReady.await() }
            assertFalse(left.isCompleted)
            assertFalse(right.isCompleted)
            release.complete(Unit)
            val (leftResult, rightResult) = withTimeout(5_000) { left.await() to right.await() }
            assertTrue(leftResult.isSuccess != rightResult.isSuccess,
                "Exactly one adapter must create the directory: left=$leftResult, right=$rightResult")
            val failure = if (leftResult.isFailure) leftResult.exceptionOrNull() else rightResult.exceptionOrNull()
            assertIs<java.nio.file.FileAlreadyExistsException>(failure)
            assertTrue(ops.isDir(path))
            assertTrue(ops.listDir(path).isEmpty())
        } finally {
            release.complete(Unit)
            withContext(NonCancellable) {
                left.cancelAndJoin()
                right.cancelAndJoin()
                ops.deleteRecursively(directory)
            }
        }
    }

}
