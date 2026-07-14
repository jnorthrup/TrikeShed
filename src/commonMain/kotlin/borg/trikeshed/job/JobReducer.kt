package borg.trikeshed.job

/**
 * JobReducer — pure function from JobFrame to result.
 * Deduplicates by idempotencyKey.
 */
class JobReducer {

    private val idempotencyKeys = mutableSetOf<String>()
    private var nextRevision = 1L

    data class Result(
        val accepted: Boolean,
        val event: JobEvent?,
        val fact: JobFact?,
        val snapshot: JobSnapshot?,
    ) {
        companion object {
            fun accepted(event: JobEvent, fact: JobFact, snapshot: JobSnapshot): Result =
                Result(true, event, fact, snapshot)
            fun rejected(event: JobEvent): Result = Result(false, event, null, null)
        }
    }

    fun reduce(frame: JobFrame): Result {
        if (!idempotencyKeys.add(frame.idempotencyKey)) {
            return Result.rejected(JobEvent.Rejected(
                jobId = frame.jobId,
                sequence = 0,
                reason = "duplicate idempotencyKey: ${frame.idempotencyKey}",
            ))
        }

        val event = JobEvent.Accepted(frame.jobId, nextRevision)

        val fact = JobFact(frame.jobId, ContentId.of(canonicalFrameBytes(frame)))

        val lifecycle = when (frame.operation) {
            "submit" -> "submitted"
            "start" -> "active"
            "complete" -> "closed"
            "fail" -> "failed"
            "retry" -> "submitted"
            "progress" -> "active"
            "block" -> "blocked"
            "cancel" -> "cancelled"
            "move" -> "moved"
            "acknowledge" -> "acknowledged"
            "retract" -> "retracted"
            else -> "unknown"
        }

        val snapshot = JobSnapshot(
            jobId = frame.jobId,
            revision = nextRevision,
            causalKey = frame.causalKey,
            lifecycle = lifecycle,
            dependencies = frame.dependencies,
        )

        nextRevision++
        return Result.accepted(event, fact, snapshot)
    }

    private fun canonicalFrameBytes(frame: JobFrame): ByteArray =
        frame.toString().encodeToByteArray()
}