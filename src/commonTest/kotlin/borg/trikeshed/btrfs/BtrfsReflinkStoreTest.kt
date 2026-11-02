package borg.trikeshed.btrfs

import borg.trikeshed.job.ContentId
import borg.trikeshed.cas.CasPaths
import borg.trikeshed.userspace.nio.file.spi.BoundedFileReader
import borg.trikeshed.userspace.nio.file.spi.BoundedReadLimitException
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
import borg.trikeshed.userspace.nio.channels.spi.ProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.ProcessResult
import borg.trikeshed.reflink.InMemoryReferenceCounter
import borg.trikeshed.util.oroboros.FileCasStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BtrfsReflinkStoreTest {

    // The unit tests run against InMemoryFileOperations, which is not a real
    // filesystem at all; the btrfs guard is satisfied with an explicit stub so the
    // guard itself stays a REQUIRED constructor argument (no permissive default).
    private val BTRFS_PROBE = FilesystemTypeProbe { BtrfsReflinkStore.BTRFS }

    @Test
    fun boundedReadsVerifyContentAndKeepCanonicalPrecedence() {
        val backing = InMemoryFileOperations()
        var boundedReads = 0
        val files = object : FileOperations by backing, BoundedFileReader {
            override fun readAllBytes(filename: String): ByteArray = error("unbounded CAS read")
            override fun readBounded(path: String, maxBytes: Int): ByteArray {
                boundedReads++
                val bytes = backing.readAllBytes(path)
                if (bytes.size > maxBytes) throw BoundedReadLimitException(maxBytes)
                return bytes.copyOf()
            }
        }
        val store = BtrfsReflinkStore("/btrfs/cas", files, MockProcessOperations(), InMemoryReferenceCounter(), BTRFS_PROBE)
        val bytes = "source".encodeToByteArray()
        val cid = ContentId.of(bytes)
        val canonical = backing.resolvePath("/btrfs/cas", CasPaths.blob(cid))
        val legacy = backing.resolvePath("/btrfs/cas", CasPaths.legacyBlob(cid))
        backing.write(legacy, bytes)
        assertContentEquals(bytes, store.getBounded(cid, bytes.size))
        assertFailsWith<BoundedReadLimitException> { store.getBounded(cid, bytes.size - 1) }
        backing.write(canonical, "broken".encodeToByteArray())
        assertFailsWith<IllegalStateException> { store.getBounded(cid, bytes.size) }
        backing.write(canonical, bytes)
        assertContentEquals(bytes, store.getBounded(cid, bytes.size))
        assertNull(store.getBounded(ContentId.of("missing".encodeToByteArray()), 64))
        assertFailsWith<IllegalArgumentException> { store.getBounded(cid, -1) }
        assertFailsWith<IllegalArgumentException> { store.getBounded(cid, Int.MAX_VALUE) }
        val empty = ContentId.of(ByteArray(0))
        backing.write(backing.resolvePath("/btrfs/cas", CasPaths.blob(empty)), ByteArray(0))
        assertContentEquals(ByteArray(0), store.getBounded(empty, 0))
        assertEquals(5, boundedReads)
    }

    @Test
    fun boundedReadsRefuseBackendsWithoutTheCapability() {
        val store = BtrfsReflinkStore("/btrfs/cas", InMemoryFileOperations(), MockProcessOperations(),
            InMemoryReferenceCounter(), BTRFS_PROBE)
        assertFailsWith<UnsupportedOperationException> { store.getBounded(ContentId.of(ByteArray(0)), 0) }
    }

    @Test
    fun boundedReadsRejectAnOversizedReturnFromABrokenBackend() {
        val backing = InMemoryFileOperations()
        val files = object : FileOperations by backing, BoundedFileReader {
            override fun readBounded(path: String, maxBytes: Int): ByteArray = backing.readAllBytes(path)
        }
        val store = BtrfsReflinkStore("/btrfs/cas", files, MockProcessOperations(), InMemoryReferenceCounter(), BTRFS_PROBE)
        val bytes = "source".encodeToByteArray()
        val cid = ContentId.of(bytes)
        backing.write(backing.resolvePath("/btrfs/cas", CasPaths.blob(cid)), bytes)
        assertFailsWith<BoundedReadLimitException> { store.getBounded(cid, 1) }
    }

    @Test
    fun testFourHexLayoutAndLegacyInterop() = runTest {
        val fileOps = InMemoryFileOperations()
        val processOps = MockProcessOperations()
        val store = BtrfsReflinkStore(
            "/btrfs/cas", fileOps, processOps, InMemoryReferenceCounter(), BTRFS_PROBE
        )
        val bytes = ByteArray(0)
        val cid = ContentId.of(bytes)
        val legacy = "/btrfs/cas/sha256/e3/b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val current = "/btrfs/cas/sha256/e/3/b/0/c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        fileOps.write(legacy, bytes)
        assertContentEquals(bytes, store.get(cid))
        assertTrue(store.reflinkCopy(cid, "/btrfs/legacy-copy"))
        assertEquals(listOf("--reflink=always", legacy, "/btrfs/legacy-copy"), processOps.lastArgs)

        assertEquals(cid, store.put(bytes))
        assertEquals(current, store.pathFor(cid))
        assertTrue(fileOps.exists(current))
        val fileStore = FileCasStore(fileOps, "/btrfs/cas")
        assertContentEquals(bytes, fileStore.get(cid))
        val other = "written by FileCasStore".encodeToByteArray()
        assertContentEquals(other, store.get(fileStore.put(other)))
        assertTrue(store.reflinkCopy(cid, "/btrfs/new-copy"))
        assertEquals(listOf("--reflink=always", current, "/btrfs/new-copy"), processOps.lastArgs)

        fileOps.write(current, "corrupt".encodeToByteArray())
        assertFailsWith<IllegalStateException> { store.get(cid) }
    }

    
    // Mock ProcessOperations
    class MockProcessOperations : ProcessOperations {
        var lastCommand: String? = null
        var lastArgs: List<String>? = null
        override suspend fun exec(
            command: String,
            args: List<String>,
            stdin: ByteArray?,
            env: Map<String, String>
        ): ProcessResult {
            lastCommand = command
            lastArgs = args
            return ProcessResult(0, ByteArray(0), ByteArray(0))
        }
    }

    @Test
    fun testStoreAndRetrieve() = runTest {
        val fileOps = InMemoryFileOperations()
        val processOps = MockProcessOperations()
        val refCounter = InMemoryReferenceCounter()
        
        val store = BtrfsReflinkStore(
            rootDir = "/btrfs/cas",
            fileOps = fileOps,
            processOps = processOps,
            refCounter = refCounter,
            fsProbe = BTRFS_PROBE
        )
        
        val data = "Hello, Btrfs!".encodeToByteArray()
        val cid = store.put(data)
        
        assertEquals(1L, refCounter.getCount(cid))
        
        val retrieved = store.get(cid)
        assertNotNull(retrieved)
        assertTrue(data.contentEquals(retrieved))
    }
    
    @Test
    fun testDedupIncrement() = runTest {
        val fileOps = InMemoryFileOperations()
        val processOps = MockProcessOperations()
        val refCounter = InMemoryReferenceCounter()
        
        val store = BtrfsReflinkStore(
            rootDir = "/btrfs/cas",
            fileOps = fileOps,
            processOps = processOps,
            refCounter = refCounter,
            fsProbe = BTRFS_PROBE
        )
        
        val data = "Duplicate Me".encodeToByteArray()
        val cid1 = store.put(data)
        assertEquals(1L, refCounter.getCount(cid1))
        
        val cid2 = store.put(data) // Should trigger dedup code path (increment reference)
        assertEquals(cid1, cid2)
        assertEquals(2L, refCounter.getCount(cid1))
    }
    
    @Test
    fun testReflinkCopy() = runTest {
        val fileOps = InMemoryFileOperations()
        val processOps = MockProcessOperations()
        val refCounter = InMemoryReferenceCounter()
        
        val store = BtrfsReflinkStore(
            rootDir = "/btrfs/cas",
            fileOps = fileOps,
            processOps = processOps,
            refCounter = refCounter,
            fsProbe = BTRFS_PROBE
        )
        
        val data = "Reflink This".encodeToByteArray()
        val cid = store.put(data)
        
        val dstPath = "/btrfs/dst/copied_file.txt"
        val success = store.reflinkCopy(cid, dstPath)
        assertTrue(success)
        
        assertEquals("cp", processOps.lastCommand)
        assertNotNull(processOps.lastArgs)
        assertTrue(processOps.lastArgs!!.contains("--reflink=always"))
    }
}
