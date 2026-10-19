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
        val priorStateCid: String? = null,
        val stateCid: String? = null,
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

    fun reduce(frame: JobFrame): Result = if (frame.operation == "state") reduce(frame.stateCommand()) else reduce(
        input = ReductionInput(
            operation = frame.operation,
            jobId = frame.jobId,
            idempotencyKey = frame.idempotencyKey,
            dependencies = frame.dependencies,
            expectedRevision = frame.expectedRevision,
            causalKey = frame.causalKey,
        ),
        canonicalBytes = CanonicalCbor.encode(frame.doc),
    )

    fun reduce(command: JobCommand): Result = reduce(
        input = ReductionInput(
            operation = command.operationName,
            jobId = command.jobId,
            idempotencyKey = command.idempotencyKey,
            dependencies = (command as? JobCommand.Submit)?.dependencies ?: emptyList(),
            expectedRevision = when (command) {
                is JobCommand.Submit -> command.expectedRevision
                is JobCommand.Update -> command.expectedRevision
                is JobCommand.Start -> command.expectedRevision
                is JobCommand.Complete -> command.expectedRevision
                is JobCommand.Fail -> command.expectedRevision
                is JobCommand.Retry -> command.expectedRevision
                is JobCommand.Progress -> command.expectedRevision
                is JobCommand.State -> command.expectedRevision
                is JobCommand.Block -> command.expectedRevision
                is JobCommand.Cancel -> command.expectedRevision
                is JobCommand.Move -> command.expectedRevision
                is JobCommand.Acknowledge -> command.expectedRevision
                is JobCommand.Retract -> command.expectedRevision
            }?.let { Revision.of(it) },
            causalKey = "",
            priorStateCid = (command as? JobCommand.State)?.priorStateCid,
            stateCid = (command as? JobCommand.State)?.stateCid,
        ),
        canonicalBytes = CanonicalCbor.encode(command),
    )

    /** The command consumer probes State before retaining a target that a rejected command never owns. */
    fun stateRejection(command: JobCommand.State): String? = stateRejection(
        command.jobId, command.idempotencyKey, command.expectedRevision, command.priorStateCid, command.stateCid,
    )

    private fun stateRejection(jobId: JobId, key: String, revision: Long?, prior: String?, next: String?): String? {
        if (key in idempotencyKeys) return "duplicate idempotencyKey: $key"
        val snapshot = jobSnapshots[jobId] ?: return "state of unknown job: ${jobId.value}"
        if (revision == null || revision < 0L || revision != snapshot.revision)
            return "stale expectedRevision: expected $revision, actual ${snapshot.revision}"
        if (snapshot.lifecycle != "active") return "state requires active job: ${jobId.value} is ${snapshot.lifecycle}"
        if (prior != snapshot.stateCid) return "state prior head mismatch for ${jobId.value}"
        if (next == null || runCatching { ContentId(next); prior?.let(::ContentId) }.isFailure)
            return "invalid state ContentId for ${jobId.value}"
        return null
    }

    private fun reduce(input: ReductionInput, canonicalBytes: ByteArray): Result {
        val stateRefusal = if (input.operation == "state")
            stateRejection(input.jobId, input.idempotencyKey, input.expectedRevision?.value,
                input.priorStateCid, input.stateCid) else null
        // 1. Idempotency check
        if (!idempotencyKeys.add(input.idempotencyKey)) {
            return Result.rejected(JobEvent.Rejected(
                jobId = input.jobId,
                sequence = 0,
                reason = "duplicate idempotencyKey: ${input.idempotencyKey}",
                idempotencyKey = input.idempotencyKey,
            ))
        }

        // A rejected request still consumes its identity, like every other logged command.
        if (stateRefusal != null) return Result.rejected(JobEvent.Rejected(input.jobId, 0L, stateRefusal, input.idempotencyKey))

        // 2. expectedRevision validation (optimistic concurrency)
        val currentRevision = jobRevisions[input.jobId] ?: 0L
        val expectedRev = input.expectedRevision
        val priorSnapshot = jobSnapshots[input.jobId]
        if (input.operation == "update" && (priorSnapshot == null || expectedRev == null)) {
            return Result.rejected(JobEvent.Rejected(input.jobId, 0,
                if (priorSnapshot == null) "update of unknown job: ${input.jobId.value}"
                else "update without expectedRevision", input.idempotencyKey))
        }
        if (expectedRev != null) {
            val expected = expectedRev.value
            if (expected != currentRevision) {
                return Result.rejected(JobEvent.Rejected(
                    jobId = input.jobId,
                    sequence = 0,
                    reason = "stale expectedRevision: expected $expected, actual $currentRevision",
                    idempotencyKey = input.idempotencyKey,
                ))
            }
        }

        val newRevision = currentRevision + 1
        jobRevisions[input.jobId] = newRevision

        val event = JobEvent.Accepted(input.jobId, nextGlobalSequence, input.idempotencyKey)

        // Track dependencies for lifecycle derivation
        val priorDeps = jobSnapshots[input.jobId]?.dependencies ?: emptyList()
        val effectiveDeps = if (input.dependencies.isNotEmpty()) input.dependencies else priorDeps

        val lifecycle = if (input.operation == "update" || input.operation == "state") checkNotNull(priorSnapshot).lifecycle
            else deriveLifecycle(input.operation, effectiveDeps)

        val attemptCount = when (input.operation) {
            "retry" -> (jobAttemptCounts[input.jobId] ?: 1) + 1
            "submit" -> 1
            else -> jobAttemptCounts[input.jobId] ?: 1
        }
        jobAttemptCounts[input.jobId] = attemptCount

        val snapshot = if (input.operation == "update" || input.operation == "state") checkNotNull(priorSnapshot).copy(
            revision = newRevision,
            stateCid = if (input.operation == "state") input.stateCid else priorSnapshot.stateCid,
        ) else JobSnapshot(
            jobId = input.jobId,
            revision = newRevision,
            causalKey = input.causalKey,
            lifecycle = lifecycle,
            dependencies = effectiveDeps,
            attemptCount = attemptCount,
            stateCid = priorSnapshot?.stateCid,
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
