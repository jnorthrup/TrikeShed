package borg.trikeshed.kanban

import borg.trikeshed.causal.CausalPhase
import kotlin.jvm.JvmInline

/**
 * BoardTaxonomy — D1: ONE closed column vocabulary for the whole board plane,
 * replacing the three that grew apart (ForgeKanbanIngest's seven strings,
 * JobProjection's `col-*` four, PRELOAD's implied three). Strings exist only at
 * the wire; in memory a column is a [ColId] byte riding a primitive array
 * (PRELOAD: the board is a Cursor, a card IS a row index).
 *
 * The wire ids deliberately equal ForgeKanbanIngest's seven so every existing
 * PWA/panel surface renders unchanged.
 */
enum class BoardCol(val wire: String, val order: Int, val wipLimit: Int? = null) {
    TRIAGE("triage", 0),
    TODO("todo", 1),
    READY("ready", 2),
    RUNNING("running", 3, wipLimit = 3),
    BLOCKED("blocked", 4),
    DONE("done", 5),
    ARCHIVED("archived", 6);

    val id: ColId get() = ColId(ordinal.toByte())

    companion object {
        private val byWire = entries.associateBy { it.wire }

        /** Wire string → column, or null (callers refuse-with-reason, never guess). */
        fun fromWire(wire: String): BoardCol? = byWire[wire.lowercase()]

        fun fromId(id: ColId): BoardCol = entries[id.value.toInt()]

        /**
         * JobProjection's legacy `col-*` four, folded into the canonical seven.
         * col-causal-blocked = waiting on causality (submitted/ready) → TODO;
         * col-agentic = an agent is on it → RUNNING; col-attention = a human
         * must look (blocked/failed) → BLOCKED; col-closed → DONE.
         */
        fun legacyCol(columnId: String): BoardCol? = when (columnId) {
            "col-causal-blocked" -> TODO
            "col-agentic" -> RUNNING
            "col-attention" -> BLOCKED
            "col-closed" -> DONE
            else -> fromWire(columnId)
        }

        /** Job lifecycle strings → coarse column (the JobProjection mapping, canonicalized). */
        fun fromLifecycle(lifecycle: String): BoardCol = when (lifecycle) {
            "submitted" -> TODO
            "ready" -> READY
            "active" -> RUNNING
            "blocked", "failed" -> BLOCKED
            "closed" -> DONE
            else -> TRIAGE
        }

        /**
         * The coarse block-status a card carries when it moves to [columnId] —
         * ONE fold for every column vocabulary that reaches a MoveCard:
         * the three canonical projection ids (`col-*`), JobProjection's legacy
         * four, the seven wire ids, and the status strings themselves. Every
         * consumer (ArticulatedNode.applySignal, future surfaces) reads THIS,
         * never a private when-switch — the old switch let all seven canonical
         * ids fall through to "backlog".
         */
        fun statusFor(columnId: String): String = when (columnId) {
            "col-done", "done" -> "done"
            "col-inprogress", "in-progress" -> "in-progress"
            "col-agentic" -> "in-progress" // legacy: an agent is on it → RUNNING
            "col-closed" -> "done"         // legacy: closed → DONE
            else -> when (fromWire(columnId)) {
                RUNNING -> "in-progress"
                DONE, ARCHIVED -> "done"
                else -> "backlog"
            }
        }

        /**
         * W6.6: the single source for KanbanBoard column lists. Every producer
         * that used to hardcode its own vocabulary (ForgeKanbanIngest's seven
         * strings, ForgeBoardFSM.loadDefault's incompatible four) renders from
         * HERE, so the closed vocabulary cannot drift again.
         */
        fun columns(): List<KanbanColumn> = entries.map { col ->
            KanbanColumn(
                id = KanbanColumnId(col.wire),
                name = col.wire.replaceFirstChar { it.uppercase() },
                order = col.order,
                wipLimit = col.wipLimit,
            )
        }
    }
}

/** Packed column identity — one byte per card in the board's SoA arrays. */
@JvmInline
value class ColId(val value: Byte) {
    val col: BoardCol get() = BoardCol.fromId(this)
    override fun toString(): String = col.wire
}
