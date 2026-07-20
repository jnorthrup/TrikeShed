package borg.trikeshed.btrfs

import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
import borg.trikeshed.userspace.nio.channels.spi.ProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.ProcessResult
import borg.trikeshed.reflink.FixedBlockReflinkScanner
import borg.trikeshed.reflink.InMemoryReferenceCounter
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BtrfsReflinkStoreTest {
    
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
        val scanner = FixedBlockReflinkScanner()
        val refCounter = InMemoryReferenceCounter()
        
        val store = BtrfsReflinkStore(
            rootDir = "/btrfs/cas",
            fileOps = fileOps,
            processOps = processOps,
            scanner = scanner,
            refCounter = refCounter
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
        val scanner = FixedBlockReflinkScanner()
        val refCounter = InMemoryReferenceCounter()
        
        val store = BtrfsReflinkStore(
            rootDir = "/btrfs/cas",
            fileOps = fileOps,
            processOps = processOps,
            scanner = scanner,
            refCounter = refCounter
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
        val scanner = FixedBlockReflinkScanner()
        val refCounter = InMemoryReferenceCounter()
        
        val store = BtrfsReflinkStore(
            rootDir = "/btrfs/cas",
            fileOps = fileOps,
            processOps = processOps,
            scanner = scanner,
            refCounter = refCounter
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
