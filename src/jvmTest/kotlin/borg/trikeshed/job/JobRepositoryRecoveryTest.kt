package borg.trikeshed.job

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * I1 — JobRepository recovery RED tests.
 *
 * Recovery from WAL replay produces one deterministic head, sequence,
 * index root, and activation input set.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JobRepositoryRecoveryTest {

    @Test
    fun recoveryFromWalReplayYieldsDeterministicHead() = runTest {
        val repo = JobRepository.inMemory()

        // Commit 5 frames
        for (i in 1..5) {
            repo.commit(
                jobId = "j-$i",
                snapshot = JobSnapshot("j-$i", 1, "ck-$i", "submitted", emptyList()),
                payload = "frame-$i".encodeToByteArray(),
            )
        }

        // Recover from WAL
        val recovered = repo.recover()

        assertEquals(5, recovered.committedSequence)
        assertEquals("submitted", recovered.snapshot("j-1")?.lifecycle)
        assertEquals("submitted", recovered.snapshot("j-5")?.lifecycle)
    }

    @Test
    fun recoveryFromTornWalStopsAtLastValidFrame() = runTest {
        val repo = JobRepository.inMemory()

        for (i in 1..3) {
            repo.commit(
                jobId = "j-$i",
                snapshot = JobSnapshot("j-$i", 1, "ck-$i", "submitted", emptyList()),
                payload = "frame-$i".encodeToByteArray(),
            )
        }

        // Inject corruption after frame 3
        repo.injectCorruptionAfter(sequence = 3L)

        val recovered = repo.recover()
        assertEquals(3, recovered.committedSequence, "recovery must stop at last valid frame")
    }

    @Test
    fun recoveryIdempotentAcrossTwoReads() = runTest {
        val repo = JobRepository.inMemory()
        repo.commit("j-1", JobSnapshot("j-1", 1, "ck", "submitted", emptyList()), "a".encodeToByteArray())
        repo.commit("j-2", JobSnapshot("j-2", 1, "ck", "submitted", emptyList()), "b".encodeToByteArray())

        val recovered1 = repo.recover()
        val recovered2 = repo.recover()

        assertEquals(recovered1.committedSequence, recovered2.committedSequence)
        assertEquals(recovered1.snapshot("j-1"), recovered2.snapshot("j-1"))
    }
}
