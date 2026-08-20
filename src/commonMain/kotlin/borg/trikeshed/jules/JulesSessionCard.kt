package borg.trikeshed.jules

import borg.trikeshed.kanban.KanbanCard
import borg.trikeshed.kanban.KanbanCardId
import borg.trikeshed.kanban.KanbanColumnId
import borg.trikeshed.job.ContentId
import borg.trikeshed.util.oroboros.MergeReceipt
import kotlinx.datetime.Clock

/**
 * Jules session as a Kanban card with its own context.
 *
 * Each card snapshots its surroundings into its own assumpsis (assumption basis):
 * the session JSON, the repo HEAD it was dispatched against, the predicate vector
 * at capture time, and the CID of any delivered patch. The snapshot is the card's
 * world — it explains what the card believed when it last changed lane.
 *
 * Every lane transition appends a [JulesCause]: agents know what causes things,
 * and the cause is recorded, not implied. The board is the conductor; the commits
 * are only the recording of actions the board decided.
 */

/** Snapshot of the card's surroundings at capture time — the card's assumpsis. */
data class JulesSnapshot(
    val sessionId: String,
    val state: String,            // raw Jules state string
    val title: String,
    val patchBytes: Long,         // delivered unidiff size, 0 = none yet
    val headSha: String,          // repo HEAD the session was dispatched against
    val activeCount: Int,         // concurrent sessions at capture (predicate: quota)
    val awaitingCount: Int,       // awaiting sessions at capture (predicate: no-awaiting)
    val capturedAt: Long = Clock.System.now().toEpochMilliseconds(),
)

/**
 * What caused a lane transition. Sealed so every cause is inspectable.
 *
 * Jules exposes no mutation serials — activity ids are random hex, only
 * createTime orders them. So the board mints its own per-session serial
 * ([activitySeq] = index in the ordered activities list) and anchors it to the
 * Jules activity [activityId] for dedup. Causes without an anchor are
 * conductor-internal (predicate flips, drain bookkeeping).
 */
sealed class JulesCause {
    abstract val at: Long

    /** Jules activity id that provoked this cause, if any (dedup anchor). */
    open val activityId: String? get() = null

    /** Board-minted serial: index of the activity in the session's ordered list. */
    open val activitySeq: Int? get() = null

    /** Work id anchor for unified-queue causes (WorkQueued/WorkDispatched/WorkDrained). */
    open val workId: String? get() = null

    /** Agent posted a progress/question message. */
    data class AgentMessaged(
        val excerpt: String,
        override val at: Long,
        override val activityId: String? = null,
        override val activitySeq: Int? = null,
    ) : JulesCause()

    /** Human answered an AWAITING session via the board. */
    data class HumanAnswered(
        val message: String,
        override val at: Long,
        override val activityId: String? = null,
        override val activitySeq: Int? = null,
    ) : JulesCause()

    /** Session delivered a patch (changeSet artifact observed). */
    data class PatchArrived(
        val bytes: Long,
        override val at: Long,
        override val activityId: String? = null,
        override val activitySeq: Int? = null,
    ) : JulesCause()

    /**
     * One immutable Jules activity-patch snapshot after its bytes are in CAS.
     * [causalOrdinal] is the position in the current ordered activity stream;
     * [patchCid] makes ordinal shifts or repeated snapshots unambiguous.
     *
     * A snapshot is a new automatic review candidate only when
     * [reviewCandidate] is true.  [missingFromCandidate] records a file-set
     * regression against the retained last-known-good candidate.
     */
    data class PatchSnapshotObserved(
        val patchCid: ContentId,
        val causalOrdinal: Int,
        val artifactSeq: Int,
        val touchedFiles: List<String>,
        val missingFromCandidate: List<String>,
        val reviewCandidate: Boolean,
        override val at: Long,
        override val activityId: String,
        override val activitySeq: Int,
    ) : JulesCause()

    /**
     * Explicit operator review of one observed snapshot.  The receipt reference
     * is mandatory provenance (ticket, review log, or settlement receipt); it
     * lets a regressed latest snapshot be selected without trusting recency.
     */
    data class PatchReviewSelected(
        val patchCid: ContentId,
        val causalOrdinal: Int,
        /** Latest producer artifacts visible to the reviewer (CAS watermark). */
        val latestPatchCid: ContentId? = null,
        val latestReportCid: ContentId? = null,
        val reviewedBy: String,
        val receiptRef: String,
        override val at: Long,
    ) : JulesCause()

    /**
     * Typed reject of one observed snapshot chain.  A completed session whose
     * every candidate is superseded (or otherwise unusable) cannot settle the
     * patch path honestly; this cause names the rejected chain head and the
     * durable reason, bonded to a receipt, so settlement can retire it without
     * laundering a regressed patch or discarding CAS evidence.
     */
    data class PatchRejected(
        val patchCid: ContentId,
        val causalOrdinal: Int,
        /** Latest producer artifacts visible to the reviewer (CAS watermark). */
        val latestPatchCid: ContentId? = null,
        val latestReportCid: ContentId? = null,
        val reason: String,
        val reviewedBy: String,
        val receiptRef: String,
        override val at: Long,
    ) : JulesCause()

