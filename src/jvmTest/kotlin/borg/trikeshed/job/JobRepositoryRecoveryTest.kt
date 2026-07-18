package borg.trikeshed.job

import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.collections.btree.BTreeKey
import borg.trikeshed.collections.btree.BTreeNode
import borg.trikeshed.collections.btree.BTreeValue
import borg.trikeshed.collections.btree.CowBPlusTree
import borg.trikeshed.collections.btree.CowBPlusTreeCodec
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
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

        // Pre-checkpoint state
        val j1Snap = createSnapshot("j1", 1)
        val j2Snap = createSnapshot("j2", 1)

        // Write normal commits
        repo.commit("j1", j1Snap, byteArrayOf(1))
        repo.commit("j2", j2Snap, byteArrayOf(2))

        // Create a B+Tree and properly put CanonicalCbor snapshots in CasStore for the B+Tree values
        val tree = CowBPlusTree(casStore)
        val j1Cid = casStore.put(CanonicalCbor.encode(j1Snap))
        val j2Cid = casStore.put(CanonicalCbor.encode(j2Snap))

        var rootCid = tree.insert(null, BTreeKey("f", byteArrayOf(1), JobId.of("j1"), 1L), BTreeValue(j1Cid, 1L))
        rootCid = tree.insert(rootCid, BTreeKey("f", byteArrayOf(2), JobId.of("j2"), 1L), BTreeValue(j2Cid, 2L))

        // Put schema in CAS
        val schemaCid = casStore.put(byteArrayOf(99))

        // Write a checkpoint
        val cp = JobCheckpoint(2L, rootCid, schemaCid, emptyMap())
        repo.checkpoint(cp)

        // Write tail commits after checkpoint
        // j3 is a completely new job in tail
        repo.commit("j3", createSnapshot("j3", 1), byteArrayOf(3))
        // j1 is updated in tail (it should win over the checkpointed j1)
        val j1UpdatedSnap = createSnapshot("j1", 2)
        repo.commit("j1", j1UpdatedSnap, byteArrayOf(4))

        // Recover
        val repo2 = JobRepository(log, casStore)
        val result = repo2.recover()

        assertEquals(5L, result.committedSequence)
        assertNotNull(result.checkpoint)
        assertEquals(2L, result.checkpoint!!.committedSequence)
        assertEquals(rootCid, result.checkpoint!!.rootCid)

        // Verify j1 is present and the tail version won
        val recoveredJ1 = result.snapshot("j1")
        assertNotNull(recoveredJ1)
        assertEquals(2L, recoveredJ1.revision)

        // Verify j2 is present from the checkpoint
        val recoveredJ2 = result.snapshot("j2")
        assertNotNull(recoveredJ2)
        assertEquals(1L, recoveredJ2.revision)

        // Verify j3 is present from the tail
        val recoveredJ3 = result.snapshot("j3")
        assertNotNull(recoveredJ3)
        assertEquals(1L, recoveredJ3.revision)
    }

    private fun assertNull(actual: Any?) {
        assertEquals(null, actual)
    }

    @Test
    fun testMissingPageRejection() = runBlocking {
        val log = MockLog()
        val casStore = CasStore.inMemory()
        val repo = JobRepository(log, casStore)

        val j1Snap = createSnapshot("j1", 1)
        val j1Cid = casStore.put(CanonicalCbor.encode(j1Snap))

        val tree = CowBPlusTree(casStore)
        var rootCid = tree.insert(null, BTreeKey("f", byteArrayOf(1), JobId.of("j1"), 1L), BTreeValue(j1Cid, 1L))
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

        val j1Snap = createSnapshot("j1", 1)
        val j1Cid = casStore.put(CanonicalCbor.encode(j1Snap))

        val tree = CowBPlusTree(casStore)
        var rootCid = tree.insert(null, BTreeKey("f", byteArrayOf(1), JobId.of("j1"), 1L), BTreeValue(j1Cid, 1L))
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

        val j1Snap = createSnapshot("j1", 1)
        val j1Cid = casStore.put(CanonicalCbor.encode(j1Snap))

        val tree = CowBPlusTree(casStore)
        var rootCid = tree.insert(null, BTreeKey("f", byteArrayOf(1), JobId.of("j1"), 1L), BTreeValue(j1Cid, 1L))
        val schemaCid = casStore.put(byteArrayOf(99))
        val cp = JobCheckpoint(0L, rootCid, schemaCid, emptyMap())
        repo.checkpoint(cp)

        val repo2 = JobRepository(log, casStore)
        val result1 = repo2.recover()
        val result2 = repo2.recover() // Second time

        assertEquals(result1.committedSequence, result2.committedSequence)
        assertEquals(result1.checkpoint?.rootCid, result2.checkpoint?.rootCid)
    }

    @Test
    fun testCorruptValuePageRejection() = runBlocking {
        val log = MockLog()
        val casStore = CasStore.inMemory()
        val repo = JobRepository(log, casStore)

        val j1Snap = createSnapshot("j1", 1)
        val j1Cid = casStore.put(CanonicalCbor.encode(j1Snap))

        val tree = CowBPlusTree(casStore)
        val rootCid = tree.insert(null, BTreeKey("f", byteArrayOf(1), JobId.of("j1"), 1L), BTreeValue(j1Cid, 1L))
        val schemaCid = casStore.put(byteArrayOf(99))
        val cp = JobCheckpoint(0L, rootCid, schemaCid, emptyMap())
        repo.checkpoint(cp)

        casStore.corrupt(j1Cid) // Corrupt the actual value page, not the tree node

        val repo2 = JobRepository(log, casStore)
        assertFailsWith<IllegalStateException> {
            repo2.recover()
        }
    }

    @Test
    fun testEmptyTreeRecovery() = runBlocking {
        val log = MockLog()
        val casStore = CasStore.inMemory()
        val repo = JobRepository(log, casStore)

        val tree = CowBPlusTree(casStore)
        val emptyRootCid = casStore.put(CowBPlusTreeCodec.encode(BTreeNode.Leaf(emptyList(), emptyList())))

        val schemaCid = casStore.put(byteArrayOf(99))
        val cp = JobCheckpoint(0L, emptyRootCid, schemaCid, emptyMap())
        repo.checkpoint(cp)

        val repo2 = JobRepository(log, casStore)
        val result = repo2.recover()

        assertNotNull(result.checkpoint)
        assertEquals(emptyRootCid, result.checkpoint!!.rootCid)
        assertNull(result.snapshot("j1"))
    }

    @Test
    fun testRangeTraversalRecovery() = runBlocking {
        val log = MockLog()
        val casStore = CasStore.inMemory()
        val repo = JobRepository(log, casStore)

        val tree = CowBPlusTree(casStore)
        var rootCid: ContentId? = null
        for (i in 1..50) {
            val snap = createSnapshot("j$i", i.toLong())
            val cid = casStore.put(CanonicalCbor.encode(snap))
            rootCid = tree.insert(rootCid, BTreeKey("f", byteArrayOf(i.toByte()), JobId.of("j$i"), i.toLong()), BTreeValue(cid, i.toLong()))
        }

        val schemaCid = casStore.put(byteArrayOf(99))
        val cp = JobCheckpoint(0L, rootCid!!, schemaCid, emptyMap())
        repo.checkpoint(cp)

        val repo2 = JobRepository(log, casStore)
        val result = repo2.recover()

        for (i in 1..50) {
            val recovered = result.snapshot("j$i")
            assertNotNull(recovered)
            assertEquals(i.toLong(), recovered.revision)
        }
    }

    @Test
    fun testDeterministicRestorationCoverage() = runBlocking {
        val log = MockLog()
        val casStore = CasStore.inMemory()
        val repo = JobRepository(log, casStore)

        val j1Snap = createSnapshot("j1", 1)
        val j1Cid = casStore.put(CanonicalCbor.encode(j1Snap))

        val tree = CowBPlusTree(casStore)
        val rootCid = tree.insert(null, BTreeKey("f", byteArrayOf(1), JobId.of("j1"), 1L), BTreeValue(j1Cid, 1L))
        val schemaCid = casStore.put(byteArrayOf(99))
        val cp = JobCheckpoint(0L, rootCid, schemaCid, emptyMap())
        repo.checkpoint(cp)

        val repo2 = JobRepository(log, casStore)
        val result = repo2.recover()
        val recoveredJ1 = result.snapshot("j1")
        assertNotNull(recoveredJ1)
        assertEquals("j1", recoveredJ1.jobId.value)
        assertEquals(1L, recoveredJ1.revision)
    }
}
