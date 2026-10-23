package borg.trikeshed.skinnerbox

import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
import kotlinx.coroutines.*
import kotlin.test.*

class MeshPersistenceTest {
    private val grants = mapOf("alice" to setOf("tenant.a"), "bob" to setOf("tenant.b"))
    private fun task(verb: String, fields: Map<String, Any?>, tenant: String = "tenant.a") =
        MeshTask(tenant, verb, CanonicalCbor.encodeMap(fields))
    private fun put(request: String, key: String = "one", value: ByteArray = "{\"n\":1}".encodeToByteArray(), revision: String? = null) =
        task("row.put", mapOf("requestId" to request, "collection" to "rows", "key" to key,
            "expectedRevision" to revision, "value" to value))
    private suspend fun run(store: MeshPersistence, task: MeshTask, peer: String = "alice") =
        MeshPersistenceCodec.decode(store.execute(peer, task))

    @Test
    fun retryRetainsOriginalRevisionAndSequenceAndReusedRequestRefuses() = runBlocking<Unit> {
        val store = MeshPersistence.volatile(this, grants)
        try {
            val input = put("request-1")
            val first = store.execute("alice", input)
            assertContentEquals(first, store.execute("alice", input))
            val receipt = MeshPersistenceCodec.decode(first)
            assertEquals(false, receipt["durable"])
            assertEquals(1L, receipt["sequence"])
            val conflict = assertFailsWith<MeshPersistenceException> { store.execute("alice", put("request-1", "another")) }
            assertEquals(MeshPersistenceError.CONFLICT, conflict.reason)
            val stale = assertFailsWith<MeshPersistenceException> { store.execute("alice", put("request-2")) }
            assertEquals(MeshPersistenceError.CONFLICT, stale.reason)
            val revision = receipt["revision"] as String
            run(store, put("request-2", value = "{\"n\":2}".encodeToByteArray(), revision = revision))
            val changed = run(store, task("changes", mapOf("since" to 0L)))
            assertEquals(2, (changed["changes"] as List<*>).size)
            val row = run(store, task("row.get", mapOf("collection" to "rows", "key" to "one")))
            assertContentEquals("{\"n\":2}".encodeToByteArray(), row["value"] as ByteArray)
        } finally { store.drain() }
    }

    @Test
    fun tenantGrantPrecedesParsingAndContentIdentityDoesNotGrantAccess() = runBlocking<Unit> {
        val store = MeshPersistence.volatile(this, grants)
        try {
            val denied = assertFailsWith<MeshPersistenceException> {
                store.execute("bob", MeshTask("tenant.a", "row.put", byteArrayOf(0xff.toByte())))
            }
            assertEquals(MeshPersistenceError.UNAUTHORIZED, denied.reason)
            val value = "immutable".encodeToByteArray()
            val content = ContentId.of(value).value
            run(store, task("replica.retain", mapOf("contentId" to content, "value" to value)))
            val absent = assertFailsWith<MeshPersistenceException> {
                run(store, task("replica.read", mapOf("contentId" to content), "tenant.b"), "bob")
            }
            assertEquals(MeshPersistenceError.NOT_FOUND, absent.reason)
            assertContentEquals(value, run(store, task("replica.read", mapOf("contentId" to content)))["value"] as ByteArray)
        } finally { store.drain() }
    }

    @Test
    fun strictRowsAndBoundedPacketsRefuseBeforePublication() = runBlocking<Unit> {
        val store = MeshPersistence.volatile(this, grants)
        try {
            for (json in listOf("{\"a\":1,\"a\":2}", "[]", "{\"a\":NaN}", "{")) {
                val failure = assertFailsWith<MeshPersistenceException> {
                    run(store, put("bad", value = json.encodeToByteArray()))
                }
                assertEquals(MeshPersistenceError.INVALID, failure.reason)
            }
            assertFailsWith<MeshPersistenceException> {
                run(store, put("bad", value = ByteArray(MeshPersistenceCodec.ROW_BYTES + 1)))
            }
            assertFails { MeshPersistenceCodec.decode(byteArrayOf(0xbf.toByte(), 0xff.toByte())) }
            assertFails { MeshPersistenceCodec.decode(byteArrayOf(0xa1.toByte(), 0x61, 0x78, 0x9a.toByte(), 0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte())) }
            assertEquals(1L, run(store, put("bad"))["sequence"])
        } finally { store.drain() }
    }

