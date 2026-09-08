package borg.trikeshed.userspace

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.toSeries
import borg.trikeshed.userspace.UringOp.Companion.Submissions
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Controlled effect latency isolates admission/settlement races; real file tests use OS adapters. */
@OptIn(ExperimentalCoroutinesApi::class)
class UringLifecycleTest {
    private class LatencyBackend : UserspaceChannelBackend {
        override val capabilities: Long = UringOp.NOP.mask
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val effects = mutableListOf<Long>()
        var closes = 0
        override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> =
            error("scoped execution must use suspend backend")
        override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> {
            val submission = submissions[0]
            if (submission.userData == 1L) {
                entered.complete(Unit)
                release.await()
            }
            effects += submission.userData
            return arrayOf(UringCompletion(submission.userData, 0, 0)).toSeries()
        }
        override fun close() { closes++ }
    }

    @Test
    fun drain_waits_for_active_and_admitted_queue_before_closing() = runTest {
        val backend = LatencyBackend()
        val facade = FunctionalUringFacade.create(this, 1, backend)
        try {
            val active = async { facade.batchEnqueue(arrayOf(Submissions.nop(1)).toSeries()) }
            backend.entered.await()
            val queued = async { facade.batchEnqueue(arrayOf(Submissions.nop(2)).toSeries()) }
            runCurrent()
            val drain = async { facade.drain() }
            runCurrent()
            assertFalse(active.isCompleted)
            assertFalse(queued.isCompleted)
            assertFalse(drain.isCompleted)
            assertEquals(0, backend.closes)
            backend.release.complete(Unit)
            assertEquals(1L, active.await()[0].userData)
            assertEquals(2L, queued.await()[0].userData)
            drain.await()
            assertEquals(listOf(1L, 2L), backend.effects)
            assertEquals(1, backend.closes)
            assertFails { facade.batchEnqueue(arrayOf(Submissions.nop(3)).toSeries()) }
            facade.drain()
            assertEquals(1, backend.closes)
        } finally {
            backend.release.complete(Unit)
            facade.drain()
        }
    }

    @Test
    fun cancellation_of_active_request_waits_for_effect_settlement() = runTest {
        val backend = LatencyBackend()
        val facade = FunctionalUringFacade.create(this, 1, backend)
        try {
            val active = async { facade.batchEnqueue(arrayOf(Submissions.nop(1)).toSeries()) }
            backend.entered.await()
            active.cancel()
            runCurrent()
            assertFalse(active.isCompleted, "cancellation must not release borrowed buffers before execution settles")
            backend.release.complete(Unit)
            active.join()
            assertTrue(active.isCancelled)
            assertEquals(listOf(1L), backend.effects, "the settled effect was not undone")
        } finally {
            backend.release.complete(Unit)
            facade.drain()
        }
    }

    @Test
    fun full_queue_backpressures_and_cancelled_admission_has_no_effect() = runTest {
        val backend = LatencyBackend()
        val facade = FunctionalUringFacade.create(this, 1, backend)
        try {
            val active = async { facade.batchEnqueue(arrayOf(Submissions.nop(1)).toSeries()) }
            backend.entered.await()
            val queued = async { facade.batchEnqueue(arrayOf(Submissions.nop(2)).toSeries()) }
            runCurrent()
            val blocked = async { facade.batchEnqueue(arrayOf(Submissions.nop(3)).toSeries()) }
            runCurrent()
            blocked.cancel()
            queued.cancel()
            runCurrent()
            assertTrue(blocked.isCompleted, "unadmitted sender cancellation should settle immediately")
            assertFalse(queued.isCompleted, "admitted request waits for consumer acknowledgement")
            backend.release.complete(Unit)
            active.await()
            queued.join()
            assertEquals(listOf(1L), backend.effects)
        } finally {
            backend.release.complete(Unit)
            facade.drain()
        }
    }

    @Test
    fun backend_close_failure_settles_drain_and_is_reported_to_caller() = runTest {
        val failure = IllegalStateException("descriptor close failed")
        val backend = object : UserspaceChannelBackend {
            override val capabilities: Long = UringOp.NOP.mask
            override fun submitBatch(submissions: List<UringSubmission>) =
                submissions.map { SelectionResult(0, it.userData) }
            override suspend fun batchEnqueue(submissions: Series<UringSubmission>) =
                arrayOf(UringCompletion(submissions[0].userData, 0, 0)).toSeries()
            override fun close(): Unit = throw failure
        }
        val facade = FunctionalUringFacade.create(this, 1, backend)
        facade.batchEnqueue(arrayOf(Submissions.nop(2)).toSeries())
        assertEquals(failure, assertFailsWith<IllegalStateException> { facade.drain() })
    }

    @Test
    fun cancelled_owner_closes_backend_and_rejects_admission_without_waiting_forever() = runTest {
        val owner = SupervisorJob(coroutineContext[Job])
        owner.cancel()
        val backend = LatencyBackend()
        val facade = FunctionalUringFacade.create(CoroutineScope(coroutineContext + owner), 1, backend)
        runCurrent()
        assertFails { facade.batchEnqueue(arrayOf(Submissions.nop(2)).toSeries()) }
        facade.drain()
        assertEquals(1, backend.closes)
        assertEquals(emptyList(), backend.effects)
    }

    @Test
    fun duplicate_outstanding_token_across_batches_is_rejected() = runTest {
        val backend = LatencyBackend()
        val facade = FunctionalUringFacade.create(this, 2, backend)
        try {
            val active = async { facade.batchEnqueue(arrayOf(Submissions.nop(1)).toSeries()) }
            backend.entered.await()
            assertFails { facade.batchEnqueue(arrayOf(Submissions.nop(1)).toSeries()) }
            backend.release.complete(Unit)
            active.await()
            assertEquals(listOf(1L), backend.effects)
        } finally {
            backend.release.complete(Unit)
            facade.drain()
        }
    }
}
