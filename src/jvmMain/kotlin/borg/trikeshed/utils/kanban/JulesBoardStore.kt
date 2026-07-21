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

    /** Fold the log into cards. Card state is a projection; the log is truth. */
    fun load(): MutableMap<String, JulesSessionCard> {
        val snapshots = HashMap<String, KanbanEventCodec.SnapEvent>()
        val causes = HashMap<String, MutableList<JulesCause>>()
        for ((sid, payload) in wal.replay()) {
            when (val ev = KanbanEventCodec.decode(payload.decodeToString())) {
                is KanbanEventCodec.SnapEvent -> snapshots[sid] = ev
                is KanbanEventCodec.CauseEvent -> causes.getOrPut(ev.sid) { mutableListOf() }.add(ev.cause)
                null -> {} // forward-compat: skip unknown record shapes
            }
        }
        val board = HashMap<String, JulesSessionCard>()
        for ((sid, snap) in snapshots) {
            val card = JulesSessionCard.capture(snap.snapshot)
            board[sid] = card.copy(
                drained = snap.drained,
                causes = causes[sid] ?: card.causes,
            )
        }
        return board
    }
}
