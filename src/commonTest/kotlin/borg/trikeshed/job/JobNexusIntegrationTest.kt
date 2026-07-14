package borg.trikeshed.job

import borg.trikeshed.cursor.blackboardContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * I2 — Rules to supervised work to projections: integration RED test.
 *
 * End-to-end: command → CAS/WAL → reducer → index → fact → Rete → command
 * Multiple children, one fails, sibling survives, retry unblocks.
 *
 * Every type referenced is NEW.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JobNexusIntegrationTest {

    @Test
    fun endToEndDependencyResolutionFailureAndRetry() = runTest {
        val nexus = JobSupervisorElement.open(scope = this, capacity = 64)

        // Submit parent + two children
        nexus.submit(JobCommand.Submit("parent", idempotencyKey = "k-parent",
            dependencies = listOf("child-a", "child-b")))
        nexus.submit(JobCommand.Submit("child-a", idempotencyKey = "k-a"))
        nexus.submit(JobCommand.Submit("child-b", idempotencyKey = "k-b"))

        advanceUntilIdle()

        // Commit dependency facts: child-a succeeds, child-b fails
        nexus.submit(JobCommand.Complete("child-a", idempotencyKey = "k-a-done",
            expectedRevision = 1))
        nexus.submit(JobCommand.Fail("child-b", idempotencyKey = "k-b-fail",
            expectedRevision = 1, reason = "boom"))

        advanceUntilIdle()

        // Parent must be blocked because child-b failed
        val parent = nexus.snapshot("parent")!!
        assertEquals("blocked", parent.lifecycle,
            "parent must be blocked when a dependency fails")

        // child-a is closed
        val childA = nexus.snapshot("child-a")!!
        assertEquals("closed", childA.lifecycle)

        // Retry child-b: new attempt, prior history retained
        nexus.submit(JobCommand.Retry("child-b", idempotencyKey = "k-b-retry",
            expectedRevision = 2))
        advanceUntilIdle()

        val childB = nexus.snapshot("child-b")!!
        assertTrue(childB.attemptCount >= 2, "retry must create a new attempt")

        // Complete child-b on retry
        nexus.submit(JobCommand.Complete("child-b", idempotencyKey = "k-b-done",
            expectedRevision = 3))
        advanceUntilIdle()

        // Parent should now be ready (all deps satisfied)
        val parentFinal = nexus.snapshot("parent")!!
        assertEquals("ready", parentFinal.lifecycle,
            "parent must be ready after all dependencies complete")
    }

    @Test
    fun failureCommitsEvidenceAndBlocksDependent() = runTest {
        val nexus = JobSupervisorElement.open(scope = this, capacity = 64)

        nexus.submit(JobCommand.Submit("dep", idempotencyKey = "k1"))
        nexus.submit(JobCommand.Submit("job", idempotencyKey = "k2",
            dependencies = listOf("dep")))
        advanceUntilIdle()

        // Fail the dependency
        nexus.submit(JobCommand.Fail("dep", idempotencyKey = "k3",
            expectedRevision = 1, reason = "dependency exploded"))
        advanceUntilIdle()

        val job = nexus.snapshot("job")!!
        assertEquals("blocked", job.lifecycle,
            "job must be blocked when its dependency fails")

        // Evidence must be committed
        val evidence = nexus.facts("dep")
        assertTrue(evidence.any { it.contains("fail") || it.contains("dependency") },
            "failure evidence must be committed")
    }

    @Test
    fun successfulSiblingRemainsAfterOtherFails() = runTest {
        val nexus = JobSupervisorElement.open(scope = this, capacity = 64)

        nexus.submit(JobCommand.Submit("sib-a", idempotencyKey = "k1"))
        nexus.submit(JobCommand.Submit("sib-b", idempotencyKey = "k2"))
        advanceUntilIdle()

        nexus.submit(JobCommand.Fail("sib-a", idempotencyKey = "k3",
            expectedRevision = 1, reason = "crash"))
        advanceUntilIdle()

        // sib-b must still be committed and active
        val sibB = nexus.snapshot("sib-b")!!
        assertEquals("submitted", sibB.lifecycle, "sibling must not be affected by another sibling's failure")
        assertFalse(sibB.lifecycle == "failed", "sibling must not fail when another fails")
    }
}