    /**
     * One complete Jules agent message after its exact UTF-8 bytes are durable
     * in CAS.  This is deliberately separate from [AgentMessaged], whose
     * excerpt exists only for the operator board.  Together the WAL key,
     * [activityId], [activitySeq], [causalOrdinal], and [reportCid] preserve the
     * report's position and identity even when a completed session has no patch.
     */
    data class AgentReportObserved(
        val reportCid: ContentId,
        val causalOrdinal: Int,
        val bytes: Long,
        val apiCreateTime: String,
        override val at: Long,
        override val activityId: String,
        override val activitySeq: Int,
    ) : JulesCause()

    /**
     * Explicit semantic review of one observed report.  Observation alone does
     * not mean that an agent's no-op claim is correct; settlement must name the
     * reviewed disposition and receipt that authorized it.
     */
    data class AgentReportReviewSelected(
        val reportCid: ContentId,
        val causalOrdinal: Int,
        /** Latest producer artifacts visible to the reviewer (CAS watermark). */
        val latestPatchCid: ContentId? = null,
        val latestReportCid: ContentId? = null,
        val disposition: String,
        val reviewedBy: String,
        val receiptRef: String,
        override val at: Long,
    ) : JulesCause()

    /** Drain applied the patch locally and committed. */
    data class DrainApplied(val commitSha: String, val rejects: Int, override val at: Long) : JulesCause()

    /** Drain failed; patch did not apply. */
    data class DrainFailed(val reason: String, override val at: Long) : JulesCause()

    /** A predicate flipped (quota freed, awaiting cleared) allowing dispatch. */
    data class PredicateFlipped(val predicate: String, val nowPassing: Boolean, override val at: Long) : JulesCause()

    /** Session failed on the Jules side. */
    data class SessionFailed(val reason: String, override val at: Long) : JulesCause()

    /** Settled session archived through the Jules API after origin/master parity. */
    data class SessionArchived(override val at: Long) : JulesCause()

    /** Poll observed a state change with no finer-grained cause. */
    data class StateObserved(val from: String, val to: String, override val at: Long) : JulesCause()

    /** Work item appended to the unified queue. workId is the dedup anchor. */
    data class WorkQueued(
        override val workId: String,
        val tier: String,
        val title: String,
        val spec: String,
        val parent: String? = null,
        val score: Double = 0.5,
        override val at: Long,
    ) : JulesCause()

    /** Queue item promoted to a Jules session. workId → sessionId is the delta. */
    data class WorkDispatched(
        override val workId: String,
        val sessionId: String,
        val attempt: Int,
        override val at: Long,
    ) : JulesCause()

    /** Jules session merged locally; the work item is drained. */
    data class WorkDrained(
        override val workId: String,
        val sessionId: String,
        val commitSha: String,
        val taskId: String,
        val receipt: MergeReceipt? = null,
        override val at: Long,
    ) : JulesCause()

    /** Identity synthesized for a dispatched work item. Carries the durable
     *  synonym map (sessionId → gitBranch → prUrl → gitTag → commitSha) so the
     *  flywheel can recover the identity across restarts without re-minting. */
    data class WorkIdentitySynthesized(
        override val workId: String,
        val identity: WorkIdentity,
        override val at: Long,
    ) : JulesCause()
}

/**
 * Durable identity for one unit of work across all surfaces it can appear on.
 *
 * A single Jules task may surface as any combination of:
 *  - `sessionId`: the Jules API primary key (always present after dispatch)
 *  - `sessionUrl`: `https://jules.google.com/session/<sessionId>` (derived)
 *  - `gitBranch`: `refs/heads/jules-<id>-<sha>` pushed by Jules to origin
 *  - `prUrl`: GitHub PR URL if Jules ran `gh pr create` (may be absent)
 *  - `gitTag`: `flywheel/jules-<session>-<sha12>` minted by claimPatch
 *  - `commitSha`: local merge commit after settlePatch lands
 *
 * Jules may COMPLETED with no branch, no PR, and no patch (patchBytes == 0).
 * The identity is still the sessionId; missing synonyms are null, not duplicated.
 *
 * Written once at dispatch (WorkIdentitySynthesized cause), enriched at harvest
 * (gitBranch, prUrl) and land (gitTag, commitSha). The WAL is truth; the
 * identity is never re-minted — only extended with synonyms as they surface.
 */
data class WorkIdentity(
    val workId: String,
    val sessionId: String,
    val sessionUrl: String = "https://jules.google.com/session/$sessionId",
    val gitBranch: String? = null,
    val prUrl: String? = null,
    val gitTag: String? = null,
    val commitSha: String? = null,
) {
    /** Whether this identity has been observed as drained/landed. */
    val isLanded: Boolean get() = commitSha != null || gitTag != null
}

