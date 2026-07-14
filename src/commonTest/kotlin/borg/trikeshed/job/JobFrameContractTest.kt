package borg.trikeshed.job

import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * E0 — Vertical foundation cut RED tests.
 *
 * Proves the smallest live vertical: Confix JSON → schema validation →
 * JobFrame → JobReducer → JobProjection.
 *
 * Every type referenced here is NEW — none exist in the codebase yet.
 * These tests fail to compile until the E0 cut is implemented.
 */
class JobFrameContractTest {

    /**
     * C01: One schema contract.
     * job-nexus.schema.json must exist as a classpath resource.
     */
    @Test
    fun schemaResourceMustExist() {
        val cl = Thread.currentThread().contextClassLoader
        val url = cl?.getResource("confix/job-nexus.schema.json")
        assertNotNull(url, "confix/job-nexus.schema.json must exist on the classpath")
        val text = url.readText()
        assertTrue(text.contains("\"command\""), "schema must define a command record type")
        assertTrue(text.contains("\"event\""), "schema must define an event record type")
        assertTrue(text.contains("\"snapshot\""), "schema must define a snapshot record type")
        assertTrue(text.contains("\"fact\""), "schema must define a fact record type")
        assertTrue(text.contains("\"activation\""), "schema must define an activation record type")
    }

    /**
     * C02: One committed identity model.
     * ContentId must be sha256:<64 hex> over canonical CBOR.
     */
    @Test
    fun contentIdMustBeSha256PrefixedHex() {
        val cid = ContentId.of("hello world".encodeToByteArray())
        assertTrue(cid.value.startsWith("sha256:"), "ContentId must start with sha256:")
        val hex = cid.value.removePrefix("sha256:")
        assertEquals(64, hex.length, "ContentId hex must be 64 chars, got ${hex.length}")
        assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' }, "must be lowercase hex")
    }

    /**
     * C02: Same canonical bytes → same CID. Different bytes → different CID.
     */
    @Test
    fun contentIdIsDeterministicAndCollisionResistant() {
        val a = ContentId.of("hello".encodeToByteArray())
        val b = ContentId.of("hello".encodeToByteArray())
        val c = ContentId.of("world".encodeToByteArray())
        assertEquals(a, b, "same bytes must produce same CID")
        assertNotEquals(a, c, "different bytes must produce different CID")
    }

    /**
     * JobFrame wraps a ConfixDoc and extracts operation, jobId, idempotencyKey.
     */
    @Test
    fun jobFrameExtractsOperationAndJobId() {
        val doc = ConfixDoc.parse("""
            {"operation":"submit","jobId":"j-1","idempotencyKey":"idem-1"}
        """.trimIndent().encodeToByteArray())
        val frame = JobFrame(doc)
        assertEquals("submit", frame.operation)
        assertEquals("j-1", frame.jobId)
        assertEquals("idem-1", frame.idempotencyKey)
    }

