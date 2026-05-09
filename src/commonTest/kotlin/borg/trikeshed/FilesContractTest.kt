package borg.trikeshed

import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract test for the NIO FileOperations SPI.
 *
 * Verifies the in-memory implementation satisfies the core
 * create/read/write/delete/exists/list contract.
 */
class FilesContractTest {

    private val fs = InMemoryFileOperations()

    @Test
    fun `create and read file`() {
        fs.write("/test/a.txt", "hello".encodeToByteArray())
        assertTrue(fs.exists("/test/a.txt"))
        val bytes = fs.readAllBytes("/test/a.txt")
        assertEquals("hello", bytes.decodeToString())
    }

    @Test
    fun `read all lines`() {
        fs.write("/test/b.csv", "a,1\nb,2\nc,3\n".encodeToByteArray())
        val lines = fs.readAllLines("/test/b.csv")
        assertEquals(3, lines.size)
        assertEquals("a,1", lines[0])
        assertEquals("c,3", lines[2])
    }

    @Test
    fun `delete file`() {
        fs.write("/test/removable.txt", "data".encodeToByteArray())
        assertTrue(fs.exists("/test/removable.txt"))
        fs.deleteRecursively("/test/removable.txt")
        assertTrue(!fs.exists("/test/removable.txt"))
    }

    @Test
    fun `temp dir creation`() {
        val dir = fs.createTempDir("trike")
        assertTrue(dir.isNotEmpty())
        fs.write("$dir/test.txt", "ok".encodeToByteArray())
        assertTrue(fs.exists("$dir/test.txt"))
        fs.deleteRecursively(dir)
    }

    @Test
    fun `list directory`() {
        fs.write("/dir/a.txt", "a".encodeToByteArray())
        fs.write("/dir/b.txt", "b".encodeToByteArray())
        val entries = fs.listDir("/dir")
        assertTrue(entries.contains("a.txt"))
        assertTrue(entries.contains("b.txt"))
    }

    @Test
    fun `non-existent file returns false`() {
        assertTrue(!fs.exists("/no/such/file.txt"))
    }
}
