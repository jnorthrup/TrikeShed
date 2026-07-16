package borg.trikeshed.job

/**
 * JobEvent — committed events.
 */
sealed class JobEvent {
    data class Accepted(val jobId: JobId, val sequence: Long) : JobEvent()
    data class Rejected(val jobId: JobId, val sequence: Long, val reason: String) : JobEvent()
}

/** Wire-level operation name: "accepted" for Accepted, "rejected" for Rejected. */
val JobEvent.operation: String
    get() = when (this) {
        is JobEvent.Accepted -> "accepted"
        is JobEvent.Rejected -> "rejected"
    }

/** Reason for a Rejected event, or null for Accepted. */
val JobEvent.reason: String?
    get() = when (this) {
        is JobEvent.Accepted -> null
        is JobEvent.Rejected -> this.reason
    }

/**
 * JobFact — facts emitted by reducer.
 */
data class JobFact(val jobId: JobId, val cid: ContentId)