    @Test
    fun boundedObjectPagesAndChangeCursorDoNotLoseRows() = runBlocking<Unit> {
        val store = MeshPersistence.volatile(this, grants)
        try {
            val value = ByteArray(60 * 1024) { (it % 251).toByte() }
            for (key in listOf("a", "b", "c")) run(store, task("object.put", mapOf(
                "requestId" to key, "collection" to "objects", "key" to key, "expectedRevision" to null, "value" to value)))
            var after: String? = null
            val keys = mutableListOf<String>()
            do {
                val page = run(store, task("object.list", mapOf("collection" to "objects", "after" to after, "limit" to 64L)))
                val rows = page["rows"] as List<*>
                assertEquals(1, rows.size)
                keys.add((rows.single() as Map<*, *>)["key"] as String)
                after = page["after"] as String
            } while (page["more"] == true)
            assertEquals(listOf("a", "b", "c"), keys)
            val one = run(store, task("changes", mapOf("since" to 0L, "limit" to 1L)))
            assertEquals(true, one["more"])
            val two = run(store, task("changes", mapOf("since" to one["since"], "limit" to 64L)))
            assertEquals(2, (two["changes"] as List<*>).size)
            assertEquals(3L, two["since"])
        } finally { store.drain() }
    }

    @Test
    fun historyCapacityKeepsRetriesReadableAndRefusesNewMutation() = runBlocking<Unit> {
        val store = MeshPersistence.volatile(this, grants, MeshPersistenceLimits(maxRecords = 1))
        try {
            val input = put("first")
            val receipt = store.execute("alice", input)
            val failure = assertFailsWith<MeshPersistenceException> { run(store, put("second", "other")) }
            assertEquals(MeshPersistenceError.CAPACITY, failure.reason)
            assertContentEquals(receipt, store.execute("alice", input))
            assertEquals(1, (run(store, task("changes", emptyMap()))["changes"] as List<*>).size)
        } finally { store.drain() }
    }

    @Test
    fun concurrentCallsSettleAndDrainClosesAdmission() = runBlocking<Unit> {
        val store = MeshPersistence.volatile(this, grants, MeshPersistenceLimits(queueCapacity = 2))
        val outcomes = (0 until 20).map { n -> async { runCatching { store.execute("alice", put("request-$n", "row-$n")) } } }.awaitAll()
        val receipts = outcomes.mapNotNull { it.getOrNull() }
        assertTrue(receipts.isNotEmpty())
        for (outcome in outcomes.filter { it.isFailure }) {
            assertEquals(MeshPersistenceError.CAPACITY, (outcome.exceptionOrNull() as MeshPersistenceException).reason)
        }
        assertEquals((1L..receipts.size.toLong()).toSet(), receipts.map { MeshPersistenceCodec.decode(it)["sequence"] }.toSet())
        withTimeout(5_000) { store.drain(); store.drain() }
        assertEquals(MeshPersistenceError.CLOSED,
            assertFailsWith<MeshPersistenceException> { store.execute("alice", put("late", "late")) }.reason)
    }

    @Test
    fun cancelledParentReleasesOwnerAndSettlesRequests() = runBlocking<Unit> {
        val parent = Job(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + parent)
        val store = MeshPersistence.volatile(scope, grants)
        store.execute("alice", put("before"))
        parent.cancel()
        withTimeout(5_000) { store.drain(); parent.join() }
        assertFailsWith<MeshPersistenceException> { store.execute("alice", put("after", "two")) }
    }

    @Test
    fun durableConstructorRejectsVolatileProviders() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> {
            MeshPersistence.open(this, CasStore.inMemory(), InMemoryFileOperations(), "/mesh", grants)
        }
    }

    @Test
    fun unrelatedTenantHistoryConsumesOnlyOneBoundedScanStroke() = runBlocking<Unit> {
        val store = MeshPersistence.volatile(this, grants)
        try {
            repeat(70) { index -> run(store, task("row.put", mapOf(
                "requestId" to "other-$index", "collection" to "rows", "key" to "row-$index",
                "expectedRevision" to null, "value" to "{}".encodeToByteArray()), "tenant.b"), "bob") }
            run(store, put("mine"))
            val empty = run(store, task("changes", mapOf("since" to 0L)))
            assertEquals(emptyList<Any?>(), empty["changes"])
            assertEquals(64L, empty["since"])
            assertEquals(true, empty["more"])
            val next = run(store, task("changes", mapOf("since" to empty["since"])))
            assertEquals(1, (next["changes"] as List<*>).size)
            assertEquals(71L, next["since"])
            assertEquals(false, next["more"])
        } finally { store.drain() }
    }
}
