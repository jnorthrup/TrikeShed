package borg.trikeshed.skinnerbox

import borg.trikeshed.lib.*
import kotlinx.coroutines.*
import kotlin.test.*

class MeshTwinElementTest {
    private val grants = mapOf("author" to TwinGrant("author", TwinSource.entries.toSet(), s_[TwinScope("*", "")]))
    private fun observation(at: Long) = TwinObservation.create(TwinKey("user", "alice"), TwinSource.MEASURED, "fixture", at, mapOf("observed" to at))
    private fun task(verb: String, payload: Map<String, Any?>) = MeshTask("tenant", verb, MeshPersistenceCodec.encode(payload))
    private fun merge(request: String, at: Long) = task("twin.merge", mapOf("requestId" to request, "observations" to listOf(observation(at).bytes())))
    private fun get() = task("twin.get", mapOf("kind" to "user", "key" to "alice"))

    @Test fun retainedBatchResultIsStableAfterLaterMutationsAndElementReopen() = runBlocking<Unit> {
        val persistence = MeshPersistence.volatile(this, mapOf("author" to setOf("tenant")))
        var twins = MeshTwinElement.open(this, persistence, grants, s_["tenant"], "author")
        try {
            val first = twins.execute("author", merge("first", 1))
            val receipt = MeshPersistenceCodec.decode(first, MeshPersistenceCodec.REPLY_BYTES)
            assertEquals(false, receipt["durable"])
            assertNotNull(receipt["event"])
            twins.execute("author", merge("second", 2))
            assertContentEquals(first, twins.execute("author", merge("first", 1)))
            twins.drain()
            twins = MeshTwinElement.open(this, persistence, grants, s_["tenant"], "author")
            assertContentEquals(first, twins.execute("author", merge("first", 1)))
            val head = MeshPersistenceCodec.decode(twins.execute("author", get()))
            assertEquals(observation(2).id.value, head["contentId"])
            val residual = MeshPersistenceCodec.decode(twins.execute("author", task("twin.residual", mapOf("known" to emptyList<String>()))))
            assertEquals(2, (residual["observations"] as List<*>).size)
            assertFails { twins.execute("author", merge("first", 3)) }
        } finally { twins.drain(); persistence.drain() }
    }

    @Test fun grantsApplyBeforeAdmissionAndAreRecheckedDuringReplay() = runBlocking<Unit> {
        val persistence = MeshPersistence.volatile(this, mapOf("author" to setOf("tenant"), "other" to setOf("elsewhere")))
        val twins = MeshTwinElement.open(this, persistence, grants, s_["tenant"], "author")
        try {
            assertFails { twins.execute("other", get()) }
            twins.execute("author", merge("first", 1))
            twins.drain()
            val plannedOnly = mapOf("author" to TwinGrant("author", setOf(TwinSource.PLANNED), s_[TwinScope("*", "")]))
            assertFails { MeshTwinElement.open(this, persistence, plannedOnly, s_["tenant"], "author") }
        } finally { twins.drain(); persistence.drain() }
    }

    @Test fun boundedBatchesAndClosedAdmissionDoNotPublish() = runBlocking<Unit> {
        val persistence = MeshPersistence.volatile(this, mapOf("author" to setOf("tenant")))
        val twins = MeshTwinElement.open(this, persistence, grants, s_["tenant"], "author")
        try {
            assertFails { twins.execute("author", task("twin.merge", mapOf("requestId" to "large", "observations" to List(65) { observation(it.toLong()).bytes() }))) }
            assertFails { twins.execute("author", get()) }
            twins.drain()
            withTimeout(1000) { assertFails { twins.execute("author", merge("closed", 1)) } }
        } finally { twins.drain(); persistence.drain() }
    }

    @Test fun referenceWirePagesKeepTheirSnapshotAndContinuation() = runBlocking<Unit> {
        val persistence = MeshPersistence.volatile(this, mapOf("author" to setOf("tenant")))
        val twins = MeshTwinElement.open(this, persistence, grants, s_["tenant"], "author")
        try {
            val observation = TwinObservation.create(TwinKey("user", "alice"), TwinSource.MEASURED, "fixture", 1,
                mapOf("references" to listOf(mapOf("kind" to "user", "key" to "missing1"), mapOf("kind" to "user", "key" to "missing2"))))
            twins.execute("author", task("twin.merge", mapOf("requestId" to "links", "observations" to listOf(observation.bytes()))))
            val first = MeshPersistenceCodec.decode(twins.execute("author", task("twin.references", mapOf("limit" to 1L))))
            assertEquals(1, (first["issues"] as List<*>).size)
            assertNotNull(first["after"])
            val last = MeshPersistenceCodec.decode(twins.execute("author", task("twin.references",
                mapOf("limit" to 1L, "after" to first["after"], "savepoint" to first["savepoint"]))))
            assertEquals(1, (last["issues"] as List<*>).size)
            assertNull(last["after"])
            assertFails { twins.execute("author", task("twin.references", mapOf("after" to first["after"]))) }
        } finally { twins.drain(); persistence.drain() }
    }
}
