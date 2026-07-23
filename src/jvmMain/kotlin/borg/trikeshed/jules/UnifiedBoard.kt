/*
 * Copyright (c) 2017 TrikeShed Contributors
 * AGPLv3 — see LICENSE
 */
package borg.trikeshed.jules

import borg.trikeshed.kanban.KanbanBoard
import borg.trikeshed.kanban.KanbanCard
import borg.trikeshed.kanban.KanbanCardId
import borg.trikeshed.kanban.KanbanColumn
import borg.trikeshed.kanban.KanbanColumnId
import kotlinx.datetime.Clock

/**
 * Unified board — Jules session cards projected onto the Forge kanban board.
 *
 * The Jules session card carries a [borg.trikeshed.kanban.KanbanCard] whose
 * [borg.trikeshed.kanban.KanbanCard.columnId] is the Jules lane's column name
 * (see [JulesLane.columnName], e.g. "Causal Ready", "Agentic Work"). The Forge
 * kanban board is keyed on the same `KanbanColumnId`. Unifying them is a
 * column-keyed merge, not a parallel truth.
 *
 * The Jules REST plane and the Forge markdown board are two projections of the
 * same causal wheel. This function returns one board keyed on `columnId`.
 *
 * @param kanban  the persisted Forge board (work pool + already-drained items)
 * @param cards   the live Jules session cards
 * @return merged [KanbanBoard] with all cards, columns unioned
 */
fun unifyBoard(
    kanban: KanbanBoard,
    cards: Collection<JulesSessionCard>,
): KanbanBoard {
    val julesCards = cards.map { it.card }
    // Jules card wins on column id (it reflects live state); kanban wins on
    // card id uniqueness (markdown work pool items don't collide with sessions).
    val byId = LinkedHashMap<KanbanCardId, KanbanCard>()
    for (c in kanban.cards) byId[c.id] = c
    for (c in julesCards) byId[c.id] = c
    val merged = byId.values.toList()

    // Columns: union by column id, preserving kanban's order;
    // append any Jules-lane-named columns not present in the kanban (in lane order).
    val seen = kanban.columns.map { it.id.value }.toMutableList()
    val cols = kanban.columns.toMutableList()
    for (lane in JulesLane.values()) {
        val cid = KanbanColumnId(lane.columnName)
        if (cid.value !in seen) {
            cols.add(KanbanColumn(cid, lane.columnName, lane.order))
            seen.add(cid.value)
        }
    }
    return kanban.copy(columns = cols, cards = merged)
}

/**
 * Paddles of the saturation wheel, in cycle order.
 *
 * `SLICE/CURATE → READY QUEUE → DISPATCH → RUNNING → GUIDE/AWAITING →
 * HARVEST/REVIEW → LAND/MERGE → CURATE/REFILL → (SLICE)`
 */
enum class WheelPaddle(val label: String) {
    SLICE("SLICE/CURATE"),
    READY("READY QUEUE"),
    DISPATCH("DISPATCH"),
    RUNNING("RUNNING"),
    GUIDE("GUIDE/AWAIT"),
    HARVEST("HARVEST/REV"),
    LAND("LAND/MERGE"),
    CURATE("CURATE/REFILL"),
    ;
    val next get() = entries[(ordinal + 1) % entries.size]
}

/**
 * Saturation wheel snapshot — one row per paddle with live counts derived
 * from a [unifyBoard] merged board.
 *
 * Per the flywheel skill: this is a projection, never truth. Every count is
 * traced to a card-lane in the merged board, not to a counter in the wheel.
 */
data class WheelSnapshot(
    val paddle: WheelPaddle,
    val count: Int,
    /** card titles occupying this paddle (truncated, ≤ 4) */
    val occupants: List<String>,
) {
    /** Render one paddle: label[pad] count  occupants. */
    fun render(): String = buildString {
        append(paddle.label.padEnd(14))
        append('[')
        append(count.toString().padStart(2))
        append("]  ")
        append(occupants.joinToString(" | ") { it.take(40) })
    }
}

