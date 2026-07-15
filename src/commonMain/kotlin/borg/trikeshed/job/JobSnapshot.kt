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
) {
    companion object {
        /**
         * Factory for test ergonomics - accepts String for jobId and dependencies.
         */
        operator fun invoke(
            jobId: String,
            revision: Long,
            causalKey: String,
            lifecycle: String,
            dependencies: List<String> = emptyList(),
        ): JobSnapshot = JobSnapshot(
            jobId = JobId.of(jobId),
            revision = revision,
            causalKey = causalKey,
            lifecycle = lifecycle,
            dependencies = dependencies.map { JobId.of(it) },
        )
    }
}