package borg.trikeshed.dag

import borg.trikeshed.cursor.blackboardContext
import borg.trikeshed.job.ContentId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Section 7 RED — Board never lies about running work.
 *
 * The plan: "WIP limit exceeded → block admission, not mutate the board"
 * "A card column is therefore a query over facts, not a manually maintained status string."
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardNeverLiesTest {

    @Test
    fun wipLimitExceededCardStaysInReadyColumn() = runTest {
        val nexus = JobSupervisorElement.open(scope = this, capacity = 64)
        nexus.setWipLimit(2)

        // Submit and start 2 jobs — fills WIP
        nexus.submit(JobCommand.Submit("j-1", idempotencyKey = "k1"))
        nexus.submit(JobCommand.Submit("j-2", idempotencyKey = "k2"))
        nexus.submit(JobCommand.Submit("j-3", idempotencyKey = "k3"))
        advanceUntilIdle()

        nexus.submit(JobCommand.Start("j-1", idempotencyKey = "k4", expectedRevision = 1))
        nexus.submit(JobCommand.Start("j-2", idempotencyKey = "k5", expectedRevision = 1))
        advanceUntilIdle()

        // j-3 is ready but WIP is full. Attempting to start it must be rejected.
        nexus.submit(JobCommand.Start("j-3", idempotencyKey = "k6", expectedRevision = 1))
        advanceUntilIdle()

        // j-3 must NOT be in "active" — the board never lies.
        val snap3 = nexus.snapshot("j-3")!!
        assertEquals("ready", snap3.lifecycle,
            "j-3 must remain 'ready' (not 'active') when WIP limit is exceeded")
    }

    @Test
    fun boardColumnReflectsFactStateNotManualStatus() = runTest {
        val nexus = JobSupervisorElement.open(scope = this, capacity = 64)
        nexus.submit(JobCommand.Submit("j-1", idempotencyKey = "k1", dependencies = listOf("dep-1")))
        advanceUntilIdle()

        // j-1 is submitted with an unmet dependency → must be "blocked"
        assertEquals("blocked", nexus.snapshot("j-1")!!.lifecycle)

        // Dependency completes → j-1 must become "ready"
        nexus.submit(JobCommand.Submit("dep-1", idempotencyKey = "k2"))
        nexus.submit(JobCommand.Complete("dep-1", idempotencyKey = "k3", expectedRevision = 1))
        advanceUntilIdle()

        assertEquals("ready", nexus.snapshot("j-1")!!.lifecycle,
            "card must reflect fact-derived state, not manual status")
    }

    @Test
    fun supportingFactRetractedMakesStatusDisappear() = runTest {
        val nexus = JobSupervisorElement.open(scope = this, capacity = 64)
        nexus.submit(JobCommand.Submit("dep-1", idempotencyKey = "k1"))
        nexus.submit(JobCommand.Submit("j-1", idempotencyKey = "k2", dependencies = listOf("dep-1")))
        advanceUntilIdle()

        // dep-1 completes → j-1 becomes ready
        nexus.submit(JobCommand.Complete("dep-1", idempotencyKey = "k3", expectedRevision = 1))
        advanceUntilIdle()
        assertEquals("ready", nexus.snapshot("j-1")!!.lifecycle)

        // Retract dep-1's completion — TMS retraction must make j-1 blocked again
        nexus.submit(JobCommand.Retract("dep-1", idempotencyKey = "k4", expectedRevision = 2))
        advanceUntilIdle()

        assertEquals("blocked", nexus.snapshot("j-1")!!.lifecycle,
            "TMS retraction must cause derived status to disappear")
    }
}
