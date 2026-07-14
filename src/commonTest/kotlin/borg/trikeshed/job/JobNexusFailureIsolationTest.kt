package borg.trikeshed.job

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * I2 — Failure isolation RED tests.
 *
 * A child attempt failure cancels its attempt subtree and does not
 * cancel sibling jobs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JobNexusFailureIsolationTest {

    @Test
    fun childFailureDoesNotCancelRoot() = runTest {
        val nexus = JobSupervisorElement.open(scope = this, capacity = 64)

        nexus.submit(JobCommand.Submit("j-1", idempotencyKey = "k1"))
        nexus.submit(JobCommand.Submit("j-2", idempotencyKey = "k2"))
        advanceUntilIdle()

        nexus.submit(JobCommand.Fail("j-1", idempotencyKey = "k3", expectedRevision = 1, reason = "boom"))
        advanceUntilIdle()

        assertTrue(nexus.isActive, "nexus root must remain active after child failure")
        assertEquals("failed", nexus.snapshot("j-1")?.lifecycle)
        assertEquals("submitted", nexus.snapshot("j-2")?.lifecycle, "sibling must survive")
    }

    @Test
    fun childFailureStormDoesNotCancelRoot() = runTest {
        val nexus = JobSupervisorElement.open(scope = this, capacity = 256)

        // Submit 10 jobs and fail 5 of them
        for (i in 1..10) {
            nexus.submit(JobCommand.Submit("j-$i", idempotencyKey = "k-$i"))
        }
        advanceUntilIdle()

        for (i in 1..5) {
            nexus.submit(JobCommand.Fail("j-$i", idempotencyKey = "fail-$i",
                expectedRevision = 1, reason = "storm"))
        }
        advanceUntilIdle()

        assertTrue(nexus.isActive, "root must survive a child failure storm")
        for (i in 1..5) {
            assertEquals("failed", nexus.snapshot("j-$i")?.lifecycle)
        }
        for (i in 6..10) {
            assertEquals("submitted", nexus.snapshot("j-$i")?.lifecycle, "survivors must remain submitted")
        }
    }
}
