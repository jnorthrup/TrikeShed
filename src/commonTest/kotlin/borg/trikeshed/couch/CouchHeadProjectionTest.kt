package borg.trikeshed.couch

import borg.trikeshed.job.ContentId
import borg.trikeshed.job.JobSnapshot
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * V1 — Couch and Forge Kanban projections RED tests.
 *
 * Proves: Couch heads/revisions/changes and Kanban card views are derived
 * from committed job frames, not independent mutable state.
 *
 * Every type referenced is NEW — none exist yet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CouchHeadProjectionTest {

    /**
     * _id points to the current head CID.
     * _rev is generation-cidPrefix, derived from committed ancestry.
     */
    @Test
    fun couchHeadPointsToCurrentCidAndRevIsGenerationCid() {
        val store = ConfixRepositoryView()

        val cid1 = ContentId.of("v1".encodeToByteArray())
        val snap1 = JobSnapshot("j-1", 1, "ck-1", "submitted", emptyList())
        store.commit("j-1", snap1, cid1)

        val head = store.head("j-1")
        assertNotNull(head)
        assertEquals(cid1, head.cid)
        assertEquals("1-${cid1.value.take(8)}", head.rev)

        // Second commit — revision increments
        val cid2 = ContentId.of("v2".encodeToByteArray())
        val snap2 = snap1.copy(revision = 2, lifecycle = "active")
        store.commit("j-1", snap2, cid2)

        val head2 = store.head("j-1")
        assertNotNull(head2)
        assertEquals(cid2, head2.cid, "head must point to the new CID")
        assertEquals("2-${cid2.value.take(8)}", head2.rev)
    }

    /**
     * Stale _rev cannot move the head.
     */
    @Test
    fun staleRevCannotMoveHead() {
        val store = ConfixRepositoryView()

        val cid1 = ContentId.of("v1".encodeToByteArray())
        store.commit("j-1", JobSnapshot("j-1", 1, "ck", "submitted", emptyList()), cid1)

        // Attempt with stale expected rev
        val cid2 = ContentId.of("v2".encodeToByteArray())
        val result = store.tryCommit("j-1",
            JobSnapshot("j-1", 2, "ck", "active", emptyList()), cid2, expectedRev = "99-deadbeef")

        assertTrue(!result.accepted, "stale rev must be rejected")
        val head = store.head("j-1")
        assertEquals(cid1, head!!.cid, "head must still point to the old CID")
    }

    /**
     * Delete commits a tombstone; it does not erase history.
     */
    @Test
    fun deleteCommitsTombstoneNotEraseHistory() {
        val store = ConfixRepositoryView()
        val cid1 = ContentId.of("v1".encodeToByteArray())
        store.commit("j-1", JobSnapshot("j-1", 1, "ck", "submitted", emptyList()), cid1)

        store.delete("j-1", expectedRev = "1-${cid1.value.take(8)}")

        val head = store.head("j-1")
        assertNotNull(head)
        assertTrue(head.deleted, "head must be marked deleted")
    }

    /**
     * _changes is the durable sequence projection and resumes without duplicates or gaps.
     */
    @Test
    fun changesResumesBySequenceWithoutDuplicates() {
        val store = ConfixRepositoryView()
        val cid1 = ContentId.of("a".encodeToByteArray())
        val cid2 = ContentId.of("b".encodeToByteArray())
        val cid3 = ContentId.of("c".encodeToByteArray())

        store.commit("j-1", JobSnapshot("j-1", 1, "ck", "submitted", emptyList()), cid1)
        store.commit("j-1", JobSnapshot("j-1", 2, "ck", "active", emptyList()), cid2)
        store.commit("j-2", JobSnapshot("j-2", 1, "ck2", "submitted", emptyList()), cid3)

        // Read all from beginning
        val all = store.changes(since = 0L).toList()
        assertEquals(3, all.size)
        assertEquals(1L, all[0].sequence)
        assertEquals(2L, all[1].sequence)
        assertEquals(3L, all[2].sequence)

        // Resume from sequence 2 — should get only seq 3
        val resumed = store.changes(since = 2L).toList()
        assertEquals(1, resumed.size)
        assertEquals(3L, resumed[0].sequence)
    }

    /**
     * Repeated idempotency key returns the prior result.
     */
    @Test
    fun repeatedIdempotencyKeyReturnsPriorResult() {
        val store = ConfixRepositoryView()
        val cid1 = ContentId.of("v1".encodeToByteArray())
        val snap = JobSnapshot("j-1", 1, "ck", "submitted", emptyList())

        val first = store.commitIdempotent("j-1", snap, cid1, idempotencyKey = "k1")
        val second = store.commitIdempotent("j-1", snap, cid1, idempotencyKey = "k1")

        assertTrue(first.accepted)
        assertTrue(!second.accepted, "duplicate idempotency key must be rejected")
        assertEquals(first.sequence, second.sequence, "must return the same sequence number")
    }
}

class CouchChangesProjectionTest {

    /**
     * A missed live-tail event is recovered by sequence.
     */
    @Test
    fun missedLiveTailEventRecoveredBySequence() {
        val store = ConfixRepositoryView()

        // Commit 5 frames
        for (i in 1..5) {
            store.commit("j-$i",
                JobSnapshot("j-$i", 1, "ck-$i", "submitted", emptyList()),
                ContentId.of("v$i".encodeToByteArray()))
        }

        // Consumer saw up to sequence 3, then missed the live tail
        val missed = store.changes(since = 3L).toList()
        assertEquals(2, missed.size, "must recover seq 4 and 5")
        assertEquals(4L, missed[0].sequence)
        assertEquals(5L, missed[1].sequence)
    }

    /**
     * Changes sequence has no gaps.
     */
    @Test
    fun changesSequenceHasNoGaps() {
        val store = ConfixRepositoryView()
        for (i in 1..10) {
            store.commit("j-$i",
                JobSnapshot("j-$i", 1, "ck-$i", "submitted", emptyList()),
                ContentId.of("v$i".encodeToByteArray()))
        }

        val seqs = store.changes(since = 0L).map { it.sequence }.toList()
        val expected = (1L..10L).toList()
        assertEquals(expected, seqs, "sequence must be contiguous with no gaps")
    }
}
