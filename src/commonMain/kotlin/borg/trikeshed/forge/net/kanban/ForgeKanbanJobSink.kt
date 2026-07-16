package borg.trikeshed.forge.net.kanban

import borg.trikeshed.job.ContentId
import borg.trikeshed.job.JobSnapshot
import borg.trikeshed.kanban.JobKanbanProjection
import borg.trikeshed.kanban.ApplyResult

/**
 * ForgeKanbanJobSink — conduit from the committed durable tail to the Kanban projection.
 *
 * Responsibilities:
 *  - Accept committed (sequence, jobId, snapshot, cid) frames in monotonic order.
 *  - Reject out-of-order sequence numbers (C04 durable-before-visible; replay integrity).
 *  - Forward accepted frames to the JobKanbanProjection.
 *
 * It owns no state of its own beyond the last seen sequence; the projection is the
 * only mutable surface, and it advances only after a frame is accepted.
 */
class ForgeKanbanJobSink(private val projection: JobKanbanProjection) {

    private var lastSequence: Long = 0L

    /** Apply a committed frame. Returns acceptance + sequence.
     *  Sequence must be exactly lastSequence + 1 (strict monotonic, no gaps). */
    fun applyCommit(
        sequence: Long,
        jobId: String,
        snapshot: JobSnapshot,
        cid: ContentId,
    ): ApplyResult {
        if (sequence != lastSequence + 1) {
            return ApplyResult(accepted = false, sequence = lastSequence)
        }
        projection.applyCommit(jobId, snapshot, cid)
        lastSequence = sequence
        return ApplyResult(accepted = true, sequence = sequence)
    }
}
