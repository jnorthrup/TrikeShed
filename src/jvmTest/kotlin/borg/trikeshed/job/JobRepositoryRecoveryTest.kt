package borg.trikeshed.job

import borg.trikeshed.couch.isam.JvmDurableAppendLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import java.io.File
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

    private fun createTempRepo(casStore: CasStore): JobRepository {
        val tempFile = File.createTempFile("wal", ".log")
        tempFile.deleteOnExit()
        val log = JvmDurableAppendLog(tempFile)
        return JobRepository(log, casStore)
    }

    private val mockCasStore = object : CasStore {
        override suspend fun contains(cid: String) = cid.startsWith("ck-") || cid.startsWith("j-")
    }

    @Test
    fun recoveryFromWalReplayYieldsDeterministicHead() = runTest {
        val repo = createTempRepo(mockCasStore)

        // Commit 5 frames
        for (i in 1..5) {
            val jobId = JobId.of("j-$i")
            repo.commit(
                jobId = jobId,
                snapshot = JobSnapshot(jobId, 1, "ck-$i", "submitted", listOf(JobId.of("j-dep"))),
                payload = "frame-$i".encodeToByteArray(),
            )
        }

        // Recover from WAL
        val recovered = repo.recover()

        assertEquals(5L, recovered.committedSequence)
        assertEquals("submitted", recovered.snapshot("j-1")?.lifecycle)
        assertEquals("submitted", recovered.snapshot("j-5")?.lifecycle)
    }

    @Test
    fun recoveryFromTornWalStopsAtLastValidFrame() = runTest {
        val tempFile = File.createTempFile("wal", ".log")
        tempFile.deleteOnExit()
        val log = JvmDurableAppendLog(tempFile)
        val repo = JobRepository(log, mockCasStore)

        for (i in 1..3) {
            val jobId = JobId.of("j-$i")
            repo.commit(
                jobId = jobId,
                snapshot = JobSnapshot(jobId, 1, "ck-$i", "submitted", emptyList()),
                payload = "frame-$i".encodeToByteArray(),
            )
        }

        // Inject corruption after frame 3
        repo.injectCorruptionAfter(sequence = 3L)

        // Force a torn frame (this is already simulated by injectCorruptionAfter)
        // Reopen to test recovery
        val recovered = JobRepository(JvmDurableAppendLog(tempFile), mockCasStore).recover()

        assertEquals(3L, recovered.committedSequence, "recovery must stop at last valid frame")
        assertEquals("submitted", recovered.snapshot("j-3")?.lifecycle)
    }

    @Test
    fun recoveryIdempotentAcrossTwoReads() = runTest {
        val tempFile = File.createTempFile("wal", ".log")
        tempFile.deleteOnExit()
        val log = JvmDurableAppendLog(tempFile)
        val repo = JobRepository(log, mockCasStore)

        val jobId1 = JobId.of("j-1")
        val jobId2 = JobId.of("j-2")

        repo.commit(jobId1, JobSnapshot(jobId1, 1, "ck-1", "submitted", emptyList()), "a".encodeToByteArray())
        repo.commit(jobId2, JobSnapshot(jobId2, 1, "ck-2", "submitted", emptyList()), "b".encodeToByteArray())

        val recovered1 = repo.recover()

        // Reopen repo for second read
        val repo2 = JobRepository(JvmDurableAppendLog(tempFile), mockCasStore)
        val recovered2 = repo2.recover()

        assertEquals(recovered1.committedSequence, recovered2.committedSequence)
        assertEquals(recovered1.snapshot(jobId1), recovered2.snapshot(jobId1))
    }

    @Test
    fun recoveryDropsFramesWithMissingCasBlobs() = runTest {
        val tempFile = File.createTempFile("wal", ".log")
        tempFile.deleteOnExit()
        val log = JvmDurableAppendLog(tempFile)
        val repo = JobRepository(log, mockCasStore)

        // Commit with existing CAS
        val jobId1 = JobId.of("j-1")
        repo.commit(jobId1, JobSnapshot(jobId1, 1, "ck-1", "submitted", emptyList()), "a".encodeToByteArray())

        // Commit with missing CAS
        val jobId2 = JobId.of("j-2")
        repo.commit(jobId2, JobSnapshot(jobId2, 1, "missing-ck", "submitted", emptyList()), "b".encodeToByteArray())

        // Commit with missing dependency
        val jobId3 = JobId.of("j-3")
        repo.commit(jobId3, JobSnapshot(jobId3, 1, "ck-3", "submitted", listOf(JobId.of("missing-dep"))), "c".encodeToByteArray())

        val recovered = JobRepository(JvmDurableAppendLog(tempFile), mockCasStore).recover()

        // Sequence continues, but snapshot shouldn't be populated for j-2 and j-3
        assertEquals(3L, recovered.committedSequence)
        assertEquals("submitted", recovered.snapshot("j-1")?.lifecycle)
        assertEquals(null, recovered.snapshot("j-2"))
        assertEquals(null, recovered.snapshot("j-3"))
    }
}
