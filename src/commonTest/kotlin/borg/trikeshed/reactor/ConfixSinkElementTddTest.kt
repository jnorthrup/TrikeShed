package borg.trikeshed.reactor

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.couch.isam.ConfixIsamFactory
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.job.ConfixFacetPlan
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.testing.TestDispatcher
import kotlinx.coroutines.testing.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ConfixSinkElement TDD — RED tests for the facet projection sink.
 *
 * Each test drives a facet event through the sink and asserts:
 * 1. CasStore.put is called with canonical CBOR of the facet projection
 * 2. ISAM frame is appended with matching schema + CID
 * 3. WAL frame is written with same CID + timestamp
 * 4. ContentId emitted on [committed] flow matches the CAS CID
 * 5. CID deduplication: identical facet projections → identical CID
 */
class ConfixSinkElementTddTest {

    private val testDispatcher = TestDispatcher()

    @Test
    fun `Sink persists single facet event and emits CID`() = runTest {
        val casStore = FakeCasStore()
        val isamFactory = FakeIsamFactory()
        val wal = FakeWal()

        val facetPlan = ConfixFacetPlan(
            commandOperations = setOf("test"),
            eventOperations = setOf("test"),
            requiredFields = setOf("jobId"),
            schemaText = "{}",
        )

        val element = ConfixSinkElement(
            casStore = casStore,
            isamFactory = isamFactory.builder(),
            wal = wal,
            parentJob = Job(),
            initialConfig = ConfixSinkConfig(facetPlan = facetPlan),
        )
        element.open()

        val projection = FacetProjection(
            columns = mapOf(
                "jobId" to ColumnData.StringColumn(arrayOf("job-123")),
                "operation" to ColumnData.StringColumn(arrayOf("submit")),
            ),
        )
        val event = ConfixFacetEvent(
            facetProjection = projection,
            timestampMs = 1_000_000L,
            traceId = "trace-sink-1",
        )

        val submitted = element.submit(event)
        assertTrue(submitted)

        // Wait for processing
        testDispatcher.advanceUntilIdle()

        val cid = element.committed.first()
        assertNotNull(cid)
        assertEquals(cid, casStore.lastPutCid)

        // ISAM frame recorded
        assertTrue(isamFactory.lastFrameCid == cid)

        // WAL frame recorded
        assertTrue(wal.lastCid == cid)

        element.drain()
    }

    @Test
    fun `CID deduplication: identical facet projections yield identical CID`() = runTest {
        val casStore = FakeCasStore()
        val isamFactory = FakeIsamFactory()
        val wal = FakeWal()

        val facetPlan = ConfixFacetPlan(
            commandOperations = setOf("test"),
            eventOperations = setOf("test"),
            requiredFields = setOf("jobId"),
            schemaText = "{}",
        )

        val element = ConfixSinkElement(
            casStore = casStore,
            isamFactory = isamFactory.builder(),
            wal = wal,
            parentJob = Job(),
            initialConfig = ConfixSinkConfig(facetPlan = facetPlan),
        )
        element.open()

        val projection = FacetProjection(
            columns = mapOf(
                "jobId" to ColumnData.StringColumn(arrayOf("job-456")),
            ),
        )

        // Submit same projection twice
        element.submit(ConfixFacetEvent(projection, 1L, "trace-a"))
        element.submit(ConfixFacetEvent(projection, 2L, "trace-b"))

        testDispatcher.advanceUntilIdle()

        val cids = element.committed.take(2).toList()
        assertEquals(2, cids.size)
        assertEquals(cids[0], cids[1]) // Same CID!
        assertEquals(1, casStore.putCallCount) // CasStore.put called only once due to dedup

        element.drain()
    }

