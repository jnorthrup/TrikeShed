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
            assertEquals(listOf(UringCompletion(1, -13, 0), UringCompletion(2, 1, 0), UringCompletion(3, 81, 0)), result)
            assertEquals(listOf(UringOp.STATX, UringOp.NOP), backend.submissions.map { it.opcode })
            assertFailsWith<IllegalArgumentException> {
                facade.batchEnqueue(listOf(submission(6, UringOp.SETXATTR)).toSeries())
            }
            assertFailsWith<IllegalStateException> { facade.batchEnqueue(batch(4)) }
            assertEquals(81, facade.batchEnqueue(batch(5))[0].res)
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
}
