package borg.trikeshed.couch

import borg.trikeshed.job.ContentId
import borg.trikeshed.job.JobSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V1 — Couch changes projection RED tests.
 */
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