    /**
     * JobFrame rejects unknown operations.
     */
    @Test
    fun jobFrameRejectsUnknownOperation() {
        val doc = ConfixDoc.parse("""
            {"operation":"frobnicate","jobId":"j-1"}
        """.trimIndent().encodeToByteArray())
        try {
            JobFrame(doc)
            fail("JobFrame must reject unknown operation 'frobnicate'")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("operation"), "error must mention operation")
        }
    }

    /**
     * C03/C07: JobReducer deduplicates by idempotencyKey.
     * Same idempotencyKey → same result, no new event.
     */
    @Test
    fun reducerDeduplicatesByIdempotencyKey() {
        val doc = ConfixDoc.parse("""
            {"operation":"submit","jobId":"j-1","idempotencyKey":"idem-1","sequence":1}
        """.trimIndent().encodeToByteArray())
        val frame = JobFrame(doc)
        val reducer = JobReducer()

        val first = reducer.reduce(frame)
        val second = reducer.reduce(frame)

        assertTrue(first.accepted, "first reduce must be accepted")
        assertEquals("accepted", first.event.operation)
        assertEquals("rejected", second.event.operation, "duplicate idempotencyKey must be rejected")
        assertTrue(second.event.reason?.contains("idempotency") == true || second.event.reason?.contains("duplicate") == true,
            "rejection reason must mention idempotency or duplicate")
    }

    /**
     * C04: Reducer produces a committed snapshot with a revision.
     */
    @Test
    fun reducerProducesSnapshotWithRevision() {
        val doc = ConfixDoc.parse("""
            {"operation":"submit","jobId":"j-1","idempotencyKey":"idem-1"}
        """.trimIndent().encodeToByteArray())
        val frame = JobFrame(doc)
        val reducer = JobReducer()
        val result = reducer.reduce(frame)

        assertNotNull(result.snapshot, "accepted reduce must produce a snapshot")
        assertEquals("j-1", result.snapshot!!.jobId)
        assertEquals(1, result.snapshot!!.revision, "first revision must be 1")
        assertEquals("submitted", result.snapshot!!.lifecycle)
    }

    /**
     * C02: Revision increments on accepted mutations.
     */
    @Test
    fun revisionIncrementsOnAcceptedMutation() {
        val reducer = JobReducer()

        reducer.reduce(JobFrame(ConfixDoc.parse(
            """{"operation":"submit","jobId":"j-1","idempotencyKey":"k1"}""".encodeToByteArray()
        )))
        val startResult = reducer.reduce(JobFrame(ConfixDoc.parse(
            """{"operation":"start","jobId":"j-1","idempotencyKey":"k2","expectedRevision":1}""".encodeToByteArray()
        )))

        assertTrue(startResult.accepted)
        assertEquals(2, startResult.snapshot!!.revision, "revision must increment to 2 after start")
        assertEquals("active", startResult.snapshot!!.lifecycle)
    }

    /**
     * C04: Stale expectedRevision must be rejected.
     */
    @Test
    fun staleExpectedRevisionMustBeRejected() {
        val reducer = JobReducer()
        reducer.reduce(JobFrame(ConfixDoc.parse(
            """{"operation":"submit","jobId":"j-1","idempotencyKey":"k1"}""".encodeToByteArray()
        )))

        val result = reducer.reduce(JobFrame(ConfixDoc.parse(
            """{"operation":"start","jobId":"j-1","idempotencyKey":"k2","expectedRevision":99}""".encodeToByteArray()
        )))

        assertFalse(result.accepted, "stale expectedRevision=99 must be rejected")
        assertEquals("rejected", result.event.operation)
    }

    /**
     * JobProjection projects a snapshot to a KanbanCard.
     */
    @Test
    fun projectionProducesKanbanCard() {
        val snapshot = JobSnapshot(
            jobId = "j-1",
            revision = 1,
            causalKey = "ck-1",
            lifecycle = "submitted",
            dependencies = listOf("dep-1", "dep-2"),
        )
        val card = JobProjection.projectToCard(snapshot)
        assertEquals("j-1", card.jobId)
        assertEquals("submitted", card.lifecycle)
        assertEquals(listOf("dep-1", "dep-2"), card.dependencies)
        assertEquals("ck-1", card.causalKey)
    }

    /**
     * C09: Kanban column is derived from lifecycle, not a manual status.
     */
    @Test
    fun kanbanColumnDerivedFromLifecycle() {
        val submitted = JobSnapshot("j-1", 1, "ck", "submitted", emptyList())
        val active = JobSnapshot("j-2", 1, "ck", "active", emptyList())
        val closed = JobSnapshot("j-3", 1, "ck", "closed", emptyList())

        assertEquals("col-causal-blocked", JobProjection.projectToCard(submitted).columnId)
        assertEquals("col-agentic", JobProjection.projectToCard(active).columnId)
        assertEquals("col-closed", JobProjection.projectToCard(closed).columnId)
    }

    /**
     * Duplicate causal construction must not enqueue a second assertion.
     * (from the plan's gate: "duplicate causal construction does not enqueue a second assertion")
     */
    @Test
    fun duplicateCausalConstructionProducesNoSecondFact() {
        val reducer = JobReducer()
        val doc = ConfixDoc.parse(
            """{"operation":"submit","jobId":"j-1","idempotencyKey":"k1"}""".encodeToByteArray()
        )

        val r1 = reducer.reduce(JobFrame(doc))
        val r2 = reducer.reduce(JobFrame(doc))

        assertNotNull(r1.fact, "first reduce must produce a fact")
        assertNull(r2.fact, "duplicate reduce must NOT produce a second fact")
    }
}