    @Test
    fun `Different facet projections yield different CIDs`() = runTest {
        val casStore = FakeCasStore()
        val isamFactory = FakeIsamFactory()
        val wal = FakeWal()

        val facetPlan = ConfixFacetPlan(
            commandOperations = setOf("test"),
            eventOperations = setOf("test"),
            requiredFields = setOf("jobId"),
            schemaText = "{}",
        )

        val element = ConfixSinkElement(
            casStore = casStore,
            isamFactory = isamFactory.builder(),
            wal = wal,
            parentJob = Job(),
            initialConfig = ConfixSinkConfig(facetPlan = facetPlan),
        )
        element.open()

        element.submit(ConfixFacetEvent(
            FacetProjection(mapOf("jobId" to ColumnData.StringColumn(arrayOf("job-1")))),
            1L, "trace-1",
        ))
        element.submit(ConfixFacetEvent(
            FacetProjection(mapOf("jobId" to ColumnData.StringColumn(arrayOf("job-2")))),
            2L, "trace-2",
        ))

        testDispatcher.advanceUntilIdle()

        val cids = element.committed.take(2).toList()
        assertEquals(2, cids.size)
        assertTrue(cids[0] != cids[1])

        element.drain()
    }

    @Test
    fun `WAL frame carries correct timestamp and schema`() = runTest {
        val casStore = FakeCasStore()
        val isamFactory = FakeIsamFactory()
        val wal = FakeWal()

        val facetPlan = ConfixFacetPlan(
            commandOperations = setOf("test"),
            eventOperations = setOf("test"),
            requiredFields = setOf("jobId"),
            schemaText = "{}",
        )

        val element = ConfixSinkElement(
            casStore = casStore,
            isamFactory = isamFactory.builder(),
            wal = wal,
            parentJob = Job(),
            initialConfig = ConfixSinkConfig(facetPlan = facetPlan),
        )
        element.open()

        val projection = FacetProjection(
            columns = mapOf("jobId" to ColumnData.StringColumn(arrayOf("job-789"))),
        )
        val timestamp = 4_000_000L
        element.submit(ConfixFacetEvent(projection, timestamp, "trace-wal"))

        testDispatcher.advanceUntilIdle()

        val walFrame = wal.lastFrame
        assertNotNull(walFrame)
        assertEquals(timestamp, walFrame?.timestampMs)

        element.drain()
    }

    @Test
    fun `Sink lifecycle: CREATED -> OPEN -> ACTIVE -> DRAINING -> CLOSED`() = runTest {
        val casStore = FakeCasStore()
        val isamFactory = FakeIsamFactory()
        val wal = FakeWal()

        val facetPlan = ConfixFacetPlan(
            commandOperations = setOf("test"),
            eventOperations = setOf("test"),
            requiredFields = setOf(),
            schemaText = "{}",
        )

        val element = ConfixSinkElement(
            casStore = casStore,
            isamFactory = isamFactory.builder(),
            wal = wal,
            parentJob = Job(),
            initialConfig = ConfixSinkConfig(facetPlan = facetPlan),
        )

        assertEquals(ElementState.CREATED, element.state)

        element.open()
        assertEquals(ElementState.ACTIVE, element.state)
        assertTrue(element.isActive)

        element.drain()
        assertEquals(ElementState.CLOSED, element.state)
        assertTrue(element.isClosed)
    }

    @Test
    fun `Config change re-validates facet plan`() = runTest {
        val casStore = FakeCasStore()
        val isamFactory = FakeIsamFactory()
        val wal = FakeWal()

        val facetPlan1 = ConfixFacetPlan(
            commandOperations = setOf("cmd1"),
            eventOperations = setOf("evt1"),
            requiredFields = setOf("jobId"),
            schemaText = "{}",
        )
        val facetPlan2 = ConfixFacetPlan(
            commandOperations = setOf("cmd2"),
            eventOperations = setOf("evt2"),
            requiredFields = setOf("jobId", "causalKey"),
            schemaText = "{}",
        )

        val element = ConfixSinkElement(
            casStore = casStore,
            isamFactory = isamFactory.builder(),
            wal = wal,
            parentJob = Job(),
            initialConfig = ConfixSinkConfig(facetPlan = facetPlan1),
        )
        element.open()

        assertEquals(facetPlan1, element.config.facetPlan)

        element.updateConfig(ConfixSinkConfig(facetPlan = facetPlan2))
        assertEquals(facetPlan2, element.config.facetPlan)
        assertEquals(ElementState.ACTIVE, element.state)

        element.drain()
    }

