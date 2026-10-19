package borg.trikeshed.userspace

import borg.trikeshed.lib.*
import borg.trikeshed.userspace.UringOp.Companion.Submissions
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class UringCompletionWakeTest {
    class Backend : UserspaceChannelBackend {
        override val capabilities = UringOp.NOP.mask
        override val deferredCapabilities = capabilities
        val pending = mutableSetOf<Long>()
        val completions = ArrayDeque<SelectionResult>()
        var ready = CompletableDeferred<Unit>()
        var waits = 0
        var reaps = 0
        var closes = 0
        var onWait: (() -> Unit)? = null

        override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> {
            for (submission in submissions) {
                check(pending.add(submission.userData))
                if (submission.userData == 2L) complete(2)
            }
            return emptyList()
        }

        fun complete(token: Long) {
            check(pending.remove(token))
            completions.add(SelectionResult(0, token))
            ready.complete(Unit)
        }

        override fun reapCompletions(minComplete: Int): List<SelectionResult> {
            reaps++
            val out = completions.toList()
            completions.clear()
            if (out.isNotEmpty()) ready = CompletableDeferred()
            return out
        }

        override suspend fun awaitCompletions() {
            waits++
            onWait?.also { onWait = null }?.invoke()
            if (completions.isEmpty()) ready.await()
        }

        override fun cancelPending() { pending.toList().forEach(::complete) }
        override fun close() { check(pending.isEmpty()); closes++ }
        override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> =
            error("The common facade owns completion fan-in")
    }

    @Test fun readinessAndAdmissionShareOneCoreWithoutPollingOrBlockingTheNextBatch() = runTest {
        val backend = Backend()
        val ring = FunctionalUringFacade.create(this, 2, backend)
        val first = async { ring.batchEnqueue(s_[Submissions.nop(1)]) }
        runCurrent()
        assertEquals(1, backend.waits)
        val reaps = backend.reaps
        runCurrent()
        assertEquals(reaps, backend.reaps, "An idle ring must suspend until readiness or an admission")
        val second = async { ring.batchEnqueue(s_[Submissions.nop(2)]) }
        runCurrent()
        assertEquals(2L, second.await()[0].userData)
        assertFalse(first.isCompleted)
        backend.complete(1)
        runCurrent()
        assertEquals(1L, first.await()[0].userData)
        ring.drain()
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(1, backend.closes)
    }

    @Test fun completionBetweenEmptyReapAndWaitIsNotLost() = runTest {
        val backend = Backend().apply { onWait = { complete(1) } }
        val ring = FunctionalUringFacade.create(this, 1, backend)
        assertEquals(1L, ring.batchEnqueue(s_[Submissions.nop(1)])[0].userData)
        ring.drain()
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(1, backend.waits)
    }

    @Test fun drainClosesAdmissionAndAwaitsARealCompletionWakeup() = runTest {
        val backend = Backend()
        val ring = FunctionalUringFacade.create(this, 1, backend)
        val request = async { ring.batchEnqueue(s_[Submissions.nop(1)]) }
        runCurrent()
        val drain = async { ring.drain() }
        runCurrent()
        assertFalse(drain.isCompleted)
        assertEquals(0, backend.closes)
        assertFails { ring.batchEnqueue(s_[Submissions.nop(3)]) }
        backend.complete(1)
        request.await()
        drain.await()
        assertEquals(1, backend.closes)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun anotherUnscopedReaperCompletesTheBatchAndJoinsItsReadinessWait() = runTest {
        val parked = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        var waitsClosed = 0
        val backend = object : UserspaceChannelBackend {
            override val capabilities = UringOp.NOP.mask
            override val deferredCapabilities = capabilities
            val pending = mutableSetOf<Long>()
            val cqes = ArrayDeque<SelectionResult>()
            override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> {
                submissions.forEach { pending.add(it.userData) }
                if (submissions.any { it.userData == 2L }) {
                    pending.forEach { cqes.add(SelectionResult(0, it)) }
                    pending.clear()
                }
                return emptyList()
            }
            override fun reapCompletions(minComplete: Int): List<SelectionResult> = cqes.toList().also { cqes.clear() }
            override suspend fun awaitCompletions() {
                try { parked.await() }
                finally {
                    withContext(NonCancellable) {
                        cleanupEntered.complete(Unit)
                        cleanup.await()
                        waitsClosed++
                    }
                }
            }
            override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> =
                error("The common facade owns completion fan-in")
        }
        val ring = FunctionalUringFacade(2, backend)
        val first = async { ring.batchEnqueue(s_[Submissions.nop(1)]) }
        runCurrent()
        val second = async { ring.batchEnqueue(s_[Submissions.nop(2)]) }
        try {
            runCurrent()
            assertEquals(2L, second.await()[0].userData)
            assertTrue(cleanupEntered.isCompleted, "A completed batch cancels its now-obsolete readiness observation")
            assertFalse(first.isCompleted, "The batch must join wait-resource cleanup before returning")
            cleanup.complete(Unit)
            runCurrent()
            assertTrue(first.isCompleted)
            assertEquals(1L, first.await()[0].userData)
            assertEquals(1, waitsClosed)
            ring.drain()
            assertEquals(0L, testScheduler.currentTime)
        } finally {
            cleanup.complete(Unit)
            parked.complete(Unit)
            first.join()
            second.join()
            ring.drain()
        }
    }
}
