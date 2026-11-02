package borg.trikeshed.job

import borg.trikeshed.cas.CasPaths
import borg.trikeshed.btrfs.BtrfsReflinkStore
import borg.trikeshed.btrfs.FilesystemTypeProbe
import borg.trikeshed.reflink.InMemoryReferenceCounter
import borg.trikeshed.userspace.nio.channels.spi.JvmProcessOperations
import borg.trikeshed.userspace.nio.file.spi.*
import borg.trikeshed.util.oroboros.FileCasStore
import java.nio.file.Files
import kotlin.test.*

class BoundedCasReaderTest {
    @Test
    fun reflinkCasUsesBoundedJvmReadsAndVerifiesBothLayouts() {
        val root = Files.createTempDirectory("bounded-btrfs-cas-")
        try {
            val files = JvmFileOperations()
            val cas = BtrfsReflinkStore(root.toString(), files, JvmProcessOperations(),
                InMemoryReferenceCounter(), FilesystemTypeProbe { "btrfs" })
            val bytes = "quoted source".encodeToByteArray()
            val id = cas.put(bytes)
            assertContentEquals(bytes, cas.getBounded(id, bytes.size))
            assertFailsWith<BoundedReadLimitException> { cas.getBounded(id, bytes.size - 1) }
            val current = root.resolve(CasPaths.blob(id))
            val legacy = root.resolve(CasPaths.legacyBlob(id))
            Files.createDirectories(legacy.parent)
            Files.move(current, legacy)
            assertContentEquals(bytes, cas.getBounded(id, bytes.size))
            Files.createDirectories(current.parent)
            Files.write(current, "tampered data".encodeToByteArray())
            assertFailsWith<IllegalStateException> { cas.getBounded(id, 64) }
            assertNull(cas.getBounded(ContentId.of("missing".encodeToByteArray()), 32))
            val empty = cas.put(ByteArray(0))
            assertContentEquals(ByteArray(0), cas.getBounded(empty, 0))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun fileCasEnforcesBoundAndVerifiesDigest() {
        val root = Files.createTempDirectory("bounded-cas-")
        try {
            val files = JvmFileOperations()
            val cas = FileCasStore(files, root.toString())
            val bytes = "quoted source".encodeToByteArray()
            val id = cas.put(bytes)
            val emptyId = cas.put(byteArrayOf())

            assertContentEquals(bytes, cas.getBounded(id, bytes.size))
            assertContentEquals(byteArrayOf(), cas.getBounded(emptyId, 0))
            assertFailsWith<BoundedReadLimitException> { cas.getBounded(id, bytes.size - 1) }
            assertFailsWith<BoundedReadLimitException> { cas.getBounded(id, 0) }
            assertNull(cas.getBounded(ContentId.of("missing".encodeToByteArray()), 32))
            assertFailsWith<IllegalArgumentException> { cas.getBounded(id, -1) }

            val blob = root.resolve(CasPaths.blob(id))
            Files.write(blob, "tampered data".encodeToByteArray())
            assertFailsWith<IllegalStateException> { cas.getBounded(id, 32) }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun unboundedBackendIsNotSilentlyUsed() {
        val files = InMemoryFileOperations()
        val cas = FileCasStore(files, "/cas")
        val id = cas.put("source".encodeToByteArray())
        assertFailsWith<UnsupportedOperationException> { cas.getBounded(id, 1024) }
        assertFalse(CasStore.inMemory() is BoundedCasReader)
    }

    @Test
    fun brokenBoundedReaderCannotReturnAnOversizedSuccess() {
        val files = InMemoryFileOperations()
        val violating = object : FileOperations by files, BoundedFileReader {
            override fun readBounded(path: String, maxBytes: Int): ByteArray = files.readAllBytes(path)
        }
        val cas = FileCasStore(violating, "/cas")
        val id = cas.put("source".encodeToByteArray())
        assertFailsWith<BoundedReadLimitException> { cas.getBounded(id, 1) }
    }
}
