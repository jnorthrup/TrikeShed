package borg.trikeshed.userspace

import borg.trikeshed.lib.*
import borg.trikeshed.userspace.UringOp.Companion.Submissions
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class UringTraceCompletionTest {
    @Test fun deferredHookRetainsSubmissionIdentityUntilTerminalReap() = runTest {
        val source = listOf(Submissions.nop(8), Submissions.nop(3), Submissions.nop(21))
        val submitted = mutableListOf<UringSubmission>()
        val completed = mutableListOf<Join<UringSubmission, Int>>()
        val channels = mutableListOf<Long>()
        val failures = mutableListOf<String>()
        var deferredCalls = 0
        var readinessCalls = 0
        var released = false
        var reaped = false
        val backend = object : UserspaceChannelBackend {
            override val capabilities = UringOp.NOP.mask
            override val deferredCapabilities = capabilities
            override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> =
                error("Trace used the synchronous hook for deferred submissions")
            override fun submitDeferredBatch(submissions: List<UringSubmission>): List<SelectionResult> {
                deferredCalls++
                assertEquals(source, submissions)
                return listOf(SelectionResult(7, 21))
            }
            override suspend fun awaitCompletions() { readinessCalls++; released = true }
            override fun reapCompletions(minComplete: Int): List<SelectionResult> =
                if (released && !reaped) {
                    reaped = true
                    // CQ order differs from SQ order, including a terminal cancellation result.
                    listOf(SelectionResult(-125, 3), SelectionResult(0, 8))
                } else emptyList()
            override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> =
                error("Trace bypassed the deferred submission/reap boundary")
        }
        val trace = object : UringTrace {
            override fun channel(id: Long, availability: String, nativeCapabilities: Long) { channels.add(id) }
            override fun submit(channel: Long, submission: UringSubmission) {
                assertEquals(channels.single(), channel)
                submitted.add(submission)
            }
            override fun complete(channel: Long, submission: UringSubmission, result: Int) {
                assertEquals(channels.single(), channel)
                assertSame(source.single { it.userData == submission.userData }, submission)
                completed.add(submission j result)
            }
            override fun failed(channel: Long, submission: UringSubmission, failure: String) { failures.add(failure) }
        }
        val sleeve = UringTraceBackend(backend, trace)
        assertEquals(listOf(SelectionResult(7, 21)), sleeve.submitDeferredBatch(source))
        assertEquals(1, deferredCalls)
        assertEquals(source, submitted)
        assertEquals(listOf(21L), completed.map { it.a.userData })
        assertTrue(sleeve.reapCompletions(0).isEmpty())
        sleeve.awaitCompletions()
        assertEquals(1, readinessCalls)
        assertEquals(listOf(21L), completed.map { it.a.userData }, "Readiness is not a terminal completion")
        assertEquals(listOf(SelectionResult(-125, 3), SelectionResult(0, 8)), sleeve.reapCompletions(0))
        assertEquals(listOf(21L, 3L, 8L), completed.map { it.a.userData })
        assertEquals(listOf(7, -125, 0), completed.map { it.b })
        assertTrue(sleeve.reapCompletions(0).isEmpty())
        assertEquals(3, submitted.size, "Readiness/reaping must not retrace submissions")
        assertEquals(3, completed.size, "Every terminal CQE must be traced once")
        assertTrue(failures.isEmpty())
    }
}