    @Test
    fun `Errors emitted on processing failure`() = runTest {
        val casStore = FailingCasStore()
        val isamFactory = FakeIsamFactory()
        val wal = FakeWal()

        val facetPlan = ConfixFacetPlan(
            commandOperations = setOf("test"),
            eventOperations = setOf("test"),
            requiredFields = setOf("jobId"),
            schemaText = "{}",
        )

        val element = ConfixSinkElement(
            casStore = casStore,
            isamFactory = isamFactory.builder(),
            wal = wal,
            parentJob = Job(),
            initialConfig = ConfixSinkConfig(facetPlan = facetPlan),
        )
        element.open()

        element.submit(ConfixFacetEvent(
            FacetProjection(mapOf("jobId" to ColumnData.StringColumn(arrayOf("job-err")))),
            1L, "trace-err",
        ))

        testDispatcher.advanceUntilIdle()

        val error = element.errors.firstOrNull()
        assertNotNull(error)
        assertEquals("trace-err", error?.event.traceId)
        assertTrue(error?.message?.contains("CAS failure") == true)

        element.drain()
    }

    @Test
    fun `Batch processing respects batchSize config`() = runTest {
        val casStore = FakeCasStore()
        val isamFactory = FakeIsamFactory()
        val wal = FakeWal()

        val facetPlan = ConfixFacetPlan(
            commandOperations = setOf("test"),
            eventOperations = setOf("test"),
            requiredFields = setOf("jobId"),
            schemaText = "{}",
        )

        val element = ConfixSinkElement(
            casStore = casStore,
            isamFactory = isamFactory.builder(),
            wal = wal,
            parentJob = Job(),
            initialConfig = ConfixSinkConfig(
                facetPlan = facetPlan,
                batchSize = 3,
            ),
        )
        element.open()

        // Submit 5 events, batchSize=3
        repeat(5) { i ->
            element.submit(ConfixFacetEvent(
                FacetProjection(mapOf("jobId" to ColumnData.StringColumn(arrayOf("job-$i")))),
                i.toLong(), "trace-batch-$i",
            ))
        }

        testDispatcher.advanceUntilIdle()

        // All 5 should be processed regardless of batch config
        val cids = element.committed.take(5).toList()
        assertEquals(5, cids.size)

        element.drain()
    }
}

// ── Test fakes ──────────────────────────────────────────────────────────────

class FakeCasStore : CasStore {
    var lastPutCid: ContentId? = null
    var putCallCount = 0
    private val store = mutableMapOf<ContentId, ByteArray>()

    override suspend fun put(bytes: ByteArray): ContentId {
        putCallCount++
        val cid = ContentId.of(bytes) // deterministic SHA-256
        if (!store.containsKey(cid)) {
            store[cid] = bytes
        }
        lastPutCid = cid
        return cid
    }

    override suspend fun get(cid: ContentId): ByteArray? = store[cid]

    override suspend fun exists(cid: ContentId): Boolean = store.containsKey(cid)

    override suspend fun delete(cid: ContentId): Boolean = store.remove(cid) != null
}

class FailingCasStore : CasStore {
    override suspend fun put(bytes: ByteArray): ContentId =
        throw RuntimeException("CAS failure: simulated disk full")

    override suspend fun get(cid: ContentId): ByteArray? = null
    override suspend fun exists(cid: ContentId): Boolean = false
    override suspend fun delete(cid: ContentId): Boolean = false
}

class FakeIsamFactory {
    var lastFrameCid: ContentId? = null
    var lastSchema: List<borg.trikeshed.isam.RecordMeta>? = null

    fun builder() = ConfixIsamFactory.ConfixIsamStoreBuilder().apply {
        dataFileLocation = "/tmp/test-isam"
        stringpoolLocation = "/tmp/test-pool"
        exemplar(mapOf("jobId" to "string"))
    }
}

class FakeWal : ConfixWal("/tmp/test-wal", object : borg.trikeshed.userspace.nio.file.spi.FileOperations {
    override suspend fun readFully(file: String, offset: Long, buffer: ByteArray): Int = 0
    override suspend fun writeFully(file: String, offset: Long, buffer: ByteArray): Unit = {}
    override suspend fun sync(file: String): Unit = {}
    override fun size(file: String): Long = 0
    override fun exists(file: String): Boolean = false
    override fun create(file: String): Unit = {}
}) {
    var lastCid: ContentId? = null
    var lastFrame: WalFrame? = null

    override fun append(id: String, rev: String, doc: borg.trikeshed.parse.confix.ConfixDoc): Long {
        lastCid = ContentId.of(id)
        return 1
    }
}