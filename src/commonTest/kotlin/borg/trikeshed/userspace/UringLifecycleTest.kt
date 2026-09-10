package borg.trikeshed.userspace

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.toSeries
import borg.trikeshed.userspace.UringOp.Companion.Submissions
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext
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
        val facade = FunctionalUringFacade.create(this, 2, backend)
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
    fun full_queue_backpressures_and_admitted_cancellation_retains_effects() = runTest {
        val backend = LatencyBackend()
        val facade = FunctionalUringFacade.create(this, 2, backend)
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
            assertEquals(listOf(1L, 2L), backend.effects, "every admitted effect settles before cancellation releases its buffers")
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
    fun cancelled_owner_is_rejected_before_backend_ownership() = runTest {
        val owner = SupervisorJob(coroutineContext[Job])
        owner.cancel()
        val backend = LatencyBackend()
        assertFails {
            FunctionalUringFacade.create(CoroutineScope(coroutineContext + owner), 1, backend)
        }
        assertEquals(0, backend.closes)
        assertEquals(emptyList(), backend.effects)
    }

    @Test
    fun owner_cancellation_drains_admitted_effects_before_backend_close() = runTest {
        val owner = SupervisorJob(coroutineContext[Job])
        val backend = LatencyBackend()
        val facade = FunctionalUringFacade.create(CoroutineScope(coroutineContext + owner), 2, backend)
        try {
            val active = async { facade.batchEnqueue(arrayOf(Submissions.nop(1)).toSeries()) }
            backend.entered.await()
            val queued = async { facade.batchEnqueue(arrayOf(Submissions.nop(2)).toSeries()) }
            runCurrent()
            owner.cancel()
            runCurrent()
            assertFalse(active.isCompleted)
            assertFalse(queued.isCompleted)
            assertEquals(0, backend.closes)
            assertFails { facade.batchEnqueue(arrayOf(Submissions.nop(3)).toSeries()) }
            backend.release.complete(Unit)
            assertEquals(1L, active.await()[0].userData)
            assertEquals(2L, queued.await()[0].userData)
            facade.drain()
            assertEquals(listOf(1L, 2L), backend.effects)
            assertEquals(1, backend.closes)
        } finally {
            backend.release.complete(Unit)
            facade.drain()
        }
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

    @Test
    fun drain_releases_mapping_before_paused_caller_continuation_resumes() = runTest {
        val callerContinuations = ArrayDeque<Runnable>()
        val pausedCaller = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                callerContinuations.addLast(block)
            }
        }
        var unmapped = false
        val memory = MemoryMapping(object : MemoryMappingAccess {
            override val address = 0x1_0000_1000L
            override val length = 4096L
            override fun get(offset: Long): Byte = error("advice does not read bytes")
            override fun set(offset: Long, value: Byte): Unit = error("advice does not write bytes")
            override fun read(offset: Long, dst: ByteArray, start: Int, length: Int): Unit = error("advice does not read bytes")
            override fun write(offset: Long, src: ByteArray, start: Int, length: Int): Unit = error("advice does not write bytes")
            override fun sync(offset: Long, length: Long, flags: Int): Unit = error("advice does not sync bytes")
            override fun close() { unmapped = true }
        }, 1)
        val entered = CompletableDeferred<Unit>()
        val settle = CompletableDeferred<Unit>()
        var backendClosed = false
        val backend = object : UserspaceChannelBackend {
            override val capabilities = UringOp.MADVISE.mask
            override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> = error("suspend path required")
            override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> {
                assertEquals(memory.address, submissions[0].addr)
                entered.complete(Unit)
                settle.await()
                return arrayOf(UringCompletion(submissions[0].userData, 0, 0)).toSeries()
            }
            override fun close() { backendClosed = true }
        }
        val facade = FunctionalUringFacade.create(this, 1, backend)
        val caller = async(pausedCaller, start = CoroutineStart.UNDISPATCHED) {
            facade.batchEnqueue(arrayOf(Submissions.madvise(memory, len = 4096, advice = 3, userData = 1)).toSeries())
        }
        try {
            entered.await()
            assertFailsWith<IllegalStateException> { memory.close() }
            val drain = async { facade.drain() }
            runCurrent()
            assertFalse(drain.isCompleted)
            settle.complete(Unit)
            drain.await()
            assertTrue(backendClosed)
            assertFalse(caller.isCompleted, "the caller continuation remains deliberately unscheduled")
            assertTrue(callerContinuations.isNotEmpty())
            memory.close()
            assertTrue(unmapped, "drain released the mapping independently of caller scheduling")
        } finally {
            settle.complete(Unit)
            facade.drain()
            while (callerContinuations.isNotEmpty()) callerContinuations.removeFirst().run()
            caller.await()
            memory.close()
        }
    }

}
