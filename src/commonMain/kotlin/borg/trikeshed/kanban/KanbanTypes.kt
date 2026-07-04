package borg.trikeshed.kanban

import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * Canonical Kanban board types — internalized from libs/forge into root.
 *
 * Pruned from libs/forge/KanbanTypes.kt:
 * - No CascadeGraph/PatchBay references (those stay in the forge lib)
 * - No platformUtils dependency (uses kotlinx-datetime + kotlin.random)
 * - toCascadeGraph/toMermaid/toDot stay in forge lib via extension on its own copy
 *
 * This is the root-level, commonMain-safe type system for all Kanban consumers.
 */

// ─── IDs ───────────────────────────────────────────────────────────────────

@Serializable
data class KanbanBoardId(val value: String) {
    companion object {
        fun generate(): KanbanBoardId = KanbanBoardId(newId("board"))
    }
}

@Serializable
data class KanbanColumnId(val value: String) {
    companion object {
        fun generate(): KanbanColumnId = KanbanColumnId(newId("col"))
    }
}

@Serializable
data class KanbanCardId(val value: String) {
    companion object {
        fun generate(): KanbanCardId = KanbanCardId(newId("card"))
    }
}

@Serializable
data class SwimlaneId(val value: String) {
    companion object {
        fun generate(): SwimlaneId = SwimlaneId(newId("lane"))
    }
}

// ─── Core types ────────────────────────────────────────────────────────────

@Serializable
enum class CardPriority { LOW, MEDIUM, HIGH, CRITICAL }

@Serializable
data class KanbanColumn(
    val id: KanbanColumnId,
    val name: String,
    val order: Int,
    /** Work-in-progress limit. null = no limit. */
    val wipLimit: Int? = null,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class KanbanCard(
    val id: KanbanCardId,
    val title: String,
    val description: String = "",
    val columnId: KanbanColumnId,
    val order: Int = 0,
    val assignee: String? = null,
    val priority: CardPriority = CardPriority.MEDIUM,
    /** IDs of cards that this card blocks (depends on). */
    val dependencies: List<KanbanCardId> = emptyList(),
    val tags: Set<String> = emptySet(),
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Long = nowMs(),
    val updatedAt: Long = nowMs(),
)

@Serializable
data class Swimlane(
    val id: SwimlaneId,
    val name: String,
    val color: String,
    val cardIds: List<KanbanCardId> = emptyList(),
)

@Serializable
data class KanbanBoard(
    val id: KanbanBoardId,
    val name: String,
    val columns: List<KanbanColumn>,
    val cards: List<KanbanCard>,
    val swimlanes: List<Swimlane> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
)

// ─── Board mutations ────────────────────────────────────────────────────────

/** Move a card to a different column; stamps updatedAt. */
fun KanbanBoard.moveCard(cardId: KanbanCardId, toColumnId: KanbanColumnId): KanbanBoard {
    val newCards = cards.map { card ->
        if (card.id == cardId) card.copy(columnId = toColumnId, updatedAt = nowMs())
        else card
    }
    return copy(cards = newCards)
}

/** Create a new card in the given column and append it to the board. */
fun KanbanBoard.createCard(
    title: String,
    columnId: KanbanColumnId,
    priority: CardPriority = CardPriority.MEDIUM,
    assignee: String? = null,
    description: String = "",
): KanbanBoard {
    val maxOrder = cards.filter { it.columnId == columnId }.maxOfOrNull { it.order } ?: -1
    val card = KanbanCard(
        id = KanbanCardId.generate(),
        title = title,
        description = description,
        columnId = columnId,
        order = maxOrder + 1,
        priority = priority,
        assignee = assignee,
    )
    return copy(cards = cards + card)
}

/** Delete a card by id. */
fun KanbanBoard.deleteCard(cardId: KanbanCardId): KanbanBoard =
    copy(cards = cards.filter { it.id != cardId })

/** Update a card in-place (identity by id). No-op if id not found. */
fun KanbanBoard.updateCard(updated: KanbanCard): KanbanBoard =
    copy(cards = cards.map { if (it.id == updated.id) updated.copy(updatedAt = nowMs()) else it })

/** Cards in a given column, sorted by order. */
fun KanbanBoard.cardsInColumn(columnId: KanbanColumnId): List<KanbanCard> =
    cards.filter { it.columnId == columnId }.sortedBy { it.order }

/** Count of cards in a column (for WIP limit display). */
fun KanbanBoard.wipCount(columnId: KanbanColumnId): Int =
    cards.count { it.columnId == columnId }

// ─── Internal helpers ───────────────────────────────────────────────────────

private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

private fun newId(prefix: String): String =
    "${prefix}-${nowMs().toString(16)}-${Random.nextLong().and(0xFFFFFFFFL).toString(16)}"
