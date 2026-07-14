package borg.trikeshed.job

import borg.trikeshed.lib.Series
import kotlinx.serialization.Serializable

/**
 * JobProjection — projects a job snapshot to a KanbanCard.
 */
object JobProjection {

    @Serializable
    data class KanbanCard(
        val jobId: JobId,
        val revision: Long,
        val causalKey: String,
        val lifecycle: String,
        val dependencies: List<JobId>,
        val attemptId: String,
        val columnId: KanbanColumnId,
    )

    fun projectToCard(snapshot: JobSnapshot): KanbanCard {
        val column = when (snapshot.lifecycle) {
            "submitted" -> KanbanColumnId.of("col-causal-blocked")
            "active" -> KanbanColumnId.of("col-agentic")
            "blocked" -> KanbanColumnId.of("col-attention")
            "failed" -> KanbanColumnId.of("col-attention")
            "closed" -> KanbanColumnId.of("col-closed")
            "submitted" -> KanbanColumnId.of("col-causal-blocked")
            "active" -> KanbanColumnId.of("col-agentic")
            "closed" -> KanbanColumnId.of("col-closed")
            else -> KanbanColumnId.of("col-attention")
        }

        return KanbanCard(
            jobId = snapshot.jobId,
            revision = snapshot.revision,
            causalKey = snapshot.causalKey,
            lifecycle = snapshot.lifecycle,
            dependencies = snapshot.dependencies,
            attemptId = snapshot.attemptId,
            columnId = column,
        )
    }
}