package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.common.mktemp
import borg.trikeshed.userspace.nio.channels.spi.ChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.ProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.ReactorOperations
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import borg.trikeshed.userspace.nio.spi.platformNioProviders
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeFileOperationsTest {

    private fun platformFiles(): FileOperations =
        platformNioProviders().filterIsInstance<FileOperations>().single()

    @Test
    fun platformProvidersExposeEveryNioCapabilityOnce() {
        val providers = platformNioProviders()

        assertEquals(5, providers.size)
        assertEquals(
            setOf(
                FileOperations.Key,
                SystemOperations.Key,
                ChannelOperations.Key,
                ReactorOperations.Key,
                ProcessOperations.Key,
            ),
            providers.map { it.key }.toSet(),
        )
    }

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
