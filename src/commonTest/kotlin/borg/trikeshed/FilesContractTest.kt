package borg.trikeshed

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilesContractTest {
    @Test
    fun readWriteRoundTripsAcrossPlatforms() {
        val path = Files.createTempDir("trikeshed")
        try {
            val content = "alpha\nbeta\n"
            Files.write(path, content)

            assertEquals(content, Files.readString(path))
            assertTrue(Files.exists(path))
        } finally {
            Files.deleteRecursively(path)
        }
    }

    @Test
    fun streamLinesPreservesOffsetsAndLineBytes() {
        val path = Files.createTempDir("trikeshed")
        try {
            val content = "alpha\nbravo\ncharlie\n"
            Files.write(path, content)

            val lines = Files.streamLines(path, bufsize = 4).toList()

            assertContentEquals(listOf(0L, 6L, 12L), lines.map { it.a })
            assertContentEquals(listOf("alpha\n", "bravo\n", "charlie\n"), lines.map { it.b.decodeToString() })
        } finally {
            Files.deleteRecursively(path)
        }
    }

    @Test
    fun streamLinesPreservesWhitespaceOnlyFinalTail() {
        val path = Files.createTempDir("trikeshed")
        try {
            val content = "alpha\n   "
            Files.write(path, content)

            val lines = Files.streamLines(path, bufsize = 2).toList()

            assertContentEquals(listOf(0L, 6L), lines.map { it.a })
            assertContentEquals(listOf("alpha\n", "   "), lines.map { it.b.decodeToString() })
        } finally {
            Files.deleteRecursively(path)
        }
    }

    @Test
    fun seekFileBufferReadsBytesWrittenByFilesApi() {
        val path = Files.createTempDir("trikeshed")
        try {
            Files.write(path, "hello")

            SeekFileBuffer(path).use { buffer ->
                assertEquals(5L, buffer.size())
                assertEquals('h'.code.toByte(), buffer.get(0L))
                assertEquals('e'.code.toByte(), buffer.get(1L))
            }
        } finally {
            Files.deleteRecursively(path)
        }
    }

    @Test
    fun mkdirAndRmRoundTripNestedTree() {
        val seed = Files.createTempDir("trikeshed")
        Files.deleteRecursively(seed)
        val dir = "$seed-dir"
        val nested = "$dir/nested"
        val file = "$nested/tree.txt"

        try {
            Files.mkdirs(nested)
            assertTrue(Files.exists(nested))
            Files.write(file, "branch")

            assertEquals("branch", Files.readString(file))
            assertTrue(Files.exists(file))

            Files.deleteRecursively(file)
            assertTrue(true)
            assertFalse(Files.exists(file))
        } finally {
            Files.deleteRecursively(file)
            Files.deleteRecursively(dir)
        }
    }
}
