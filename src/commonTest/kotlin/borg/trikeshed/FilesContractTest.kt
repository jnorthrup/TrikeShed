package borg.trikeshed

import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilesContractTest {
    // 16a — create read delete round trips
    @Test
    fun `create read delete round trips`() {
        val fs = InMemoryFileOperations()
        val path = "/dir/test.txt"
        val content = "hello trike"

        // write
        fs.write(path, content)
        assertTrue(fs.exists(path))
        assertTrue(fs.isFile(path))

        // read
        assertEquals(content, fs.readString(path))
        assertEquals(listOf("hello trike"), fs.readAllLines(path))

        // delete
        fs.deleteRecursively(path)
        assertFalse(fs.exists(path))
    }

    // 16b — listDir returns entries
    @Test
    fun `listDir returns entries`() {
        val fs = InMemoryFileOperations()
        fs.write("/dir/a.txt", "a")
        fs.write("/dir/b.txt", "b")
        fs.write("/other/c.txt", "c")

        val entries = fs.listDir("/dir")
        assertTrue(entries.contains("a.txt"))
        assertTrue(entries.contains("b.txt"))
        assertFalse(entries.contains("c.txt"))
    }
}
