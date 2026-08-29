package borg.trikeshed.lcnc

import borg.trikeshed.job.ConfixFacetPlan
import borg.trikeshed.job.JobCommand
import borg.trikeshed.job.JobEvent
import borg.trikeshed.job.JobId
import borg.trikeshed.job.JobNexusBindings
import borg.trikeshed.job.JobNexusFactory
import borg.trikeshed.job.JobNexusSpec
import borg.trikeshed.job.JobSupervisorElement
import borg.trikeshed.job.schema.SchemaCompiler
import borg.trikeshed.job.schema.loadConfixSchemaBytes
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.parse.confix.Syntax
import kotlinx.coroutines.CoroutineScope

/**
 * The tribunal's mutable + versionable instance.
 *
 * This is the "enter data from a schema to an instance" seam the tribunal
 * was missing. A [TribunalInstance] is a live [JobSupervisorElement] — the
 * durability pipeline (schema → CAS → WAL → reducer → index) — whose state is
 * mutable (each command mutates the job snapshot) and versionable (every
 * commit bumps `revision` and mints a content-addressed [borg.trikeshed.job.ContentId]).
 *
 * Root pre-canned data: the instance is opened FROM a preset's pre-canned
 * kanban lanes. Each lane is submitted as a schema-conformant
 * [JobCommand.Submit] — the root jobs at revision 1, lifecycle `submitted` —
 * so the instance's root state IS the preset's structure. [open] submits
 * them eagerly (non-suspending); [awaitRootSeeds] suspends until every
 * root commit lands, so a trial that needs the committed base waits on it
 * explicitly rather than on the daemon's boot path.
 *
 * Schema: the [ConfixFacetPlan] (compiled from `job-nexus.schema.json`) is
 * the entry gate — [advance] builds a schema-conformant command frame,
 * validates it against the plan, and rejects loudly on any violation before
 * the command reaches the WAL.
 */
class TribunalInstance internal constructor(
    val nexus: JobSupervisorElement,
    val plan: ConfixFacetPlan,
    var laneIds: List<String>,
) {

    /** Revision of a committed job, or null if it has not committed yet. */
    fun revision(jobId: String): Long? = nexus.snapshot(jobId)?.revision

    /** Tracked lifecycle of a committed job (the schema's lifecycle enum). */
    fun lifecycle(jobId: String): String? = nexus.snapshot(jobId)?.lifecycle

    /** Content-addressed cid of the latest committed snapshot, when present. */
    fun snapshotCid(jobId: String): String? = nexus.snapshotCid(jobId)?.value

    /**
     * Enter one schema-conformant command into the instance and AWAIT its
     * commit. The command frame is validated against the compiled
     * [ConfixFacetPlan] before it is lowered to a typed [JobCommand]; an
     * invalid frame throws (loud), it never reaches the WAL.
     *
     * `expectedRevision` is the optimistic-concurrency guard — the caller
     * passes the job's last committed revision, so a stale writer is
     * rejected by the reducer, not silently merged.
     *
     * Suspend: it suspends until the nexus reactor commits the command, so
     * the returned [JobEvent] is authoritative (the snapshot is already
     * readable via [revision]/[lifecycle] on return).
     */
    suspend fun advance(
        jobId: String,
        operation: String,
        idempotencyKey: String,
        expectedRevision: Long,
        reason: String = "",
    ): JobEvent {
        val id = JobId.of(jobId)
        // Build the schema-conformant frame and validate it against the plan —
        // this is the schema gate on entry. A frame that fails the plan never
        // reaches the WAL.
        val frame = linkedMapOf<String, Any?>(
            "schemaVersion" to "1.0.0",
            "frameKind" to "command",
            "operation" to operation,
            "workspaceId" to "tribunal",
            "jobId" to jobId,
            "sequence" to nexus.committedSequence,
            "cid" to "sha256:" + "0".repeat(64),
            "idempotencyKey" to idempotencyKey,
            "causalKey" to "tribunal/$jobId/$operation",
            "timestampMs" to 0L,
            "expectedRevision" to expectedRevision,
        )
        val doc = confixDoc(borg.trikeshed.parse.json.JsonSupport.stringify(frame).encodeToByteArray(), Syntax.JSON)
        val verdict = plan.validate(doc)
        if (!verdict.valid) throw IllegalArgumentException("tribunal frame rejected by schema: ${verdict.errors}")

        val cmd: JobCommand = when (operation) {
            "start" -> JobCommand.Start(id, idempotencyKey, expectedRevision)
            "complete" -> JobCommand.Complete(id, idempotencyKey, expectedRevision)
            "fail" -> JobCommand.Fail(id, idempotencyKey, expectedRevision, reason)
            "progress" -> JobCommand.Progress(id, idempotencyKey, expectedRevision, 0.0)
            "submit" -> JobCommand.Submit(id, idempotencyKey)
            else -> throw IllegalArgumentException("tribunal: unknown lifecycle operation '$operation'")
        }

        nexus.submit(cmd)
        return awaitCommit(id)
    }

    /** Suspend until the nexus commits a command for [id]; return that event. */
    private suspend fun awaitCommit(id: JobId): JobEvent {
        // Sequential trial: one command in flight at a time, so the next
        // committed event is ours. Bounded so a missing commit is loud, not
        // an infinite loop.
        repeat(16) {
            val e = nexus.committed.receive()
            val eid = when (e) {
                is JobEvent.Accepted -> e.jobId
                is JobEvent.Rejected -> e.jobId
            }
            if (eid == id) return e
        }
        throw IllegalStateException("tribunal: no committed event observed for ${id.value}")
    }

    /**
     * Suspend until every root seed commit has landed (each lane at
     * revision 1). Consumes exactly one committed event per lane IN ORDER —
     * the reactor emits in commit order and the seeds were submitted in
     * lane order, so this drains the seed events from the committed channel
     * with nothing left behind (a leftover seed event for a lane would be
     * mistaken for that lane's next advance).
     */
    suspend fun awaitRootSeeds() {
        for (lane in laneIds) {
            awaitCommit(JobId.of(lane))
        }
    }

    companion object {
        /**
         * Open the instance from a compiled [plan] and a preset document.
         * NON-suspending: it assembles the nexus (scope → CAS → WAL) and
         * submits the pre-canned lanes as root jobs; their commits land on
         * the reactor's clock and [awaitRootSeeds] waits on them.
         */
        fun open(
            scope: CoroutineScope,
            plan: ConfixFacetPlan,
            presetDocument: String,
        ): TribunalInstance {
            val element = JobNexusFactory.open(
                spec = JobNexusSpec(),
                bindings = JobNexusBindings(parentScope = scope),
            )
            val instance = TribunalInstance(element, plan, emptyList())

            // Root pre-canned data: the preset's own lanes become the root jobs.
            val program = LcncProgramConfix.fromJson("preset-tribunal", presetDocument)
            val kb = program.kanban
            val lanes = if (kb != null) (0 until kb.lanes.size).map { kb.lanes[it].id } else emptyList()
            instance.laneIds = lanes
            for (lane in lanes) {
                element.trySubmit(JobCommand.Submit(JobId.of(lane), "tribunal-seed-$lane"))
            }
            return instance
        }

        /** Compile the tribunal's schema plan straight from the baked resource. */
        fun schemaPlan(): ConfixFacetPlan =
            SchemaCompiler.compilePlan(loadConfixSchemaBytes("confix/job-nexus.schema.json"))
    }
}
