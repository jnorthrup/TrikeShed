package borg.trikeshed.isam

import borg.trikeshed.io.Files
import kotlin.test.*

class FilesTest {

    @Test
    fun readAllLines_readsAllLinesFromFile() {
        val lines = Files.readAllLines("testfile.txt")
        assertEquals(listOf("line1", "line2", "line3"), lines)
    }

    @Test
    fun readAllBytes_readsAllBytesFromFile() {
        val bytes = Files.readAllBytes("testfile.txt")
        assertContentEquals("line1\nline2\nline3\n".encodeToByteArray(), bytes)
    }

    @Test
    fun readString_readsStringFromFile() {
        Files.write("testfile.txt", "file content")
        val content = Files.readString("testfile.txt")
        assertEquals("file content", content)
    }

    @Test
    fun write_writesBytesToFile() {
        Files.write("testfile.txt", "file content".encodeToByteArray())
        val bytes = Files.readAllBytes("testfile.txt")
        assertContentEquals("file content".encodeToByteArray(), bytes)
    }

    @Test
    fun write_writesLinesToFile() {
        Files.write("testfile.txt", listOf("line1", "line2", "line3"))
        val lines = Files.readAllLines("testfile.txt")
        assertEquals(listOf("line1", "line2", "line3"), lines)
    }

    @Test
    fun write_writesStringToFile() {
        Files.write("testfile.txt", "file content")
        val content = Files.readString("testfile.txt")
        assertEquals("file content", content)
    }

    @Test
    fun cwd_returnsCurrentWorkingDirectory() {
        val cwd = Files.cwd()
        assertTrue(cwd.isNotEmpty())
    }

    @Test
    fun exists_returnsTrueIfFileExists() {
        assertTrue(Files.exists("testfile.txt"))
    }

    @Test
    fun streamLines_streamsLinesFromFile() {
        val lines = Files.streamLines("testfile.txt").toList()
        assertEquals(3, lines.size)
    }

    @Test
    fun iterateLines_iteratesLinesFromFile() {
        val lines = Files.iterateLines("testfile.txt").toList()
        assertEquals(3, lines.size)
    }

    @Test
    fun readAllLines_throwsExceptionForNonExistentFile() {
        assertFailsWith< Exception> {
            Files.readAllLines("nonexistent.txt")
        }
    }

    @Test
    fun readAllBytes_throwsExceptionForNonExistentFile() {
        assertFailsWith< Exception> {
            Files.readAllBytes("nonexistent.txt")
        }
    }

    @Test
    fun readString_throwsExceptionForNonExistentFile() {
        assertFailsWith< Exception> {
            Files.readString("nonexistent.txt")
        }
    }

    @Test
    fun write_throwsExceptionForInvalidPath() {
        assertFailsWith<Exception> {
            Files.write("/invalid/path/testfile.txt", "content".encodeToByteArray())
        }
    }
}
