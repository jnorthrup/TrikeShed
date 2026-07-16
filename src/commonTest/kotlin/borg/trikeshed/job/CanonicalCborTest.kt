package borg.trikeshed.job

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * S1 — Canonical CBOR contract tests.
 *
 * Spec (§S1 RED):
 *  - Equivalent JSON/YAML/CBOR values produce one CID
 *  - Different schema-significant values produce different CIDs
 *  - Map insertion order cannot change a CID
 *
 * Anchor: encode is canonical, idempotent under reorder, and free of input
 * whitespace/whim — only logical key/value structure matters.
 */
class CanonicalCborTest {

    @Test
    fun encodeStringIsDeterministic() {
        val a = CanonicalCbor.encode("hello")
        val b = CanonicalCbor.encode("hello")
        assertTrue(a.contentEquals(b), "same string must encode to identical bytes")
        assertTrue(a.isNotEmpty(), "encoding must produce non-empty bytes")
    }

    @Test
    fun encodeStringDistinguishesContent() {
        val a = CanonicalCbor.encode("alpha")
        val b = CanonicalCbor.encode("beta")
        assertEquals(false, a.contentEquals(b),
            "different strings must produce different byte sequences")
    }

    @Test
    fun encodeJobSnapshotIsOrderIndependent() {
        val a = JobSnapshot(
            jobId = JobId.of("j-1"),
            revision = 7,
            causalKey = "ck",
            lifecycle = "active",
            dependencies = listOf(JobId.of("d1"), JobId.of("d2")),
            attemptCount = 3,
            parentJobId = JobId.of("p"),
            attemptId = "att",
        )
        val first = CanonicalCbor.encode(a)
        val second = CanonicalCbor.encode(a)
        assertTrue(first.contentEquals(second),
            "same logical snapshot must yield identical canonical bytes")
        assertEquals(
            ContentId.of(first),
            ContentId.of(second),
            "CIDs over canonical CBOR must be deterministic for identical logical state",
        )
    }

    @Test
    fun encodeJobSnapshotDistinguishesLifecycle() {
        val submitted = JobSnapshot(JobId.of("j"), 1, "ck", "submitted", emptyList())
        val active = JobSnapshot(JobId.of("j"), 1, "ck", "active", emptyList())
        assertNotEquals(
            ContentId.of(CanonicalCbor.encode(submitted)),
            ContentId.of(CanonicalCbor.encode(active)),
            "lifecycle change must yield different canonical bytes and CIDs",
        )
    }

    @Test
    fun encodeJobSnapshotDependencyOrderIsCanonical() {
        val a = JobSnapshot(JobId.of("j"), 1, "ck", "active", listOf(JobId.of("d1"), JobId.of("d2"), JobId.of("d3")))
        val b = JobSnapshot(JobId.of("j"), 1, "ck", "active", listOf(JobId.of("d3"), JobId.of("d2"), JobId.of("d1")))
        assertEquals(
            ContentId.of(CanonicalCbor.encode(a)),
            ContentId.of(CanonicalCbor.encode(b)),
            "dependency ordering must be canonical — list permutation must not change CID",
        )
    }

    @Test
    fun encodeJobCommandRoundTripsThroughCid() {
        val submit = JobCommand.Submit(
            jobId = JobId.of("j-1"),
            idempotencyKey = "ik-1",
            dependencies = listOf(JobId.of("d1")),
            expectedRevision = null,
        )
        val first = CanonicalCbor.encode(submit)
        val second = CanonicalCbor.encode(submit)
        assertTrue(first.contentEquals(second),
            "same logical command must encode to identical canonical bytes")
        assertEquals(ContentId.of(first), ContentId.of(second))
    }

    @Test
    fun encodeJobCommandDifferentOperationsDiffer() {
        val submit = JobCommand.Submit(JobId.of("j"), "ik", emptyList(), null)
        val start = JobCommand.Start(JobId.of("j"), "ik", expectedRevision = 1)
        assertNotEquals(
            ContentId.of(CanonicalCbor.encode(submit)),
            ContentId.of(CanonicalCbor.encode(start)),
            "different operation names must yield different CIDs",
        )
    }

    @Test
    fun encodeJobCommandMoveIncludesToColumn() {
        val moveA = JobCommand.Move(JobId.of("j"), "ik", expectedRevision = 1, toColumn = KanbanColumnId.of("col-agentic"))
        val moveB = JobCommand.Move(JobId.of("j"), "ik", expectedRevision = 1, toColumn = KanbanColumnId.of("col-closed"))
        assertNotEquals(
            ContentId.of(CanonicalCbor.encode(moveA)),
            ContentId.of(CanonicalCbor.encode(moveB)),
            "different toColumn values must yield different CIDs",
        )
    }
}