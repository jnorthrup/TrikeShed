/*
 * Copyright (c) 2017 TrikeShed Contributors
 * AGPLv3 — see LICENSE
 */
package borg.trikeshed.utils.kanban

import borg.trikeshed.forge.persistence.CausalWal
import borg.trikeshed.jules.JulesCause
import borg.trikeshed.jules.JulesSessionCard
import borg.trikeshed.jules.JulesSnapshot
import java.io.File

/**
 * Durable board store: the Kanban's causal truth on disk.
 *
 * Every card mutation appends Confix records to a [CausalWal] — one snapshot
 * record (the card's new assumpsis) plus one cause record (what provoked it).
 * Replay folds the log back into cards: latest snapshot per session wins,
 * causes accumulate in append order.
 *
 * Lives at ~/.local/forge/jules-board.wal — the TrikeShed state default.
 * The ISAM snapshot spool (high-volume telemetry side of the quandary) lands
 * here later as a sibling file when poll volume demands it.
 */
class JulesBoardStore(
    dir: File = File(System.getProperty("user.home"), ".local/forge"),
) {
    private val wal: CausalWal

    init {
        dir.mkdirs()
        wal = CausalWal(File(dir, "jules-board.wal"))
    }

    /** Persist a card mutation: new snapshot + the cause of the change. */
    suspend fun append(snapshot: JulesSnapshot, drained: Boolean, cause: JulesCause?) {
        wal.append(snapshot.sessionId, KanbanEventCodec.encodeSnapshot(snapshot, drained).encodeToByteArray())
        if (cause != null) {
            wal.append(snapshot.sessionId, KanbanEventCodec.encodeCause(snapshot.sessionId, cause).encodeToByteArray())
        }
    }

    /**
     * Append a work-queue cause (WorkQueued/WorkDispatched/WorkDrained) under the
     * workId as the WAL key. Idempotent on (workId, kind): a second WorkQueued for
     * the same workId is a no-op.
     */
    suspend fun appendWork(workId: String, cause: JulesCause) {
        wal.append(workId, KanbanEventCodec.encodeCause(workId, cause).encodeToByteArray())
    }

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

    /** Fold the log into cards. Card state is a projection; the log is truth. */
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
                    byWorkId[c.workId] = it.copy(sessionId = c.sessionId, attempt = c.attempt, dispatchedAt = c.at)
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

