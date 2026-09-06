package borg.trikeshed.btrfs

import borg.trikeshed.job.ContentId
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
import kotlin.test.assertTrue

class BtrfsReflinkStoreTest {

    // The unit tests run against InMemoryFileOperations, which is not a real
    // filesystem at all; the btrfs guard is satisfied with an explicit stub so the
    // guard itself stays a REQUIRED constructor argument (no permissive default).
    private val BTRFS_PROBE = FilesystemTypeProbe { BtrfsReflinkStore.BTRFS }

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
