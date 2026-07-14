package borg.trikeshed.job

import kotlinx.serialization.Serializable

/**
 * JobSnapshot — committed state of a job.
 */
@Serializable
data class JobSnapshot(
    val jobId: JobId,
    val revision: Long,
    val causalKey: String,
    val lifecycle: String,
    val dependencies: List<JobId> = emptyList(),
    val attemptCount: Int = 0,
    val parentJobId: JobId? = null,
    val attemptId: String = "",
)