/** Kanban lanes for the Jules conductor board. Order matters (left→right). */
enum class JulesLane(val columnName: String, val order: Int) {
    TO_DO("To Do", 0),
    AGENTIC_WORK("Agentic Work", 1),
    CAUSAL_BLOCKED("Causal Blocked", 2),
    REVIEW("Review", 3),
    CAUSAL_READY("Causal Ready", 4),
    DONE("Done", 5),
    FAILED("Failed", 6),
}

/** Derive the lane from a snapshot of Jules state. */
fun laneFor(snapshot: JulesSnapshot, drained: Boolean): JulesLane = when (snapshot.state) {
    "QUEUED", "PLANNING" -> JulesLane.TO_DO
    "IN_PROGRESS" -> JulesLane.AGENTIC_WORK
    "AWAITING_USER_FEEDBACK" -> JulesLane.CAUSAL_BLOCKED
    "AWAITING_PLAN_APPROVAL" -> JulesLane.REVIEW
    "COMPLETED" -> when {
        drained -> JulesLane.DONE
        snapshot.patchBytes == 0L -> JulesLane.REVIEW
        else -> JulesLane.CAUSAL_READY
    }
    "FAILED" -> JulesLane.FAILED
    else -> JulesLane.TO_DO
}

/**
 * A Jules session card: the canonical KanbanCard plus its context world.
 *
 * [snapshot] is the current assumpsis. [causes] is the append-only causal chain —
 * the record of what the agents knew caused each transition. [drained] records
 * whether the delivered patch has been applied and committed locally.
 */
data class JulesSessionCard(
    val card: KanbanCard,
    val snapshot: JulesSnapshot,
    val causes: List<JulesCause> = emptyList(),
    val drained: Boolean = false,
) {
    val lane: JulesLane get() = laneFor(snapshot, drained)

    /** Transition: new snapshot + the cause of the change. Card column follows lane. */
    fun transition(newSnapshot: JulesSnapshot, cause: JulesCause): JulesSessionCard {
        val newLane = laneFor(newSnapshot, drained)
        return copy(
            card = card.copy(
                columnId = KanbanColumnId(newLane.columnName),
                updatedAt = newSnapshot.capturedAt,
                metadata = card.metadata + ("julesState" to newSnapshot.state),
            ),
            snapshot = newSnapshot,
            causes = causes + cause,
        )
    }

    /** Record a successful drain: patch applied, committed, lane → Done. */
    fun markDrained(commitSha: String, rejects: Int, at: Long = Clock.System.now().toEpochMilliseconds()): JulesSessionCard =
        copy(
            drained = true,
            causes = causes + JulesCause.DrainApplied(commitSha, rejects, at),
            card = card.copy(columnId = KanbanColumnId(JulesLane.DONE.columnName), updatedAt = at),
        )

    companion object {
        /** Create a card from a first-observed snapshot. */
        fun capture(snapshot: JulesSnapshot): JulesSessionCard {
            val lane = laneFor(snapshot, drained = false)
            return JulesSessionCard(
                card = KanbanCard(
                    id = KanbanCardId(snapshot.sessionId),
                    title = snapshot.title.ifBlank { "jules-${snapshot.sessionId}" },
                    columnId = KanbanColumnId(lane.columnName),
                    tags = setOf("jules"),
                    metadata = mapOf(
                        "julesState" to snapshot.state,
                        "headSha" to snapshot.headSha,
                        "patchBytes" to snapshot.patchBytes.toString(),
                    ),
                    createdAt = snapshot.capturedAt,
                    updatedAt = snapshot.capturedAt,
                ),
                snapshot = snapshot,
                causes = listOf(JulesCause.StateObserved("∅", snapshot.state, snapshot.capturedAt)),
            )
        }
    }
}

/** Render one card as a ≤10-line agent-scannable block, `$ ---` terminated. */
fun JulesSessionCard.renderBlock(): String = buildString {
    // Bolt: avoid intermediate List allocations from filterIsInstance by using sequence for terminal ops
    val finalReport = causes.asSequence().filterIsInstance<JulesCause.AgentReportObserved>()
        .maxByOrNull { it.causalOrdinal }
    appendLine("id: ${snapshot.sessionId}")
    appendLine("title: ${card.title.take(80)}")
    appendLine("lane: ${lane.columnName}")
    appendLine("state: ${snapshot.state}")
    appendLine("patchBytes: ${snapshot.patchBytes}")
    appendLine("headSha: ${snapshot.headSha.take(9)}")
    appendLine("drained: $drained")
    appendLine("causes: ${causes.size} last=${causes.lastOrNull()?.let { it::class.simpleName } ?: "none"}")
    finalReport?.let { appendLine("report: ${it.causalOrdinal}/${it.reportCid.value}") }
    append("$ ---")
}
