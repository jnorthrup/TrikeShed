package borg.trikeshed.htx.client

import borg.trikeshed.userspace.nio.file.Files
import borg.trikeshed.userspace.nio.spi.platformNioProviders
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * JVM integration test for Files + line streaming.
 * Uses borg.trikeshed.userspace.nio.file.Files (backed by java.io.File on JVM).
 * Must call platformNioProviders() before tests to initialize fileOperations.
 */
class StreamLinesTinyTest {
    companion object {
        init {
            // Initialize platform providers (sets fileOperations, etc.)
            // Must happen once per test class before any test uses Files.*
            platformNioProviders()
        }
    }

    @Test
    fun testStreamLinesTiny() {
        val lines = (0 until 20).map {
            (0 until (2..75).random()).map { ('a'..'z').random() }.joinToString("")
        }
        assertEquals(20, lines.size)

        val baseDir = "/tmp/StreamLinesTinyTest-${System.currentTimeMillis()}"
        val resolve = "$baseDir/test.txt"

        Files.write(resolve, lines)
        val readLines = Files.lines(resolve).toList()
        assertEquals(20, readLines.size, "should have 20 lines")
        assertContentEquals(readLines, lines)
    }

    @Test
    fun testLineFragmentsEmitsOffsetAndBytes() {
        val lines = listOf("a", "bb", "ccc", "dddd", "eeeee")
        // Byte layout: "a\nbb\nccc\ndddd\neeeee"
        // Offsets:    0   2   5    9     14

        val baseDir = "/tmp/StreamLinesTinyTest-fragments-${System.currentTimeMillis()}"
        val resolve = "$baseDir/fragments.txt"

        Files.write(resolve, lines)
        val fragments = Files.fragments(resolve).toList()
        assertEquals(5, fragments.size, "should have 5 fragments")

        val expectedOffsets = listOf(0L, 2L, 5L, 9L, 14L)
        assertContentEquals(fragments.map { it.a }, expectedOffsets)

        val fragmentLines = fragments.map { String(it.b, Charsets.UTF_8) }
        assertContentEquals(fragmentLines, lines)
    }
}
