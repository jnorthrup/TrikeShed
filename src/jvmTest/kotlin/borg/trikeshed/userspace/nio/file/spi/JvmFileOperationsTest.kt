package borg.trikeshed.userspace.nio.file.spi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
