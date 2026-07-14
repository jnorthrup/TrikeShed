package borg.trikeshed.job

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * I2 — Restart/replay RED tests.
 *
 * Process restart through the factory must reconstruct the same heads,
 * indexes, Rete agenda, and card view.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JobNexusRestartTest {

    @Test
    fun restartReconstructsSameHeadsAndSnapshots() = runTest {
        val walData = mutableMapOf<String, ByteArray>()

        // First session
        val nexus1 = JobSupervisorElement.open(scope = this, capacity = 64, walData = walData)

        nexus1.submit(JobCommand.Submit("j-1", idempotencyKey = "k1"))
        nexus1.submit(JobCommand.Submit("j-2", idempotencyKey = "k2"))
        nexus1.submit(JobCommand.Start("j-1", idempotencyKey = "k3", expectedRevision = 1))
        advanceUntilIdle()

        val seq1 = nexus1.committedSequence
        val snap1 = nexus1.snapshot("j-1")!!.copy()

        nexus1.drain()
        advanceUntilIdle()

        // Restart
        val nexus2 = JobSupervisorElement.open(scope = this, capacity = 64, walData = walData)

        assertEquals(seq1, nexus2.committedSequence, "replay must restore the same committed sequence")
        assertEquals(snap1, nexus2.snapshot("j-1"), "snapshots must match after restart")
    }

    @Test
    fun restartProducesByteEqualSnapshotCids() = runTest {
        val walData = mutableMapOf<String, ByteArray>()

        val nexus1 = JobSupervisorElement.open(scope = this, capacity = 64, walData = walData)
        nexus1.submit(JobCommand.Submit("j-1", idempotencyKey = "k1"))
        advanceUntilIdle()
        val cid1 = nexus1.snapshotCid("j-1")
        nexus1.drain()
        advanceUntilIdle()

        val nexus2 = JobSupervisorElement.open(scope = this, capacity = 64, walData = walData)
        val cid2 = nexus2.snapshotCid("j-1")

        assertEquals(cid1, cid2, "snapshot CIDs must be byte-equal after restart")
    }
}
