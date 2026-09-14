@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.common.mktemp
import borg.trikeshed.userspace.nio.spi.platformNioProviders
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import platform.posix.timeval
import platform.posix.utimes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeFileOperationsTest {

    @Test
    fun lastModifiedPreservesMilliseconds() = memScoped {
        val files = platformFiles()
        val directory = files.createTempDir("trikeshed-mtime")
        val path = files.resolvePath(directory, "mtime")
        try {
            files.write(path, byteArrayOf(1))
            val times = allocArray<timeval>(2)
            for (index in 0..1) {
                times[index].tv_sec = 1_700_000_000L.convert()
                times[index].tv_usec = 123_456L.convert()
            }
            assertEquals(0, utimes(path, times))
            assertEquals(1_700_000_000_123L, files.lastModified(path))
            assertEquals(1_700_000_000_123L, borg.trikeshed.common.Files.lastModified(path))
            assertEquals(0L, files.lastModified(files.resolvePath(directory, "missing")))
        } finally {
            files.deleteRecursively(directory)
        }
    }

    private fun platformFiles(): FileOperations =
        platformNioProviders().filterIsInstance<FileOperations>().single()

    @Test
    fun createTempDirCreatesAnExistingDirectory() {
        val files = platformFiles()
        val directory = files.createTempDir("trikeshed-native")
        try {
            assertTrue(files.exists(directory))
            assertTrue(files.isDir(directory))
        } finally {
            files.deleteRecursively(directory)
        }
    }

    @Test
    fun fileDescriptorAndByteRoundTripUsesThePlatformProvider() {
        val files = platformFiles()
        val directory = mktemp()
        val path = files.resolvePath(directory, "bytes.bin")
        val payload = byteArrayOf(0, 1, 2, 127, -1)
        try {
            files.write(path, payload)
            assertTrue(files.exists(path))
            assertTrue(files.isFile(path))
            assertContentEquals(payload, files.readAllBytes(path))

            val fd = files.open(path, readOnly = true)
            assertTrue(fd >= 0)
            assertEquals(payload.size.toLong(), files.size(fd))
            assertEquals(0, files.close(fd))
            assertEquals(-1L, files.size(fd))
        } finally {
            files.deleteRecursively(directory)
        }
        assertFalse(files.exists(directory))
    }

    @Test
    fun streamLinesPreservesByteOffsets() {
        val files = platformFiles()
        val directory = mktemp()
        val path = files.resolvePath(directory, "lines.txt")
        try {
            files.write(path, "one\ntwo\nlast")
            val lines = files.streamLines(path, bufsize = 3).toList()

            assertEquals(listOf(0L, 4L, 8L), lines.map { it.a })
            assertEquals(listOf("one\n", "two\n", "last"), lines.map { it.b.decodeToString() })
        } finally {
            files.deleteRecursively(directory)
        }
    }
}
