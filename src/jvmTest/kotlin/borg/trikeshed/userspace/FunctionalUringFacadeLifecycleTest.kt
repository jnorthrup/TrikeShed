package borg.trikeshed.userspace

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toList
import borg.trikeshed.lib.toSeries
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.channels.UringChannel
import borg.trikeshed.userspace.nio.ebpf.UringEbpfContextLayout
import borg.trikeshed.userspace.nio.ebpf.UringEbpfInsn
import borg.trikeshed.userspace.nio.ebpf.UringEbpfPhase
import borg.trikeshed.userspace.nio.ebpf.UringEbpfProgram
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FunctionalUringFacadeLifecycleTest {
    private class Backend(val heldToken: Long? = null, val failedToken: Long? = null) : UserspaceChannelBackend {
        override val capabilities = UringOp.caps(UringOp.NOP, UringOp.OPENAT, UringOp.WRITE, UringOp.STATX)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val submissions = mutableListOf<UringSubmission>()
        val contexts = mutableListOf<FunctionalUringFacade?>()
        var synchronousCalls = 0
        var closes = 0

        override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> {
            synchronousCalls++
            this.submissions += submissions
            return submissions.map { SelectionResult(80, it.userData) }
        }

        override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> {
            contexts += currentCoroutineContext()[FunctionalUringFacade]
            val batch = submissions.toList()
            this.submissions += batch
            if (batch.any { it.userData == heldToken }) {
                entered.complete(Unit)
                release.await()
            }
            check(batch.none { it.userData == failedToken }) { "Backend failure" }
            return batch.map { UringCompletion(it.userData, 80, 0) }.toSeries()
        }

        override fun close() { closes++ }
    }

    private fun submission(token: Long, opcode: UringOp = UringOp.NOP) =
        UringSubmission(opcode, -1, 0, 0, 0, userData = token)

    private fun batch(token: Long): Series<UringSubmission> = 1 j { _: Int -> submission(token) }

    @Test
    fun cancelled_callers_wait_for_settlement_then_throw_in_both_modes() = runTest {
        for (scoped in listOf(false, true)) {
            val backend = Backend(heldToken = 1)
            val facade = if (scoped) FunctionalUringFacade.create(this, 2, backend)
                else FunctionalUringFacade(2, backend)
            var delivered = false
            var failure: Throwable? = null
            try {
                val caller = async {
                    try {
                        facade.batchEnqueue(batch(1))
                        delivered = true
                    } catch (caught: Throwable) {
                        failure = caught
                        throw caught
                    }
                }
                backend.entered.await()
                caller.cancel()
                val draining = async { facade.drain() }
                runCurrent()
                assertFalse(caller.isCompleted)
                assertFalse(draining.isCompleted)
                assertEquals(0, backend.closes)
                backend.release.complete(Unit)
                caller.join()
                draining.await()
                assertFalse(delivered)
                assertTrue(failure is CancellationException)
                assertEquals(1, backend.closes)
            } finally {
                backend.release.complete(Unit)
                facade.drain()
            }
        }
    }

    @Test
    fun bounded_admission_and_cancelled_drain_settle_queued_work() = runTest {
        val owner = Job(coroutineContext[Job])
        val backend = Backend(heldToken = 1)
        val facade = FunctionalUringFacade.create(CoroutineScope(coroutineContext + owner), 2, backend)
        try {
            val first = async { facade.batchEnqueue(batch(1)) }
            backend.entered.await()
            val queued = async { facade.batchEnqueue(batch(2)) }
            val waiting = async { facade.batchEnqueue(batch(3)) }
            runCurrent()
            queued.cancel()
            waiting.cancel()
            runCurrent()
            assertFalse(queued.isCompleted)
            assertTrue(waiting.isCompleted)
            assertEquals(listOf(1L), backend.submissions.map { it.userData })

            val draining = async { facade.drain() }
            runCurrent()
            draining.cancel()
            runCurrent()
            assertFalse(draining.isCompleted)
            assertEquals(0, backend.closes)
            assertFailsWith<IllegalStateException> { facade.batchEnqueue(batch(4)) }
            backend.release.complete(Unit)
            assertEquals(1L, first.await()[0].userData)
            queued.join()
            draining.join()
            assertEquals(listOf(1L, 2L), backend.submissions.map { it.userData })
            assertEquals(0, backend.synchronousCalls)
            assertEquals(1, backend.closes)
            assertTrue(owner.children.none())
            backend.contexts.forEach { assertSame(facade, it) }
            assertSame(facade, (coroutineContext + facade)[FunctionalUringFacade.Key])
        } finally {
            backend.release.complete(Unit)
            facade.drain()
            owner.complete()
            owner.join()
        }
    }

    @Test
    fun owner_cancellation_drains_admitted_work_and_joins_before_closing() = runTest {
        val owner = Job(coroutineContext[Job])
        val backend = Backend(heldToken = 1)
        val facade = FunctionalUringFacade.create(CoroutineScope(coroutineContext + owner), 2, backend)
        try {
            val first = async { facade.batchEnqueue(batch(1)) }
            backend.entered.await()
            val queued = async { facade.batchEnqueue(batch(2)) }
            runCurrent()
            owner.cancel()
            runCurrent()
            assertFalse(owner.isCompleted)
            assertEquals(0, backend.closes)
            assertFailsWith<IllegalStateException> { facade.batchEnqueue(batch(3)) }
            backend.release.complete(Unit)
            first.await()
            queued.await()
            owner.join()
            assertEquals(listOf(1L, 2L), backend.submissions.map { it.userData })
            assertEquals(1, backend.closes)
            assertTrue(owner.children.none())
        } finally {
            backend.release.complete(Unit)
            facade.drain()
            owner.cancel()
            owner.join()
        }
    }

    @Test
    fun scoped_processing_preserves_containment_hooks_and_failure_isolation() = runTest {
        val rejectWrites = UringEbpfProgram.of("reject-write", UringEbpfPhase.SUBMIT, longArrayOf(
            UringEbpfInsn.loadCtx32(UringEbpfInsn.R0, UringEbpfContextLayout.OPCODE),
            UringEbpfInsn.jne64Imm(UringEbpfInsn.R0, UringOp.WRITE.code, offset = 2),
            UringEbpfInsn.mov64Imm(UringEbpfInsn.R0, -13),
            UringEbpfInsn.exit(),
            UringEbpfInsn.mov64Imm(UringEbpfInsn.R0, 0),
            UringEbpfInsn.exit(),
        ))
        val addOne = UringEbpfProgram.of("add-one", UringEbpfPhase.COMPLETE, longArrayOf(
            UringEbpfInsn.loadCtx64(UringEbpfInsn.R0, UringEbpfContextLayout.COMPLETION_RES),
            UringEbpfInsn.add64Imm(UringEbpfInsn.R0, 1),
            UringEbpfInsn.exit(),
        ))
        val backend = Backend(failedToken = 4)
        val facade = FunctionalUringFacade.create(this, 8, backend, listOf(rejectWrites, addOne))
        try {
            val result = facade.batchEnqueue(listOf(
                submission(1, UringOp.WRITE), submission(2, UringOp.STATX), submission(3),
            ).toSeries()).toList()
            assertEquals(listOf(UringCompletion(1, -13, 0), UringCompletion(2, 80, 0), UringCompletion(3, 80, 0)), result,
                "completion programs must preserve the backend's actual result")
            assertEquals(listOf(UringOp.STATX, UringOp.NOP), backend.submissions.map { it.opcode })
            assertEquals(UringCompletion(6, -13, 0), facade.batchEnqueue(listOf(submission(6, UringOp.SETXATTR)).toSeries())[0],
                "an admitted forbidden effect settles exactly one rejection CQE")
            assertFailsWith<IllegalStateException> { facade.batchEnqueue(batch(4)) }
            assertEquals(80, facade.batchEnqueue(batch(5))[0].res)
            assertEquals(0, backend.synchronousCalls)
            assertEquals(0, backend.closes)
        } finally {
            facade.drain()
        }
        assertEquals(1, backend.closes)
    }

    @Test
    fun raw_enqueue_and_synchronous_close_preserve_pending_completions() {
        val backend = Backend()
        val channel = UringChannel(FunctionalUringFacade(2, backend))
        channel.enqueue(UringOp.Companion.Submissions.openat("compatibility.img", 0, 7))
        channel.closeNow()
        channel.closeNow()
        assertEquals(listOf(UringOp.OPENAT), backend.submissions.map { it.opcode })
        assertEquals(listOf(SelectionResult(80, 7)), channel.peek())
        assertEquals(1, backend.synchronousCalls)
        assertEquals(1, backend.closes)
        assertFailsWith<IllegalStateException> { channel.enqueue(submission(8)) }
    }

    @Test
    fun failedDeferredTransportAndCancellationRetainTheDestinationUntilItsCqe() = runTest {
        val destination = mapMemory(0, 65536, 3, 2 or 0x20, -1, 0)
        var pending: UringSubmission? = null
        var failCancellation = true
        var backendClosed = false
        val completed = mutableListOf<SelectionResult>()
        val backend = object : UserspaceChannelBackend {
            override val capabilities = UringOp.READ.mask
            override val deferredCapabilities = UringOp.READ.mask
            override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> = error("unexpected sync submission")
            override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> {
                pending = submissions[0]
                error("transport failed before the READ completion arrived")
            }
            override fun cancelPending() {
                if (failCancellation) error("cancellation transport is unavailable")
                pending?.let {
                    // The admitted READ wins the cancellation race and must still access its destination.
                    checkNotNull(it.memory)[0] = 42
                    completed.add(SelectionResult(1, it.userData))
                    pending = null
                }
            }
            override fun reapCompletions(minComplete: Int): List<SelectionResult> =
                completed.toList().also { completed.clear() }
            override fun close() { check(pending == null); backendClosed = true }
        }
        val facade = FunctionalUringFacade(2, backend)
        try {
            val read = UringSubmission(UringOp.READ, 17, destination.address, 1, 0, userData = 1, memory = destination)
            assertFailsWith<IllegalStateException> { facade.batchEnqueue(1 j { _: Int -> read }) }
            assertFailsWith<IllegalStateException> { destination.close() }
            assertFailsWith<IllegalStateException> { facade.drain() }
            assertFalse(backendClosed)
            assertFailsWith<IllegalStateException> { destination.close() }
            failCancellation = false
            facade.drain()
            assertEquals(42.toByte(), destination[0])
            assertEquals(listOf(SelectionResult(1, 1)), facade.wait(0))
            destination.close()
            assertFalse(destination.isOpen)
            assertTrue(backendClosed)
        } finally {
            failCancellation = false
            try { facade.drain() } finally { destination.close() }
        }
    }

    @Test
    fun foreignCompletionFailureStillPreservesLaterTerminalCompletions() = runTest {
        val destination = mapMemory(0, 65536, 3, 2 or 0x20, -1, 0)
        val backend = object : UserspaceChannelBackend {
            override val capabilities = UringOp.caps(UringOp.READ, UringOp.NOP)
            override val deferredCapabilities = UringOp.READ.mask
            override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> = emptyList()
            override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> {
                destination[0] = 11
                destination[1] = 12
                return listOf(
                    UringCompletion(1, 1, 0), UringCompletion(1, 1, 0),
                    UringCompletion(2, 1, 0), UringCompletion(submissions[0].userData, 0, 0),
                ).toSeries()
            }
        }
        val facade = FunctionalUringFacade(4, backend)
        try {
            facade.enqueue(UringSubmission(UringOp.READ, 17, destination.address, 1, 0, userData = 1, memory = destination))
            facade.enqueue(UringSubmission(UringOp.READ, 17, destination.address + 1, 1, 1, userData = 2, memory = destination))
            assertEquals(2, facade.submit())
            assertFailsWith<IllegalStateException> { destination.close() }
            assertFailsWith<IllegalStateException> { facade.batchEnqueue(batch(3)) }
            assertEquals(listOf(SelectionResult(1, 1), SelectionResult(1, 2)), facade.wait(0))
            assertEquals(11.toByte(), destination[0])
            assertEquals(12.toByte(), destination[1])
            destination.close()
            assertFalse(destination.isOpen)
        } finally {
            try { facade.drain() } finally { destination.close() }
        }
    }

    @Test
    fun failedBackendCloseCanBeRetriedToReleaseItsResourceInBothModes() = runTest {
        for (scoped in listOf(false, true)) {
            val resource = mapMemory(0, 65536, 3, 2 or 0x20, -1, 0)
            var failClose = true
            val backend = object : UserspaceChannelBackend {
                override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> = error("unexpected submission")
                override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> = error("unexpected submission")
                override fun close() {
                    if (failClose) error("resource release is temporarily unavailable")
                    resource.close()
                }
            }
            val facade = if (scoped) FunctionalUringFacade.create(this, 1, backend) else FunctionalUringFacade(1, backend)
            try {
                assertFailsWith<IllegalStateException> { facade.drain() }
                assertTrue(resource.isOpen)
                resource[0] = 27
                assertEquals(27.toByte(), resource[0])
                failClose = false
                facade.drain()
                assertFalse(resource.isOpen, "retry must release the actual backend resource; scoped=$scoped")
            } finally {
                failClose = false
                try { runCatching { facade.drain() } } finally { resource.close() }
            }
        }
    }
}
