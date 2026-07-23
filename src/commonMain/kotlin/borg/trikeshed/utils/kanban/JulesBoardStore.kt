/*
 * Copyright (c) 2017 TrikeShed Contributors
 * AGPLv3 — see LICENSE
 */
package borg.trikeshed.utils.kanban

import borg.trikeshed.jules.JulesCause
import borg.trikeshed.jules.JulesSnapshot
import borg.trikeshed.lib.AppendWal
import kotlinx.datetime.Clock

/**
 * Durable board store: the Kanban's causal truth on disk.
 *
 * Every card mutation appends Confix records to [wal] — one snapshot
 * record (the card's assumpsis) plus one cause record (what provoked it).
 * Replay folds the log back into cards: latest snapshot per session wins,
 * causes accumulate in append order.
 *
 * Lives at ~/.local/forge/jules-board.wal — the TrikeShed state default.
 * The ISAM snapshot spool (high-volume telemetry side of the quandary) lands
 * here later as a sibling file when poll volume demands it.
 *
 * @param wal    the append-only WAL — [borg.trikeshed.lib.AppendWal] SPI.
 *                 JVM: JvmAppendWal (Panama mmap); JS: in-memory; Native: posix mmap.
 */
class JulesBoardStore(
    private val wal: AppendWal,
) {

    /**
     * Persist a card mutation: new snapshot + the cause of the change.
     * Both records are appended under the sessionId key so replay folds correctly.
     */
    suspend fun append(snapshot: JulesSnapshot, drained: Boolean, cause: JulesCause?) {
        wal.append(
            snapshot.sessionId,
            KanbanEventCodec.encodeSnapshot(snapshot, drained).encodeToByteArray()
        )
        if (cause != null) {
            wal.append(
                snapshot.sessionId,
                KanbanEventCodec.encodeCause(snapshot.sessionId, cause).encodeToByteArray()
            )
        }
    }

    /**
     * Append a work-queue cause (WorkQueued/WorkDispatched/WorkDrained) under the
     * workId as the WAL key. Idempotent on (workId, kind): a second WorkQueued for
     * the same workId is a no-op (dedup at dispatch time in the flywheel).
     */
    suspend fun appendWork(workId: String, cause: JulesCause) {
        wal.append(workId, KanbanEventCodec.encodeCause(workId, cause).encodeToByteArray())
    }

    /**
     * Replay causes for a specific workId — used by the flywheel's trajectory reducer
     * to compute the dispatch verdict without loading the full board.
     */
    fun replayCauses(workId: String): List<JulesCause> {
        val causes = mutableListOf<JulesCause>()
        for ((key, payload) in wal.replay()) {
            if (key == workId) {
                val decoded = KanbanEventCodec.decode(payload.decodeToString())
                if (decoded is KanbanEventCodec.CauseEvent) {
                    causes.add(decoded.cause)
                }
            }
        }
        return causes
    }

    /**
     * Fold the WAL into cards. Card state is a projection; the WAL is truth.
     * Returns the full board keyed by sessionId.
     */
    fun load(): MutableMap<String, JulesSessionCard> {
        val snapshots = mutableMapOf<String, KanbanEventCodec.SnapEvent>()
        val causes = mutableMapOf<String, MutableList<JulesCause>>()
        for ((sid, payload) in wal.replay()) {
            when (val ev = KanbanEventCodec.decode(payload.decodeToString())) {
                is KanbanEventCodec.SnapEvent -> snapshots[sid] = ev
                is KanbanEventCodec.CauseEvent -> causes.getOrPut(ev.sid) { mutableListOf() }.add(ev.cause)
                null -> {} // forward-compat: skip unknown record shapes
            }
        }
        val board = mutableMapOf<String, JulesSessionCard>()
        for ((sid, snap) in snapshots) {
            val card = JulesSessionCard.capture(snap.snapshot)
            board[sid] = card.copy(
                drained = snap.drained,
                causes = causes[sid] ?: card.causes,
            )
        }
        return board
    }

    /**
     * Fold the work-cause records into a queue projection keyed by workId.
     *
     * State per workId is derived: latest WorkQueued wins, WorkDispatched attaches
     * a sessionId, WorkDrained marks drained. This is the unified queue — dispatch
     * reads from here, not from a separate state.json.
     *
     * Priority is [QueueEntry.score] descending — caller sorts before dispatch.
     * Idempotent: same workId seen twice → first entry wins (getOrPut).
     */
    fun loadQueue(): List<QueueEntry> {
        val byWorkId = mutableMapOf<String, QueueEntry>()
        for ((workId, payload) in wal.replay()) {
            val ev = KanbanEventCodec.decode(payload.decodeToString()) as? KanbanEventCodec.CauseEvent ?: continue
            val c = ev.cause
            when (c) {
                is JulesCause.WorkQueued -> byWorkId.getOrPut(c.workId) {
                    QueueEntry(
                        workId = c.workId,
                        tier = c.tier,
                        title = c.title,
                        spec = c.spec,
                        parent = c.parent,
                        score = c.score,
                        queuedAt = c.at,
                    )
                }
                is JulesCause.WorkDispatched -> byWorkId[c.workId]?.let {
                    byWorkId[c.workId] = it.copy(
                        sessionId = c.sessionId,
                        attempt = c.attempt,
                        dispatchedAt = c.at
                    )
                }
                is JulesCause.WorkDrained -> byWorkId[c.workId]?.let {
                    byWorkId[c.workId] = it.copy(
                        commitSha = c.commitSha,
                        taskId = c.taskId,
                        drainedAt = c.at,
                    )
                }
                else -> {} // session-cause records do not carry workId; skip
            }
        }
        return byWorkId.values.toList()
    }
}

/**
 * Queue entry projected from the unified WAL.
 * [score] drives dispatch priority — sort by score descending before dispatch.
 */
data class QueueEntry(
    val workId: String,
    val tier: String,
    val title: String,
    val spec: String,
    val parent: String? = null,
    val score: Double = 0.5,
    val queuedAt: Long = 0L,
    val sessionId: String? = null,
    val attempt: Int = 0,
    val dispatchedAt: Long? = null,
    val commitSha: String? = null,
    val taskId: String? = null,
    val drainedAt: Long? = null,
) {
    val isDispatched: Boolean get() = sessionId != null
    val isDrained: Boolean get() = drainedAt != null
}
