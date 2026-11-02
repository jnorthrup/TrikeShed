package borg.trikeshed.job

import borg.trikeshed.userspace.nio.file.spi.BoundedReadLimitException
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class PackCasStoreIntegrityTest {
    @Test fun putRefRejectsForgedIdentityWithoutAppending() {
        val files = JvmFileOperations()
        val root = files.createTempDir("pack-ref-integrity-")
        try {
            val path = files.resolvePath(root, "source")
            val bytes = "first content".encodeToByteArray()
            val forged = ContentId.of("other content".encodeToByteArray())
            files.write(path, bytes)
            PackCasStore(files, root).use { store ->
                val before = Files.size(Path.of(root, "pack"))
                assertFailsWith<IllegalArgumentException> { store.putRef(path, bytes, forged) }
                assertFalse(store.holds(forged))
                assertEquals(before, Files.size(Path.of(root, "pack")))
                val actual = ContentId.of(bytes)
                assertEquals(actual, store.putRef(path, bytes, actual))
                assertContentEquals(bytes, store.getBounded(actual, bytes.size))
                assertFailsWith<BoundedReadLimitException> { store.getBounded(actual, bytes.size - 1) }
                files.deleteRecursively(path)
                assertNull(store.getBounded(actual, 0), "missing reference must not be reported as oversized")
                store.sync()
            }
        } finally { files.deleteRecursively(root) }
    }

    @Test fun verifiedReadRejectsChangedPathEvenIfSizeAndTimestampMatch() {
        val files = JvmFileOperations()
        val root = files.createTempDir("pack-ref-changed-")
        try {
            val path = files.resolvePath(root, "source")
            val first = "first content".encodeToByteArray()
            val other = "other content".encodeToByteArray()
            assertEquals(first.size, other.size)
            files.write(path, first)
            val stamp = Files.getLastModifiedTime(Path.of(path))
            val id = PackCasStore(files, root).use { store ->
                val id = store.putFile(path)
                files.write(path, other)
                Files.setLastModifiedTime(Path.of(path), stamp)
                assertEquals(stamp, Files.getLastModifiedTime(Path.of(path)))
                assertFailsWith<IllegalStateException> { store.getBounded(id, first.size) }
                store.sync()
                id
            }
            PackCasStore(files, root).use { assertFailsWith<IllegalStateException> { it.getBounded(id, first.size) } }
        } finally { files.deleteRecursively(root) }
    }

    @Test fun verifiedReadRejectsMismatchAlreadyPresentAtRegistration() {
        val files = JvmFileOperations()
        val root = files.createTempDir("pack-ref-path-mismatch-")
        try {
            val path = files.resolvePath(root, "source")
            val expected = "first content".encodeToByteArray()
            files.write(path, "other content".encodeToByteArray())
            PackCasStore(files, root).use { store ->
                val id = store.putRef(path, expected, ContentId.of(expected))
                assertFailsWith<IllegalStateException> { store.getBounded(id, expected.size) }
            }
        } finally { files.deleteRecursively(root) }
    }

    @Test fun verifiedReadRejectsChangedBlobAndBoundsAllocation() {
        val files = JvmFileOperations()
        val root = files.createTempDir("pack-blob-corrupt-")
        try {
            val id = PackCasStore(files, root).use { store ->
                val id = store.put("payload".encodeToByteArray())
                assertContentEquals("payload".encodeToByteArray(), store.getBounded(id, 7))
                assertFailsWith<BoundedReadLimitException> { store.getBounded(id, 6) }
                store.sync()
                id
            }
            RandomAccessFile(files.resolvePath(root, "pack"), "rw").use { file ->
                file.seek(PackCasStore.HEADER.toLong())
                file.writeByte('X'.code)
            }
            PackCasStore(files, root).use { store -> assertFailsWith<IllegalStateException> { store.getBounded(id, 7) } }
        } finally { files.deleteRecursively(root) }
    }

    @Test fun referenceVerificationFailsClosedWithoutBoundedFileReader() {
        val files = JvmFileOperations()
        val withoutBoundedRead = object : FileOperations by files {}
        val root = files.createTempDir("pack-ref-unbounded-")
        try {
            val path = files.resolvePath(root, "source")
            val bytes = "content".encodeToByteArray()
            val id = ContentId.of(bytes)
            files.write(path, bytes)
            PackCasStore(withoutBoundedRead, root).use { store ->
                store.putRef(path, bytes, id)
                assertFailsWith<UnsupportedOperationException> { store.getBounded(id, bytes.size) }
            }
        } finally { files.deleteRecursively(root) }
    }
}
