package borg.trikeshed.util.oroboros

import borg.trikeshed.couch.CouchStore
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.FacetedRow
import borg.trikeshed.lib.MetaSeries
import borg.trikeshed.lib.Series2
import borg.trikeshed.lib.Twin
import borg.trikeshed.lib.Join
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MockVersionGateway : VersionGateway {
    var isAvailableVal = true
    var recordCalls = 0
    override suspend fun init(home: String): Boolean = true
    override suspend fun isAvailable(): Boolean = isAvailableVal
    override suspend fun record(home: String, author: String, message: String): String? {
        recordCalls++
        return "mock-rev-$recordCalls"
    }
}

class CoordinatorMockFileOperations : FileOperations {
    private val files = mutableMapOf<String, ByteArray>()

    override fun open(path: String, readOnly: Boolean): Int = 0
    override fun readAllLines(filename: String): List<String> = emptyList()
    override fun readAllBytes(filename: String): ByteArray = files[filename] ?: error("Not found")
    override fun readString(filename: String): String = ""
    override fun write(filename: String, bytes: ByteArray) { files[filename] = bytes }
    override fun write(filename: String, lines: List<String>) {}
    override fun write(filename: String, string: String) {}
    override fun cwd(): String = "/"
    override fun exists(filename: String): Boolean = files.containsKey(filename)
    override fun streamLines(fileName: String, bufsize: Int): Sequence<borg.trikeshed.lib.Join<Long, ByteArray>> = emptySequence()
    override fun iterateLines(fileName: String, bufsize: Int): Iterable<borg.trikeshed.lib.Join<Long, borg.trikeshed.lib.Series<Byte>>> = emptyList()
    override fun listDir(path: String): List<String> = emptyList()
    override fun isDir(path: String): Boolean = false
    override fun isFile(path: String): Boolean = files.containsKey(path)
    override fun mkdirs(path: String) {}
    override fun deleteRecursively(path: String) { files.remove(path) }
    override fun resolvePath(vararg parts: String): String = parts.joinToString("/")
    override fun readZip(path: String): List<Pair<String, ByteArray>> = emptyList()
    override fun createTempDir(prefix: String): String = "/tmp/$prefix"
    override fun close(fd: Int): Int = 0
    override fun size(fd: Int): Long = 0
}

class MockCasStore : CasStore() {
    private val store = mutableMapOf<ContentId, ByteArray>()
    override fun put(bytes: ByteArray): ContentId {
        val cid = ContentId.of(bytes)
        store[cid] = bytes
        return cid
    }
    override fun get(cid: ContentId): ByteArray? = store[cid]
}

class MockOroborosGateway : OroborosGateway, Join<OroborosK<*>, (OroborosK<*>) -> Any?> {
    val casStore = MockCasStore()
    val couchStore = CouchStoreFactory.inMemory()
    val attachments = CouchAttachmentGateway(couchStore, casStore)

    val storageRow: OroborosStorageRow = object : OroborosStorageRow, Join<OroborosStorageK<*>, (OroborosStorageK<*>) -> Any?> {
        @Suppress("UNCHECKED_CAST")
        override fun <R> get(key: OroborosStorageK<R>): R {
            return when (key) {
                is OroborosStorageK.Cas -> casStore as R
                is OroborosStorageK.Attachments -> attachments as R
                else -> error("Unsupported")
            }
        }
        override val a: OroborosStorageK<*> get() = error("Not supported")
        override val b: (OroborosStorageK<*>) -> Any? get() = error("Not supported")
    }

    val versionGateway = MockVersionGateway()

    override val storage: OroborosStorageRow get() = storageRow
    override val version: VersionGateway get() = versionGateway
    override val network: OroborosNetworkRow get() = error("Not implemented for test")

    override val a: OroborosK<*> get() = error("Not supported")
    override val b: (OroborosK<*>) -> Any? = { k ->
        when (k) {
            OroborosK.Storage -> storageRow
            OroborosK.Version -> versionGateway
            else -> null
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OroborosCoordinatorTest {
    @Test
    fun testCoordinatorUpsertAndIdempotentReplay() = runTest {
        val gateway = MockOroborosGateway()
        val fileOps = CoordinatorMockFileOperations()
        val coordinator = OroborosCoordinator(
            agent = "agent-1",
            forgeHome = "/home/forge",
            gateway = gateway,
            fileOps = fileOps
        )

        val bytes = "hello".encodeToByteArray()
        val mutation = Mutation.Upsert("test.txt", bytes)

        coordinator.submit(mutation)
        coordinator.drain()

        val results = coordinator.results
        val res1 = results.receive().getOrThrow()

        assertEquals("test.txt", res1.path)
        assertEquals("Upsert", res1.action)
        assertEquals("agent-1", res1.agent)
        assertEquals(1L, res1.seq)
        assertEquals("mock-rev-1", res1.revision)

        // Ensure file was written
        assertTrue(fileOps.exists("/home/forge/test.txt"))

        // Idempotent replay
        val coordinator2 = OroborosCoordinator(
            agent = "agent-1",
            forgeHome = "/home/forge",
            gateway = gateway, // same state
            fileOps = fileOps
        )

        coordinator2.submit(mutation)
        coordinator2.drain()

        val results2 = coordinator2.results
        val res2 = results2.receive().getOrThrow()

        assertEquals(1L, res2.seq) // should not advance seq
        assertEquals("mock-rev-1", res2.revision)

        // Version gateway should not have been called again (recordCalls stays 1)
        assertEquals(1, gateway.versionGateway.recordCalls)
    }
}
