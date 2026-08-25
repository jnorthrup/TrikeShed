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
    }
}

/** Packed column identity — one byte per card in the board's SoA arrays. */
@JvmInline
value class ColId(val value: Byte) {
    val col: BoardCol get() = BoardCol.fromId(this)
    override fun toString(): String = col.wire
}

/**
 * Coarse column from the causal log alone (CausalKernel: "This IS the kanban
 * column"). READY/BLOCKED are finer than the phase machine sees — they come
 * only from committed Move frames, never inferred here.
 */
fun CausalPhase.toCol(): BoardCol = when (this) {
    CausalPhase.CREATED -> BoardCol.TRIAGE
    CausalPhase.OPEN -> BoardCol.TODO
    CausalPhase.ACTIVE -> BoardCol.RUNNING
    CausalPhase.DRAINING -> BoardCol.DONE
    CausalPhase.CLOSED -> BoardCol.ARCHIVED
}
