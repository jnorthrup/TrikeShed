@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.common.mktemp
import borg.trikeshed.userspace.nio.spi.platformNioProviders
import kotlinx.cinterop.*
import platform.posix.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFails

class NativeFileOperationsTest {

    @Test
    fun pathResolutionNormalizesDirectoryAliasesBeforeSiblingDerivation() {
        val files = platformFiles()
        assertEquals(files.cwd(), files.resolvePath())
        assertEquals(files.cwd(), files.resolvePath("", "."))
        assertEquals("/var/dense", files.resolvePath("/var//dense/"))
        assertEquals("/var/dense", files.resolvePath("/var/dense/."))
        assertEquals("/var/dense", files.resolvePath("/var/old", "..", "/dense/"))
        assertEquals("/dense", files.resolvePath("/../../dense"))
        assertEquals("/", files.resolvePath("/a/../.."))
        assertEquals("${files.cwd()}/dense", files.resolvePath("", "dense/./"))
        assertFails { files.resolvePath("/var/dense\u0000hidden") }
    }

    @Test
    fun canonicalPathsResolveExistingAncestorsAndRejectUnresolvableAliases() {
        val files = platformFiles()
        val directory = files.canonicalPath(files.createTempDir("trikeshed-canonical-path"))
        val alias = "$directory/alias"
        val dangling = "$directory/dangling"
        val loop = "$directory/loop"
        try {
            files.mkdirs("$directory/physical/nested")
            assertEquals(0, symlink("$directory/physical/nested", alias))
            assertEquals("$directory/physical/nested", files.canonicalPath(alias))
            assertEquals("$directory/physical/nested/new/child", files.canonicalPath("$alias/new/child"))
            assertEquals("$directory/physical/nested/new", files.canonicalPath("$alias/new/./"))
            assertEquals("$directory/physical", files.canonicalPath("$alias/.."))
            assertFails { files.canonicalPath("$alias/missing/..") }
            assertEquals(0, symlink("$directory/missing", dangling))
            assertFails { files.canonicalPath("$dangling/child") }
            assertEquals(0, symlink(loop, loop))
            assertFails { files.canonicalPath("$loop/child") }
            files.write("$directory/file", "retained")
            assertFails { files.canonicalPath("$directory/file/child") }
        } finally {
            unlink(alias)
            unlink(dangling)
            unlink(loop)
            files.deleteRecursively(directory)
        }
    }

    @Test
    fun atomicPublicationReplacesTheInodeAndCreatesCasParents() {
        val files = platformFiles()
        val directory = files.createTempDir("trikeshed-publication")
        try {
            val path = "$directory/nested/sha256/blob"
            val before = byteArrayOf(1, 2, 3)
            val after = ByteArray(128 * 1024) { (it % 251).toByte() }
            files.writeAtomically(path, before)
            val retained = files.open(path)
            assertTrue(retained >= 0)
            try {
                files.writeAtomically(path, after)
                assertEquals(before.size.toLong(), files.size(retained), "publication must not truncate an existing reader's inode")
                assertContentEquals(after, files.readAllBytes(path))
                files.writeAtomically(path, byteArrayOf())
                assertContentEquals(byteArrayOf(), files.readAllBytes(path))
            } finally { assertEquals(0, files.close(retained)) }
            assertEquals(listOf("blob"), files.listDir("$directory/nested/sha256"))
            assertEquals(StorageDurability.DURABLE, files.durability)
        } finally { files.deleteRecursively(directory) }
    }

    @Test
    fun failedAtomicPublicationPreservesTheDestinationAndRemovesItsTemporary() {
        val files = platformFiles()
        val directory = files.createTempDir("trikeshed-publication-failure")
        try {
            files.mkdirs("$directory/target")
            files.write("$directory/target/retained", "original")
            assertFails { files.writeAtomically("$directory/target", byteArrayOf(1)) }
            assertEquals("original", files.readString("$directory/target/retained"))
            assertEquals(listOf("target"), files.listDir(directory))
            files.write("$directory/file", "original")
            assertFails { files.mkdirs("$directory/file/child") }
            assertEquals("original", files.readString("$directory/file"))
        } finally { files.deleteRecursively(directory) }
    }

    @Test
    fun leasesExcludeOtherOwnersAndAliasesUntilAnIdempotentClose() {
        val files = platformFiles()
        val other = PosixFileOperations()
        val directory = files.createTempDir("trikeshed-lease")
        try {
            val path = "$directory/lease"
            val first = files.acquireExclusiveLease(path)
            try {
                assertEquals(0, platform.posix.link(path, "$directory/alias"))
                repeat(2) {
                    assertFails { files.acquireExclusiveLease(path).close() }
                    assertFails { other.acquireExclusiveLease("$directory/alias").close() }
                }
            } finally { first.close() }
            other.acquireExclusiveLease(path).use {
                first.close() // must not close a descriptor since reused by the new owner
                assertFails { files.acquireExclusiveLease(path).close() }
            }
            files.acquireExclusiveLease("$directory/alias").close()
        } finally { files.deleteRecursively(directory) }
    }

    @Test
    fun leasesConflictWithTraditionalRecordLocksWithoutAnUnrelatedCloseReleasingThem() = memScoped {
        val files = platformFiles()
        val directory = files.createTempDir("trikeshed-record-lock")
        val path = "$directory/lease"
        val record = alloc<flock>().apply {
            l_type = F_WRLCK.convert()
            l_whence = SEEK_SET.convert()
            l_start = 0
            l_len = 0
            l_pid = 0
        }
        try {
            files.acquireExclusiveLease(path).use {
                val other = files.open(path, readOnly = false)
                assertTrue(other >= 0)
                try {
                    assertEquals(-1, fcntl(other, F_SETLK, record.ptr))
                    assertTrue(errno == EAGAIN || errno == EACCES)
                } finally { assertEquals(0, files.close(other)) }
                assertFails { files.acquireExclusiveLease(path).close() }
            }
            val other = files.open(path, readOnly = false)
            assertTrue(other >= 0)
            try {
                assertEquals(0, fcntl(other, F_SETLK, record.ptr))
                assertFails { files.acquireExclusiveLease(path).close() }
            } finally { assertEquals(0, files.close(other)) }
            files.acquireExclusiveLease(path).close()
        } finally { files.deleteRecursively(directory) }
    }

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
