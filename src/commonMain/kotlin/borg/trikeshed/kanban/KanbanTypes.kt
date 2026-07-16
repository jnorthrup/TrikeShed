package borg.trikeshed.kanban

import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * Canonical Kanban board types — internalized from libs/forge into root.
 * 
 * Operational model: DAG-based task decomposition. Cards form a directed acyclic graph
 * via parent-child relationships. This mirrors Hermes TODO breakdown but with explicit
 * causal edges (not implicit in the card hierarchy).
 *
 * Design:
 * - dependencies: cards this card blocks (parent -> child edge)
 * - DAG provides the operational structure
 * - Model calls are logged as small job descriptors, not M2M attachments
 * - causal traces flow through the dependency graph
 */

// ─── IDs ─────────────────────────────────────────────────────────────────--

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

// ─── Model call job descriptor ────────────────────────────────────────────

/**
 * Small job descriptor for a model invocation.
 * Logged, not stored as attachment. Can be dispatched to modelmux.
 * 
 * This is a small job, not a marathon shop - lightweight, composable,
 * can be queued and tracked independently.
 */
@Serializable
data class ModelCallDescriptor(
    val id: ModelCallId,
    val cardId: KanbanCardId,        // Card that spawned this call
    val modelId: String,
    val provider: String,
    val action: String,              // "chat", "embed", "stream"
    val prompt: String,              // The input prompt
    val status: ModelCallStatus = ModelCallStatus.PENDING,
    val createdAt: Long = nowMs(),
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val result: String? = null,      // Truncated result
    val error: String? = null,
)

@Serializable
data class ModelCallId(val value: String) {
    companion object {
        fun generate(): ModelCallId = ModelCallId(newId("mcall"))
    }
}

@Serializable
enum class ModelCallStatus {
    PENDING,    // Queued, not yet dispatched
    RUNNING,    // In progress
    CACHED,     // Result from cache
    COMPLETED,   // Successfully completed
    FAILED      // Error
}

// ─── Core types ─────────────────────────────────────────────────────────---

@Serializable
enum class CardPriority { LOW, MEDIUM, HIGH, CRITICAL }

/**
 * Kanban column — represents a stage in the workflow.
 */
@Serializable
data class KanbanColumn(
    val id: KanbanColumnId,
    val name: String,
    val order: Int,
    /** Work-in-progress limit. null = no limit. */
    val wipLimit: Int? = null,
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * Kanban card — a unit of work in the DAG.
 * 
 * The DAG is formed via `dependencies` (parents) and implicit children
 * (cards that depend on this card). This enables:
 * - topological sorting for execution order
 * - cycle detection
 * - causal trace propagation
 */
@Serializable
data class KanbanCard(
    val id: KanbanCardId,
    val title: String,
    val description: String = "",
    val columnId: KanbanColumnId,
    val order: Int = 0,
    val assignee: String? = null,
    val priority: CardPriority = CardPriority.MEDIUM,
    /** Parent card IDs — this card depends on these being done first (DAG edge: parent -> child). */
    val dependencies: List<KanbanCardId> = emptyList(),
    val tags: Set<String> = emptySet(),
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Long = nowMs(),
    val updatedAt: Long = nowMs(),
)

/**
 * Swimlane — horizontal partition (e.g., by assignee, theme).
 */
@Serializable
data class Swimlane(
    val id: SwimlaneId,
    val name: String,
    val color: String,
    val cardIds: List<KanbanCardId> = emptyList(),
)

/**
 * Kanban board — contains columns, cards, and the DAG of dependencies.
 */
@Serializable
data class KanbanBoard(
    val id: KanbanBoardId,
    val name: String,
    val columns: List<KanbanColumn>,
    val cards: List<KanbanCard>,
    val swimlanes: List<Swimlane> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    /** Audit log of model calls spawned by cards */
    val modelCallLog: List<ModelCallDescriptor> = emptyList(),
)

// ─── Model call log helpers ────────────────────────────────────────────────

/**
 * Add a model call descriptor to the board's log.
 */
fun KanbanBoard.logModelCall(descriptor: ModelCallDescriptor): KanbanBoard =
    copy(modelCallLog = modelCallLog + descriptor)

/**
 * Update a model call in the log.
 */
fun KanbanBoard.updateModelCall(callId: ModelCallId, update: ModelCallDescriptor.() -> ModelCallDescriptor): KanbanBoard =
    copy(modelCallLog = modelCallLog.map { if (it.id == callId) it.update() else it })

/**
 * Get all model calls for a card.
 */
fun KanbanBoard.modelCallsForCard(cardId: KanbanCardId): List<ModelCallDescriptor> =
    modelCallLog.filter { it.cardId == cardId }

// ─── DAG operations ─────────────────────────────────────────────────────────

/**
 * Get all cards that depend on this card (children in the DAG).
 */
fun KanbanBoard.childrenOf(cardId: KanbanCardId): List<KanbanCard> =
    cards.filter { cardId in it.dependencies }

/**
 * Get all parent cards (dependencies) for a card.
 */
fun KanbanBoard.parentsOf(cardId: KanbanCardId): List<KanbanCard> {
    val card = cards.find { it.id == cardId } ?: return emptyList()
    return card.dependencies.mapNotNull { depId -> cards.find { it.id == depId } }
}

/**
 * Topologically sort cards — parents before children.
 * Returns null if cycle detected.
 */
fun KanbanBoard.topologicalSort(): List<KanbanCard>? {
    val visited = mutableSetOf<KanbanCardId>()
    val result = mutableListOf<KanbanCard>()
    val inProgress = mutableSetOf<KanbanCardId>()

    fun visit(cardId: KanbanCardId): Boolean {
        if (cardId in inProgress) return false // cycle
        if (cardId in visited) return true

        inProgress.add(cardId)
        val card = cards.find { it.id == cardId } ?: return true
        for (dep in card.dependencies) {
            if (!visit(dep)) return false
        }
        inProgress.remove(cardId)
        visited.add(cardId)
        result.add(card)
        return true
    }

    for (card in cards) {
        if (card.id !in visited) {
            if (!visit(card.id)) return null
        }
    }
    return result
}

/**
 * Check for cycles in the DAG.
 */
fun KanbanBoard.hasCycle(): Boolean = topologicalSort() == null

// ─── Board mutations ─────────────────────────────────────────────────────---

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
    dependencies: List<KanbanCardId> = emptyList(),
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
        dependencies = dependencies,
    )
    return copy(cards = cards + card)
}

/** Add a dependency (parent -> child edge). */
fun KanbanBoard.addDependency(childId: KanbanCardId, parentId: KanbanCardId): KanbanBoard {
    val newCards = cards.map { card ->
        if (card.id == childId && parentId !in card.dependencies) {
            card.copy(dependencies = card.dependencies + parentId, updatedAt = nowMs())
        } else card
    }
    return copy(cards = newCards)
}

/** Remove a dependency. */
fun KanbanBoard.removeDependency(childId: KanbanCardId, parentId: KanbanCardId): KanbanBoard {
    val newCards = cards.map { card ->
        if (card.id == childId) {
            card.copy(dependencies = card.dependencies - parentId, updatedAt = nowMs())
        } else card
    }
    return copy(cards = newCards)
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

// ─── Internal helpers ─────────────────────────────────────────────────────

private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

private fun newId(prefix: String): String =
    "${prefix}-${nowMs().toString(16)}-${Random.nextLong().and(0xFFFFFFFFL).toString(16)}"
