package borg.trikeshed.job

/**
 * JobEvent — committed events.
 */
sealed class JobEvent {
    data class Accepted(val jobId: JobId, val sequence: Long) : JobEvent()
    data class Rejected(val jobId: JobId, val sequence: Long, val reason: String) : JobEvent()
}

/**
 * JobFact — facts emitted by reducer.
 */
data class JobFact(val jobId: JobId, val cid: ContentId)