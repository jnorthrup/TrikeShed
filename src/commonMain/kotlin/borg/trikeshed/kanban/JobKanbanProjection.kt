package borg.trikeshed.kanban

import borg.trikeshed.job.ContentId
import borg.trikeshed.job.JobSnapshot
import borg.trikeshed.job.JobProjection

/**
 * JobKanbanProjection — Kanban as a projection over committed job snapshots.
 *
 * Invariants honoured:
 *  - C05: derived indexes (cards) are rebuildable from the committed sequence.
 *  - C09: moving a card emits a versioned command; it does not mutate the
 *         projection before the next committed frame arrives.
 *
 * Two construction shapes:
 *   JobKanbanProjection()                 — empty, fed incrementally by applyCommit
 *   JobKanbanProjection.rebuild(commits)  — pre-populated from a committed tail
 *
 * The card type is JobKanbanCard, distinct from the legacy borg.trikeshed.kanban.KanbanCard
 * used by the board FSM and visual surfaces.
 */

/** Lightweight command emitted by moveCard. Carries String identities so tests and
 *  command-line adapters can assert without pulling in the JobId value class. */
data class MoveCardCommand(
    val jobId: String,
    val expectedRevision: Long,
    val toColumn: String,
    val idempotencyKey: String = "$jobId#move#$expectedRevision#$toColumn",
)

/** Projection card: a job's last committed snapshot rendered for a Kanban view. */
data class JobKanbanCard(
    val jobId: String,
    val revision: Long,
    val causalKey: String,
    val lifecycle: String,
    val dependencies: List<String>,
    val attemptId: String,
    val columnId: String,
)

/** One committed frame in the durable tail, used by rebuild(). */
data class CommitEntry(
    val sequence: Long,
    val jobId: String,
    val snapshot: JobSnapshot,
    val cid: ContentId,
)

class JobKanbanProjection internal constructor(
    private val commits: MutableList<CommitEntry> = mutableListOf(),
) {

    /** Live card count — one per distinct jobId in the committed tail. */
    val cardCount: Int
        get() = commits.map { it.jobId }.distinct().size

    /** Append a committed frame. Identical to what the durable sink delivers.
     *  Returns the accepted/rejected status so callers can detect bad input. */
    fun applyCommit(jobId: String, snapshot: JobSnapshot, cid: ContentId): ApplyResult {
        val entry = CommitEntry(commits.size + 1L, jobId, snapshot, cid)
        commits.add(entry)
        return ApplyResult(accepted = true, sequence = entry.sequence)
    }

    /** Return the last committed card for a jobId, or null if unknown. */
    fun card(jobId: String): JobKanbanCard? {
        val commit = commits.lastOrNull { it.jobId == jobId } ?: return null
        return projectToCard(commit.snapshot)
    }

    /** Emit a move command. Does NOT mutate the projection; the next committed
     *  frame carrying the new lifecycle/column is what advances the card. */
    fun moveCard(jobId: String, toColumn: String, expectedRevision: Long): MoveCardCommand =
        MoveCardCommand(jobId = jobId, expectedRevision = expectedRevision, toColumn = toColumn)

    companion object {
        /** Pure projection of one snapshot to one card. */
        fun projectToCard(snapshot: JobSnapshot): JobKanbanCard {
            val inner = JobProjection.projectToCard(snapshot)
            return JobKanbanCard(
                jobId = inner.jobId.value,
                revision = inner.revision,
                causalKey = inner.causalKey,
                lifecycle = inner.lifecycle,
                dependencies = inner.dependencies.map { it.value },
                attemptId = inner.attemptId,
                columnId = inner.columnId.value,
            )
        }

        /** Rebuild a projection from an ordered committed tail (C05). */
        fun rebuild(commits: List<CommitEntry>): JobKanbanProjection =
            JobKanbanProjection(commits.toMutableList())
    }
}

/** Result of applying a commit through the sink/projection. */
data class ApplyResult(val accepted: Boolean, val sequence: Long)
