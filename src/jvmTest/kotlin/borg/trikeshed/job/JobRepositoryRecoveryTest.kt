package borg.trikeshed.job

import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.collections.btree.BTreeKey
import borg.trikeshed.collections.btree.BTreeValue
import borg.trikeshed.collections.btree.CowBPlusTree
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class JobRepositoryRecoveryTest {

    class MockLog : DurableAppendLog {
        private val data = mutableListOf<Pair<Long, ByteArray>>()
        private var corruptAfter: Long = -1

        override fun append(sequence: Long, payload: ByteArray): Long {
            data.add(sequence to payload)
            return sequence
        }

        override suspend fun replay(onFrame: suspend (Long, ByteArray) -> Unit): Long {
            var lastSeq = 0L
            for ((seq, payload) in data) {
                if (corruptAfter != -1L && seq > corruptAfter) {
                    break // Simulate corruption
                }
                onFrame(seq, payload)
                lastSeq = seq
            }
            return lastSeq
        }

        override fun flush() {}
        override fun injectCorruptionAfter(sequence: Long) {
            corruptAfter = sequence
        }
    }

    private fun createSnapshot(jobId: String, rev: Long): JobSnapshot = JobSnapshot(
        jobId = JobId.of(jobId),
        revision = rev,
        causalKey = "causal",
        lifecycle = "running",
        dependencies = emptyList(),
        attemptCount = 1,
        parentJobId = null,
        attemptId = "attempt"
    )

    @Test
    fun testCheckpointAndTailRecovery() = runBlocking {
        val log = MockLog()
        val casStore = CasStore.inMemory()
        val repo = JobRepository(log, casStore)

        // Write some normal commits
        repo.commit("j1", createSnapshot("j1", 1), byteArrayOf(1))
        repo.commit("j2", createSnapshot("j2", 1), byteArrayOf(2))

        // Create a B+Tree
        val tree = CowBPlusTree(casStore)
        var rootCid = tree.insert(null, BTreeKey("f", byteArrayOf(1), JobId.of("j1"), 1L), BTreeValue(ContentId("sha256:0000000000000000000000000000000000000000000000000000000000000000"), 1L))

        // Put schema in CAS
        val schemaCid = casStore.put(byteArrayOf(99))

        // Write a checkpoint
        val cp = JobCheckpoint(2L, rootCid, schemaCid, emptyMap())
        repo.checkpoint(cp)

        // Write tail commits after checkpoint
        repo.commit("j3", createSnapshot("j3", 1), byteArrayOf(3))

        // Recover
        val repo2 = JobRepository(log, casStore)
        val result = repo2.recover()

        assertEquals(4L, result.committedSequence)
        assertNotNull(result.checkpoint)
        assertEquals(2L, result.checkpoint!!.committedSequence)
        assertEquals(rootCid, result.checkpoint!!.rootCid)

        // Snapshot j1 and j2 should be discarded due to checkpoint, but j3 should be present
        assertNull(result.snapshot("j1"))
        assertNull(result.snapshot("j2"))
        assertNotNull(result.snapshot("j3"))
    }

    private fun assertNull(actual: Any?) {
        assertEquals(null, actual)
    }

    @Test
    fun testMissingPageRejection() = runBlocking {
        val log = MockLog()
        val casStore = CasStore.inMemory()
        val repo = JobRepository(log, casStore)

        val tree = CowBPlusTree(casStore)
        var rootCid = tree.insert(null, BTreeKey("f", byteArrayOf(1), JobId.of("j1"), 1L), BTreeValue(ContentId("sha256:0000000000000000000000000000000000000000000000000000000000000000"), 1L))
        val schemaCid = casStore.put(byteArrayOf(99))
        val cp = JobCheckpoint(0L, rootCid, schemaCid, emptyMap())
        repo.checkpoint(cp)

        // Corrupt CAS by removing the root page
        casStore.corrupt(rootCid)

        // Actually CasStore.corrupt flips a bit, to really miss it let's make a new store that doesn't have it
        val badCasStore = CasStore.inMemory()
        badCasStore.put(byteArrayOf(99)) // put schema

        val repo2 = JobRepository(log, badCasStore)
        assertFailsWith<IllegalStateException> {
            repo2.recover()
        }
    }

    @Test
    fun testCorruptPageRejection() = runBlocking {
        val log = MockLog()
        val casStore = CasStore.inMemory()
        val repo = JobRepository(log, casStore)

        val tree = CowBPlusTree(casStore)
        var rootCid = tree.insert(null, BTreeKey("f", byteArrayOf(1), JobId.of("j1"), 1L), BTreeValue(ContentId("sha256:0000000000000000000000000000000000000000000000000000000000000000"), 1L))
        val schemaCid = casStore.put(byteArrayOf(99))
        val cp = JobCheckpoint(0L, rootCid, schemaCid, emptyMap())
        repo.checkpoint(cp)

        casStore.corrupt(rootCid)

        val repo2 = JobRepository(log, casStore)
        assertFailsWith<IllegalStateException> {
            repo2.recover() // Will fail when trying to decode the corrupted bytes
        }
    }

    @Test
    fun testIdempotence() = runBlocking {
        val log = MockLog()
        val casStore = CasStore.inMemory()
        val repo = JobRepository(log, casStore)

        val tree = CowBPlusTree(casStore)
        var rootCid = tree.insert(null, BTreeKey("f", byteArrayOf(1), JobId.of("j1"), 1L), BTreeValue(ContentId("sha256:0000000000000000000000000000000000000000000000000000000000000000"), 1L))
        val schemaCid = casStore.put(byteArrayOf(99))
        val cp = JobCheckpoint(0L, rootCid, schemaCid, emptyMap())
        repo.checkpoint(cp)

        val repo2 = JobRepository(log, casStore)
        val result1 = repo2.recover()
        val result2 = repo2.recover() // Second time

        assertEquals(result1.committedSequence, result2.committedSequence)
        assertEquals(result1.checkpoint?.rootCid, result2.checkpoint?.rootCid)
    }
}
