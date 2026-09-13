package borg.trikeshed.userspace

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.s_
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Guard: drain() classifies the consumer failure BEFORE cleanup. A cancelPending/admission failure
 * that surfaces during the consumer's stopAdmission must reach the caller even when the subsequent
 * backend.close() succeeds — a successful close may only swallow its own backend-close failure.
 */
class ConsumerFailureClassificationTest {

    /** cancelPending fails once (during the first stopAdmission, from the consumer), then succeeds. */
    private class CancelPendingThenCloseBackend : UserspaceChannelBackend {
        var cancelPendingCalls = 0
        var failCancelPending = true
        var closeCalls = 0
        var failClose = false
        var closed = false

        override fun submitBatch(submissions: List<UringOp.Companion.UringSubmission>): List<SelectionResult> = error("unexpected submission")
        override suspend fun batchEnqueue(submissions: Series<UringOp.Companion.UringSubmission>): Series<UringCompletion> = error("unexpected submission")

        override fun cancelPending() {
            cancelPendingCalls++
            if (failCancelPending) error("cancel pending unavailable")
        }

        override fun close() {
            closeCalls++
            if (failClose) error("resource release is temporarily unavailable")
            closed = true
        }
    }

    @Test
    fun admissionFailureSurvivesASuccessfulClose() = runTest {
        val backend = CancelPendingThenCloseBackend()
        val facade = FunctionalUringFacade.create(this, 1, backend)
        val failure = assertFailsWith<IllegalStateException> { facade.drain() }
        assertEquals("cancel pending unavailable", failure.message,
            "the admission/cancel failure must surface, not be replaced by close state")

        // Cleanup path now permitted; the close itself succeeds. A retry drain must not rethrow
        // the already-consumed admission failure, but the backend must actually close.
        backend.failCancelPending = false
        facade.drain()
        assertTrue(backend.closed, "successful close must release the backend")
    }

    @Test
    fun closeFailureStillRetriesAndRepairs() = runTest {
        val backend = CancelPendingThenCloseBackend().apply { failCancelPending = false; failClose = true }
        val facade = FunctionalUringFacade.create(this, 1, backend)
        assertFailsWith<IllegalStateException> { facade.drain() }
        assertTrue(!backend.closed, "a failed close must not report the backend closed")
        backend.failClose = false
        facade.drain()
        assertTrue(backend.closed, "retry must release the backend after a failed close")
    }
}
