package borg.trikeshed.util.oroboros

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MockFileOperations : FileOperations {
    val memoryFiles = mutableMapOf<String, ByteArray>()
    val directories = mutableSetOf<String>()

    override fun open(path: String, readOnly: Boolean): Int = 0
    override fun readAllLines(filename: String): List<String> = emptyList()
    override fun readAllBytes(filename: String): ByteArray = memoryFiles[filename] ?: throw IllegalArgumentException("File not found")
    override fun readString(filename: String): String = ""
    override fun write(filename: String, bytes: ByteArray) { memoryFiles[filename] = bytes }
    override fun write(filename: String, lines: List<String>) {}
    override fun write(filename: String, string: String) {}
    override fun cwd(): String = "/"
    override fun exists(filename: String): Boolean = memoryFiles.containsKey(filename) || directories.contains(filename)
    override fun streamLines(fileName: String, bufsize: Int): Sequence<Join<Long, ByteArray>> = emptySequence()
    override fun iterateLines(fileName: String, bufsize: Int): Iterable<Join<Long, Series<Byte>>> = emptyList()
    override fun listDir(path: String): List<String> = emptyList()
    override fun isDir(path: String): Boolean = directories.contains(path)
    override fun isFile(path: String): Boolean = memoryFiles.containsKey(path)
    override fun mkdirs(path: String) { directories.add(path) }
    override fun deleteRecursively(path: String) {}
    override fun resolvePath(vararg parts: String): String = parts.joinToString("/")
    override fun readZip(path: String): List<Pair<String, ByteArray>> = emptyList()
    override fun createTempDir(prefix: String): String = prefix
    override fun close(fd: Int): Int = 0
    override fun size(fd: Int): Long = 0
}

class Sha2CasBusTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testKnownSha256Vector() = runTest {
        val fileOps = MockFileOperations()
        val fileCasStore = FileCasStore(fileOps, "casRoot")
        val bus = Sha2CasBus(fileCasStore)

        val emptyBytes = ByteArray(0)
        val cid = bus.put(emptyBytes)

        assertEquals("sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", cid.value)
        assertTrue(fileOps.exists("casRoot/sha256/e3/b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"))

        val events = bus.subscribe()
        val eventBytes = events.receive()
        assertEquals(0, eventBytes.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testIdempotentPut() = runTest {
        val fileOps = MockFileOperations()
        val fileCasStore = FileCasStore(fileOps, "casRoot")
        val bus = Sha2CasBus(fileCasStore)

        val bytes = "hello world".encodeToByteArray()
        val cid1 = bus.put(bytes)
        val cid2 = bus.put(bytes)

        assertEquals(cid1, cid2)
        assertEquals(1, fileOps.memoryFiles.size)
    }

    @Test
    fun testCorruptBytesRejected() {
        val fileOps = MockFileOperations()
        val fileCasStore = FileCasStore(fileOps, "casRoot")

        val bytes = "hello world".encodeToByteArray()
        val cid = ContentId.of(bytes)
        val dir = cid.hex.substring(0, 2)
        val file = cid.hex.substring(2)
        val path = "casRoot/sha256/$dir/$file"

        // Manually write corrupt bytes
        fileOps.write(path, "corrupt data".encodeToByteArray())

        assertFailsWith<IllegalStateException> {
            fileCasStore.get(cid)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testBoundedBackpressure() = runTest {
        val fileOps = MockFileOperations()
        val fileCasStore = FileCasStore(fileOps, "casRoot")
        val bus = Sha2CasBus(fileCasStore, capacity = 2) // Small capacity

        val bytes = "test".encodeToByteArray()

        // Fill the channel
        bus.put(bytes)
        bus.put(bytes)

        var putSuspended = false
        val job = launch {
            putSuspended = true
            bus.put(bytes)
            putSuspended = false
        }

        // yield to let the coroutine run and suspend
        yield()
        assertTrue(putSuspended, "Channel should block when full")

        // Receive one to free space
        val receiver = bus.subscribe()
        receiver.receive()

        // yield to let the suspended put resume
        yield()
        assertTrue(!putSuspended, "Channel should resume after receiving")

        job.cancel()
    }
}
