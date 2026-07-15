package borg.trikeshed.job

import borg.trikeshed.couch.isam.JvmDurableAppendLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Recovery proves WAL framing, CAS verification, and deterministic committed heads. */
@OptIn(ExperimentalCoroutinesApi::class)
class JobRepositoryRecoveryTest {

    private fun tempLog(): Pair<File, JvmDurableAppendLog> {
        val file = File.createTempFile("job-repository", ".wal")
        file.deleteOnExit()
        return file to JvmDurableAppendLog(file)
    }

    @Test
    fun recoveryFromWalReplayYieldsDeterministicHead() = runTest {
        val (_, log) = tempLog()
        val casStore = CasStore.inMemory()
        val repo = JobRepository(log, casStore)

        for (i in 1..5) {
            val jobId = JobId.of("j-$i")
            repo.commit(
                jobId = jobId,
                snapshot = JobSnapshot(jobId, 1, "ck-$i", "submitted", listOf(JobId.of("j-dep"))),
                payload = "frame-$i".encodeToByteArray(),
            )
        }

        val recovered = repo.recover()
        assertEquals(5L, recovered.committedSequence)
        assertEquals("submitted", recovered.snapshot("j-1")?.lifecycle)
        assertEquals("submitted", recovered.snapshot("j-5")?.lifecycle)
    }

    @Test
    fun recoveryFromTornWalStopsAtLastValidFrame() = runTest {
        val (file, log) = tempLog()
        val casStore = CasStore.inMemory()
        val repo = JobRepository(log, casStore)

        for (i in 1..3) {
            val jobId = JobId.of("j-$i")
            repo.commit(
                jobId,
                JobSnapshot(jobId, 1, "ck-$i", "submitted", emptyList()),
                "frame-$i".encodeToByteArray(),
            )
        }
        repo.injectCorruptionAfter(sequence = 3L)

        val recovered = JobRepository(JvmDurableAppendLog(file), casStore).recover()
        assertEquals(3L, recovered.committedSequence)
        assertEquals("submitted", recovered.snapshot("j-3")?.lifecycle)
    }

    @Test
    fun recoveryIsIdempotentAcrossTwoReads() = runTest {
        val (file, log) = tempLog()
        val casStore = CasStore.inMemory()
        val repo = JobRepository(log, casStore)
        val jobId1 = JobId.of("j-1")
        val jobId2 = JobId.of("j-2")

        repo.commit(jobId1, JobSnapshot(jobId1, 1, "ck-1", "submitted", emptyList()), "a".encodeToByteArray())
        repo.commit(jobId2, JobSnapshot(jobId2, 1, "ck-2", "submitted", emptyList()), "b".encodeToByteArray())

        val first = JobRepository(JvmDurableAppendLog(file), casStore).recover()
        val second = JobRepository(JvmDurableAppendLog(file), casStore).recover()
        assertEquals(first.committedSequence, second.committedSequence)
        assertEquals(first.snapshot(jobId1), second.snapshot(jobId1))
        assertEquals(first.snapshot(jobId2), second.snapshot(jobId2))
    }

    @Test
    fun recoveryDropsHeadWhoseCasBlobIsCorruptButRetainsSequence() = runTest {
        val (file, log) = tempLog()
        val casStore = CasStore.inMemory()
        val repo = JobRepository(log, casStore)
        val jobId1 = JobId.of("j-1")
        val jobId2 = JobId.of("j-2")
        val snapshot1 = JobSnapshot(jobId1, 1, "ck-1", "submitted", emptyList())
        val snapshot2 = JobSnapshot(jobId2, 1, "ck-2", "submitted", emptyList())

        repo.commit(jobId1, snapshot1, "a".encodeToByteArray())
        repo.commit(jobId2, snapshot2, "b".encodeToByteArray())
        casStore.corrupt(ContentId.of(CanonicalCbor.encode(snapshot2)))

        val recovered = JobRepository(JvmDurableAppendLog(file), casStore).recover()
        assertEquals(2L, recovered.committedSequence)
        assertEquals(snapshot1, recovered.snapshot(jobId1))
        assertNull(recovered.snapshot(jobId2))
    }

    @Test
    fun unreferencedCasBlobDoesNotCreateAHead() = runTest {
        val (_, log) = tempLog()
        val casStore = CasStore.inMemory()
        casStore.put("orphan".encodeToByteArray())

        val recovered = JobRepository(log, casStore).recover()
        assertEquals(0L, recovered.committedSequence)
        assertNull(recovered.snapshot("orphan"))
    }
}