/**
 * Project a merged board onto the saturation wheel.
 *
 * Mapping (JulesLane → paddle):
 *  - TO_DO              → SLICE/CURATE        (work pool items in the markdown board)
 *  - CAUSAL_READY       → READY QUEUE         (ready for dispatch)
 *  - (dispatch action)  → DISPATCH            (cards transitioning queued→in-progress; counted as zero on a snapshot)
 *  - AGENTIC_WORK       → RUNNING             (live Jules sessions)
 *  - CAUSAL_BLOCKED     → GUIDE/AWAIT        (AWAITING_USER_FEEDBACK — needs the brain)
 *  - REVIEW             → HARVEST/REV         (AWAITING_PLAN_APPROVAL or COMPLETED-with-patch)
 *  - DONE               → LAND/MERGE          (drained + committed)
 *  - FAILED             → HARVEST/REV         (failed settlements requeue here)
 *  - (crossed-paddle marker `●`) is computed by the caller from consecutive snapshots.
 */
fun saturationWheel(board: KanbanBoard): List<WheelSnapshot> {
    val byCol = board.cards.groupBy { it.columnId.value }
    fun col(name: String): List<KanbanCard> = byCol[name] ?: emptyList()

    return listOf(
        WheelSnapshot(WheelPaddle.SLICE,
            col(JulesLane.TO_DO.columnName).size,
            col(JulesLane.TO_DO.columnName).map { it.title }),
        WheelSnapshot(WheelPaddle.READY,
            col(JulesLane.CAUSAL_READY.columnName).size,
            col(JulesLane.CAUSAL_READY.columnName).map { it.title }),
        WheelSnapshot(WheelPaddle.DISPATCH,
            0, emptyList()),
        WheelSnapshot(WheelPaddle.RUNNING,
            col(JulesLane.AGENTIC_WORK.columnName).size,
            col(JulesLane.AGENTIC_WORK.columnName).map { it.title }),
        WheelSnapshot(WheelPaddle.GUIDE,
            col(JulesLane.CAUSAL_BLOCKED.columnName).size,
            col(JulesLane.CAUSAL_BLOCKED.columnName).map { it.title }),
        WheelSnapshot(WheelPaddle.HARVEST,
            (col(JulesLane.REVIEW.columnName) + col(JulesLane.FAILED.columnName)).size,
            (col(JulesLane.REVIEW.columnName) + col(JulesLane.FAILED.columnName)).map { it.title }),
        WheelSnapshot(WheelPaddle.LAND,
            col(JulesLane.DONE.columnName).size,
            col(JulesLane.DONE.columnName).map { it.title }),
        WheelSnapshot(WheelPaddle.CURATE,
            0, emptyList()),  // refilled by the slicer; empty on a snapshot
    )
}

/** The current bottleneck: highest-pressure paddle that isn't LAND/DONE/SLICE/CURATE. */
fun bottleneck(wheel: List<WheelSnapshot>): WheelSnapshot? =
    wheel.filter { it.paddle != WheelPaddle.LAND && it.paddle != WheelPaddle.SLICE && it.paddle != WheelPaddle.CURATE }
        .maxByOrNull { it.count }

/** Render the saturation wheel as a single TUI block. */
fun renderWheel(board: KanbanBoard, activeAgents: Int, capacity: Int, cycleMs: Long): String {
    val wheel = saturationWheel(board)
    val bot = bottleneck(wheel)
    val total = board.cards.size
    val now = Clock.System.now().toEpochMilliseconds()
    return buildString {
        appendLine("FLYWHEEL  cards=$total  agents=$activeAgents/$capacity  cycle=${cycleMs}ms  t=$now")
        appendLine("$ ---")
        for (p in wheel) appendLine(p.render())
        appendLine("$ ---")
        appendLine("bottleneck: ${bot?.paddle?.label ?: "none"} [${bot?.count ?: 0}]")
        appendLine("engine: " + if (activeAgents == 0) "IDLE" else "ACTIVE")
        appendLine("$ ---")
    }
}
