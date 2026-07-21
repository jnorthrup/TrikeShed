/*
 * Copyright (c) 2017 TrikeShed Contributors
 * AGPLv3 — see LICENSE
 */
package borg.trikeshed.jules

import borg.trikeshed.kanban.KanbanCard
import borg.trikeshed.kanban.KanbanCardId
import borg.trikeshed.kanban.KanbanColumnId
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

/** What caused a lane transition. Sealed so every cause is inspectable. */
sealed class JulesCause {
    abstract val at: Long

    /** Agent posted a progress/question message. */
    data class AgentMessaged(val excerpt: String, override val at: Long) : JulesCause()

    /** Human answered an AWAITING session via the board. */
    data class HumanAnswered(val message: String, override val at: Long) : JulesCause()

    /** Session delivered a patch (changeSet artifact observed). */
    data class PatchArrived(val bytes: Long, override val at: Long) : JulesCause()

    /** Drain applied the patch locally and committed. */
    data class DrainApplied(val commitSha: String, val rejects: Int, override val at: Long) : JulesCause()

    /** Drain failed; patch did not apply. */
    data class DrainFailed(val reason: String, override val at: Long) : JulesCause()

    /** A predicate flipped (quota freed, awaiting cleared) allowing dispatch. */
    data class PredicateFlipped(val predicate: String, val nowPassing: Boolean, override val at: Long) : JulesCause()

    /** Session failed on the Jules side. */
    data class SessionFailed(val reason: String, override val at: Long) : JulesCause()

    /** Poll observed a state change with no finer-grained cause. */
    data class StateObserved(val from: String, val to: String, override val at: Long) : JulesCause()
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
    "COMPLETED" -> if (drained || snapshot.patchBytes == 0L) JulesLane.DONE else JulesLane.CAUSAL_READY
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
    appendLine("id: ${snapshot.sessionId}")
    appendLine("title: ${card.title.take(80)}")
    appendLine("lane: ${lane.columnName}")
    appendLine("state: ${snapshot.state}")
    appendLine("patchBytes: ${snapshot.patchBytes}")
    appendLine("headSha: ${snapshot.headSha.take(9)}")
    appendLine("drained: $drained")
    appendLine("causes: ${causes.size}")
    causes.lastOrNull()?.let { appendLine("lastCause: ${it::class.simpleName}") }
    append("$ ---")
}
