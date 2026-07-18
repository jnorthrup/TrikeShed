package borg.trikeshed.job

/**
 * JobReducer — pure function from JobFrame to result.
 * Deduplicates by idempotencyKey. Validates expectedRevision (optimistic concurrency).
 */
class JobReducer {

    private data class ReductionInput(
        val operation: String,
        val jobId: JobId,
        val idempotencyKey: String,
        val dependencies: List<JobId>,
        val expectedRevision: Revision?,
        val causalKey: String,
    )

    private val idempotencyKeys = mutableSetOf<String>()
    private val jobRevisions = mutableMapOf<JobId, Long>()
    private val jobSnapshots = mutableMapOf<JobId, JobSnapshot>()
    private val jobFacts = mutableMapOf<JobId, MutableList<JobFact>>()
    private val jobAttemptCounts = mutableMapOf<JobId, Int>()
    private var nextGlobalSequence = 1L

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

    fun reduce(frame: JobFrame): Result = reduce(
        input = ReductionInput(
            operation = frame.operation,
            jobId = frame.jobId,
            idempotencyKey = frame.idempotencyKey,
            dependencies = frame.dependencies,
            expectedRevision = frame.expectedRevision,
            causalKey = frame.causalKey,
        ),
        canonicalBytes = canonicalFrameBytes(frame),
    )

    fun reduce(command: JobCommand): Result = reduce(
        input = ReductionInput(
            operation = command.operationName,
            jobId = command.jobId,
            idempotencyKey = command.idempotencyKey,
            dependencies = (command as? JobCommand.Submit)?.dependencies ?: emptyList(),
            expectedRevision = when (command) {
                is JobCommand.Submit -> command.expectedRevision
                is JobCommand.Start -> command.expectedRevision
                is JobCommand.Complete -> command.expectedRevision
                is JobCommand.Fail -> command.expectedRevision
                is JobCommand.Retry -> command.expectedRevision
                is JobCommand.Progress -> command.expectedRevision
                is JobCommand.Block -> command.expectedRevision
                is JobCommand.Cancel -> command.expectedRevision
                is JobCommand.Move -> command.expectedRevision
                is JobCommand.Acknowledge -> command.expectedRevision
                is JobCommand.Retract -> command.expectedRevision
            }?.let { Revision.of(it) },
            causalKey = "",
        ),
        canonicalBytes = CanonicalCbor.encode(command),
    )

    private fun reduce(input: ReductionInput, canonicalBytes: ByteArray): Result {
        // 1. Idempotency check
        if (!idempotencyKeys.add(input.idempotencyKey)) {
            return Result.rejected(JobEvent.Rejected(
                jobId = input.jobId,
                sequence = 0,
                reason = "duplicate idempotencyKey: ${input.idempotencyKey}",
            ))
        }

        // 2. expectedRevision validation (optimistic concurrency)
        val currentRevision = jobRevisions[input.jobId] ?: 0L
        val expectedRev = input.expectedRevision
        if (expectedRev != null) {
            val expected = expectedRev.value
            if (expected != currentRevision) {
                return Result.rejected(JobEvent.Rejected(
                    jobId = input.jobId,
                    sequence = 0,
                    reason = "stale expectedRevision: expected $expected, actual $currentRevision",
                ))
            }
        }

        val newRevision = currentRevision + 1
        jobRevisions[input.jobId] = newRevision

        val event = JobEvent.Accepted(input.jobId, nextGlobalSequence)

        // Track dependencies for lifecycle derivation
        val priorDeps = jobSnapshots[input.jobId]?.dependencies ?: emptyList()
        val effectiveDeps = if (input.dependencies.isNotEmpty()) input.dependencies else priorDeps

        val lifecycle = deriveLifecycle(input.operation, effectiveDeps)

        val attemptCount = when (input.operation) {
            "retry" -> (jobAttemptCounts[input.jobId] ?: 1) + 1
            "submit" -> 1
            else -> jobAttemptCounts[input.jobId] ?: 1
        }
        jobAttemptCounts[input.jobId] = attemptCount

        val snapshot = JobSnapshot(
            jobId = input.jobId,
            revision = newRevision,
            causalKey = input.causalKey,
            lifecycle = lifecycle,
            dependencies = effectiveDeps,
            attemptCount = attemptCount,
        )
        jobSnapshots[input.jobId] = snapshot

        val fact = JobFact(input.jobId, ContentId.of(canonicalBytes))
        jobFacts.getOrPut(input.jobId) { mutableListOf() }.add(fact)

        nextGlobalSequence++
        return Result.accepted(event, fact, snapshot)
    }

    /**
     * Derive the job lifecycle from the operation and dependency state.
     * If any dependency has failed, the job is blocked.
     */
    private fun deriveLifecycle(operation: String, dependencies: List<JobId>): String {
        // Check if any dependency has failed
        for (dep in dependencies) {
            val depSnap = jobSnapshots[dep]
            if (depSnap != null && depSnap.lifecycle == "failed") return "blocked"
        }
        return when (operation) {
            "submit" -> {
                // If all dependencies are closed, job is ready; otherwise submitted
                val allClosed = dependencies.all { dep ->
                    jobSnapshots[dep]?.lifecycle == "closed"
                }
                if (dependencies.isNotEmpty() && allClosed) "ready" else "submitted"
            }
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
    }

    /** Expose the latest snapshot for a job (for restart/projection). */
    fun snapshot(jobId: JobId): JobSnapshot? = jobSnapshots[jobId]

    /** Expose all facts for a job (for evidence queries). */
    fun facts(jobId: JobId): List<JobFact> = jobFacts[jobId]?.toList() ?: emptyList()

    private fun canonicalFrameBytes(frame: JobFrame): ByteArray =
        frame.toString().encodeToByteArray()
}
