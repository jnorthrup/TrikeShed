package borg.trikeshed.kanban

import borg.trikeshed.job.ContentId
import borg.trikeshed.job.JobSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * V1 — Forge Kanban projection RED tests.
 *
 * Proves: Kanban cards are projections of committed job snapshots,
 * not independently mutable state. Moving a card emits a command,
 * not a direct mutation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JobKanbanProjectionTest {

    /**
     * A job snapshot projects to the same card after replay.
     */
    @Test
    fun jobSnapshotProjectsToCard() {
        val snap = JobSnapshot("j-1", 1, "ck-1", "submitted", listOf("dep-1"))
        val card = JobKanbanProjection.projectToCard(snap)
        assertEquals("j-1", card.jobId)
        assertEquals("submitted", card.lifecycle)
        assertEquals("ck-1", card.causalKey)
        assertEquals(listOf("dep-1"), card.dependencies)
    }

    /**
     * Column is derived from lifecycle (C09).
     */
    @Test
    fun columnDerivedFromLifecycle() {
        assertEquals("col-causal-blocked",
            JobKanbanProjection.projectToCard(JobSnapshot("j", 1, "ck", "submitted", emptyList())).columnId)
        assertEquals("col-agentic",
            JobKanbanProjection.projectToCard(JobSnapshot("j", 1, "ck", "active", emptyList())).columnId)
        assertEquals("col-attention",
            JobKanbanProjection.projectToCard(JobSnapshot("j", 1, "ck", "failed", emptyList())).columnId)
        assertEquals("col-closed",
            JobKanbanProjection.projectToCard(JobSnapshot("j", 1, "ck", "closed", emptyList())).columnId)
    }

    /**
     * Moving a card emits one command with expected revision and does not
     * mutate the projection before commit.
     */
    @Test
    fun movingCardEmitsCommandWithoutMutatingProjection() = runTest {
        val projection = JobKanbanProjection()
        val cid = ContentId.of("v1".encodeToByteArray())
        projection.applyCommit("j-1", JobSnapshot("j-1", 1, "ck", "submitted", emptyList()), cid)

        // Before move, card is in submitted column
        val beforeCard = projection.card("j-1")
        assertEquals("col-causal-blocked", beforeCard!!.columnId)

        // Emit move command — does not mutate projection
        val cmd = projection.moveCard("j-1", toColumn = "col-agentic", expectedRevision = 1)
        assertNotNull(cmd)
        assertEquals("j-1", cmd.jobId)
        assertEquals(1, cmd.expectedRevision)

        // Projection unchanged until commit
        val stillBeforeCard = projection.card("j-1")
        assertEquals("col-causal-blocked", stillBeforeCard!!.columnId,
            "projection must not change before commit")
    }

    /**
     * After commit, the projection updates to match the committed snapshot.
     */
    @Test
    fun projectionUpdatesAfterCommit() = runTest {
        val projection = JobKanbanProjection()
        val cid1 = ContentId.of("v1".encodeToByteArray())
        projection.applyCommit("j-1", JobSnapshot("j-1", 1, "ck", "submitted", emptyList()), cid1)

        val cid2 = ContentId.of("v2".encodeToByteArray())
        projection.applyCommit("j-1", JobSnapshot("j-1", 2, "ck", "active", emptyList()), cid2)

        val card = projection.card("j-1")
        assertEquals("col-agentic", card!!.columnId)
        assertEquals(2, card.revision)
    }

    /**
     * Cards can be rebuilt from the log (C05).
     */
    @Test
    fun cardsRebuiltFromCommittedSequence() {
        val commits = listOf(
            CommitEntry(1L, "j-1", JobSnapshot("j-1", 1, "ck", "submitted", emptyList()), ContentId.of("a".encodeToByteArray())),
            CommitEntry(2L, "j-2", JobSnapshot("j-2", 1, "ck", "submitted", emptyList()), ContentId.of("b".encodeToByteArray())),
            CommitEntry(3L, "j-1", JobSnapshot("j-1", 2, "ck", "active", emptyList()), ContentId.of("c".encodeToByteArray())),
        )

        val projection = JobKanbanProjection.rebuild(commits)
        assertEquals(2, projection.cardCount, "two jobs: j-1 and j-2")
        assertEquals("col-agentic", projection.card("j-1")!!.columnId)
        assertEquals("col-causal-blocked", projection.card("j-2")!!.columnId)
    }

    /**
     * C09: Global board reducers can be rebuilt from the log and own no
     * independent mutation API.
     */
    @Test
    fun boardRebuiltFromLogHasNoMutationApi() {
        val commits = listOf(
            CommitEntry(1L, "j-1", JobSnapshot("j-1", 1, "ck", "submitted", emptyList()), ContentId.of("a".encodeToByteArray())),
        )
        val projection = JobKanbanProjection.rebuild(commits)

        // The projection must not expose mutable operations directly.
        // moveCard returns a command, it does not mutate.
        val card = projection.card("j-1")
        val cmd = projection.moveCard("j-1", "col-agentic", 1)

        // After moveCard (no commit), the card is unchanged.
        assertEquals("col-causal-blocked", projection.card("j-1")!!.columnId)
    }

    private fun assertNotNull(cmd: Any?) {
        kotlin.test.assertNotNull(cmd)
    }
}
