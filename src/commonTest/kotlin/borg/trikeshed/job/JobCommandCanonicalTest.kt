package borg.trikeshed.job

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * JobCommand canonical-encoding contract tests.
 *
 * Spec (§C14: one entrypoint lowering path):
 *  - Every JobCommand variant has a stable canonical encoding.
 *  - Two commands constructed with identical logical fields produce equal
 *    canonical bytes (and therefore equal CIDs).
 *  - Different operation names / different payload fields produce distinct
 *    canonical bytes.
 *  - Empty optional fields (dependencies, expectedRevision) are omitted from
 *    the canonical encoding.
 */
class JobCommandCanonicalTest {

    @Test
    fun submitCanonicalIsDeterministic() {
        val a = JobCommand.Submit(JobId.of("j-1"), "ik-1", listOf(JobId.of("d1")), 7L)
        val b = JobCommand.Submit(JobId.of("j-1"), "ik-1", listOf(JobId.of("d1")), 7L)
        assertEquals(a, b)
        assertEquals(
            ContentId.of(CanonicalCbor.encode(a)),
            ContentId.of(CanonicalCbor.encode(b)),
            "identical submit must produce identical canonical bytes",
        )
    }

    @Test
    fun submitWithEmptyDependenciesOmitsField() {
        val c = JobCommand.Submit(JobId.of("j"), "ik", emptyList(), null)
        val bytes = CanonicalCbor.encode(c)
        assertTrue(bytes.decodeToString().indexOf("\"dependencies\"") < 0,
            "empty dependencies must not appear in canonical bytes")
    }

    @Test
    fun startCanonicalIsDeterministic() {
        val a = JobCommand.Start(JobId.of("j"), "ik", expectedRevision = 3L)
        val b = JobCommand.Start(JobId.of("j"), "ik", expectedRevision = 3L)
        assertEquals(
            ContentId.of(CanonicalCbor.encode(a)),
            ContentId.of(CanonicalCbor.encode(b)),
        )
    }

    @Test
    fun completeCarriesExpectedRevision() {
        val c = JobCommand.Complete(JobId.of("j"), "ik", expectedRevision = 5L)
        assertEquals(5L, c.expectedRevision)
        assertEquals("complete", c.operationName)
    }

    @Test
    fun failCarriesReason() {
        val c = JobCommand.Fail(JobId.of("j"), "ik", expectedRevision = 5L, reason = "boom")
        assertEquals("boom", c.reason)
        assertEquals("fail", c.operationName)
    }

    @Test
    fun retryCanonicalIsDeterministic() {
        val a = JobCommand.Retry(JobId.of("j"), "ik", expectedRevision = 2L)
        val b = JobCommand.Retry(JobId.of("j"), "ik", expectedRevision = 2L)
        assertEquals(
            ContentId.of(CanonicalCbor.encode(a)),
            ContentId.of(CanonicalCbor.encode(b)),
        )
    }

    @Test
    fun progressCarriesProgressFraction() {
        val c = JobCommand.Progress(JobId.of("j"), "ik", expectedRevision = 1L, progress = 0.5)
        assertEquals(0.5, c.progress)
        assertEquals("progress", c.operationName)
    }

    @Test
    fun blockCarriesReason() {
        val c = JobCommand.Block(JobId.of("j"), "ik", expectedRevision = 1L, reason = "dep-failed")
        assertEquals("dep-failed", c.reason)
        assertEquals("block", c.operationName)
    }

    @Test
    fun cancelHasCorrectOperationName() {
        assertEquals("cancel", JobCommand.Cancel(JobId.of("j"), "ik", 1L).operationName)
    }

    @Test
    fun moveCarriesToColumn() {
        val c = JobCommand.Move(JobId.of("j"), "ik", expectedRevision = 1L,
            toColumn = KanbanColumnId.of("col-agentic"))
        assertEquals(KanbanColumnId.of("col-agentic"), c.toColumn)
        assertEquals("move", c.operationName)
    }

    @Test
    fun moveCanonicalIsStableAcrossCalls() {
        val a = JobCommand.Move(JobId.of("j"), "ik", 1L, KanbanColumnId.of("col-agentic"))
        val b = JobCommand.Move(JobId.of("j"), "ik", 1L, KanbanColumnId.of("col-agentic"))
        assertEquals(
            ContentId.of(CanonicalCbor.encode(a)),
            ContentId.of(CanonicalCbor.encode(b)),
        )
    }

    @Test
    fun acknowledgeHasCorrectOperationName() {
        assertEquals("acknowledge",
            JobCommand.Acknowledge(JobId.of("j"), "ik", 1L).operationName)
    }

    @Test
    fun retractHasCorrectOperationName() {
        assertEquals("retract",
            JobCommand.Retract(JobId.of("j"), "ik", 1L).operationName)
    }

    @Test
    fun differentOperationsProduceDifferentCids() {
        val submit = JobCommand.Submit(JobId.of("j"), "ik", emptyList(), null)
        val start = JobCommand.Start(JobId.of("j"), "ik", expectedRevision = 1L)
        assertNotEquals(
            ContentId.of(CanonicalCbor.encode(submit)),
            ContentId.of(CanonicalCbor.encode(start)),
            "submit and start with same jobId must produce different CIDs",
        )
    }

    @Test
    fun differentToColumnProducesDifferentCids() {
        val a = JobCommand.Move(JobId.of("j"), "ik", 1L, KanbanColumnId.of("col-agentic"))
        val b = JobCommand.Move(JobId.of("j"), "ik", 1L, KanbanColumnId.of("col-closed"))
        assertNotEquals(
            ContentId.of(CanonicalCbor.encode(a)),
            ContentId.of(CanonicalCbor.encode(b)),
        )
    }
}