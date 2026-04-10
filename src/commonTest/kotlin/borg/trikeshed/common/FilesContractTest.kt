package borg.trikeshed.common

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilesContractTest {
    @Test
    fun readWriteRoundTripsAcrossPlatforms() {
        val path = mktemp()
        try {
            val content = "alpha\nbeta\n"
            Files.write(path, content)

            assertEquals(content, Files.readString(path))
            assertTrue(Files.exists(path))
        } finally {
            rm(path)
        }
    }

    @Test
    fun streamLinesPreservesOffsetsAndLineBytes() {
        val path = mktemp()
        try {
            val content = "alpha\nbravo\ncharlie\n"
            Files.write(path, content)

            val lines = Files.streamLines(path, bufsize = 4).toList()

            assertContentEquals(listOf(0L, 6L, 12L), lines.map { it.a })
            assertContentEquals(listOf("alpha\n", "bravo\n", "charlie\n"), lines.map { it.b.decodeToString() })
        } finally {
            rm(path)
        }
    }

    @Test
    fun streamLinesPreservesWhitespaceOnlyFinalTail() {
        val path = mktemp()
        try {
            val content = "alpha\n   "
            Files.write(path, content)

            val lines = Files.streamLines(path, bufsize = 2).toList()

            assertContentEquals(listOf(0L, 6L), lines.map { it.a })
            assertContentEquals(listOf("alpha\n", "   "), lines.map { it.b.decodeToString() })
        } finally {
            rm(path)
        }
    }

    @Test
    fun seekFileBufferReadsBytesWrittenByFilesApi() {
        val path = mktemp()
        try {
            Files.write(path, "hello")

            SeekFileBuffer(path).use { buffer ->
                assertEquals(5L, buffer.size())
                assertEquals('h'.code.toByte(), buffer.get(0L))
                assertEquals('e'.code.toByte(), buffer.get(1L))
            }
        } finally {
            rm(path)
        }
    }

    @Test
    fun mkdirAndRmRoundTripNestedTree() {
        val seed = mktemp()
        rm(seed)
        val dir = "$seed-dir"
        val nested = "$dir/nested"
        val file = "$nested/tree.txt"

        try {
            assertTrue(mkdir(nested))
            Files.write(file, "branch")

            assertEquals("branch", Files.readString(file))
            assertTrue(Files.exists(file))

            assertTrue(rm(file))
            assertFalse(Files.exists(file))
        } finally {
            rm(file)
            rm(dir)
        }
    }
}
