package borg.trikeshed.forge.demo

import borg.trikeshed.forge.*
import borg.trikeshed.forge.kanban.*
import borg.trikeshed.cursor.*
import kotlinx.coroutines.*
import java.time.Instant

/**
 * KanbanBoard Demo - demonstrates real Forge Kanban with CCEK choreography.
 * 
 * This shows:
 * - Real KanbanBoard with columns and cards
 * - LCNC cursor facets applied to graph nodes
 * - User-signal events flowing from board changes
 */
object KanbanBoardDemo {

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        println("📋 KanbanBoard Demo Starting...")
        println()

        // 1. Create real KanbanBoard
        println("1. Create KanbanBoard")
        val board = createDemoBoard()
        println("   Board: ${board.name}")
        println("   Columns: ${board.columns.size}")
        println()

        // 2. Add cards to columns
        println("2. Add cards to columns")
        val cards = addDemoCards(board)
        for (col in board.columns) {
            val colCards = cards.filter { it.columnId == col.id }
            println("   ${col.name}: ${colCards.size} cards")
        }
        println()

        // 3. Apply LCNC cursor facets to board
        println("3. Apply LCNC cursor facets")
        val facets = applyLcncFacetsToBoard(board)
        println("   Layout: ${facets.layoutHint}")
        println("   WTK: ${facets.wtkHint}")
        println("   DAG: ${facets.dagCoordinate}")
        println()

        // 4. Convert to Cursor for CCEK
        println("4. Convert to Cursor for CCEK")
        val cursor = boardToCursor(board, cards)
        println("   Cursor rows: ${cursor.size}")
        println()

        // 5. Query board with CCEK
        println("5. CCEK graph-node query")
        val queryResult = queryBoard(board, cards, facets)
        println("   Resolved nodes: ${queryResult.size}")
        for (node in queryResult.take(3)) {
            println("   - ${node.label} [${node.columnId}]")
        }
        if (queryResult.size > 3) println("   ... and ${queryResult.size - 3} more")
        println()

        // 6. User-signal events
        println("6. User-signal events")
        val signals = emitUserSignals(board, cards)
        println("   Events: ${signals.size}")
        for (signal in signals.take(3)) {
            println("   - ${signal.type}: ${signal.source}")
        }
        println()

        // 7. Simulate board mutation
        println("7. Simulate board mutation")
        val newCards = moveCard(board, cards, cards[0].id, board.columns[1].id)
        println("   Moved card to next column")
        
        // 8. Convert to new cursor after mutation
        println("8. Convert mutated board to Cursor")
        val newCursor = boardToCursor(board, newCards)
        println("   New cursor rows: ${newCursor.size}")
        println("   User signal emitted")
        println()

        println("✅ KanbanBoard Demo Complete!")
    }
}

/**
 * Create a demo board with columns.
 */
fun createDemoBoard(): KanbanBoard {
    return KanbanBoard(
        id = KanbanBoardId.generate(),
        name = "TrikeShed Development",
        columns = listOf(
            KanbanColumn(KanbanColumnId.generate(), "Backlog", 0),
            KanbanColumn(KanbanColumnId.generate(), "In Progress", 1),
            KanbanColumn(KanbanColumnId.generate(), "Review", 2),
            KanbanColumn(KanbanColumnId.generate(), "Done", 3)
        ),
        cards = emptyList()
    )
}

/**
 * Add demo cards to board columns.
 */
fun addDemoCards(board: KanbanBoard): List<KanbanCard> {
    val backlogCol = board.columns[0]
    val inProgressCol = board.columns[1]
    val reviewCol = board.columns[2]
    val doneCol = board.columns[3]
    
    return listOf(
        KanbanCard(KanbanCardId.generate(), "Setup CI pipeline", "", backlogCol.id, 1),
        KanbanCard(KanbanCardId.generate(), "Add user authentication", "", backlogCol.id, 1),
        KanbanCard(KanbanCardId.generate(), "Fix login bug", "", backlogCol.id, 2),
        KanbanCard(KanbanCardId.generate(), "Implement API gateway", "", inProgressCol.id, 3),
        KanbanCard(KanbanCardId.generate(), "Code review: HTX client", "", reviewCol.id, 2),
        KanbanCard(KanbanCardId.generate(), "Initial commit", "", doneCol.id, 1)
    )
}

/**
 * Apply LCNC cursor facets to board.
 */
fun applyLcncFacetsToBoard(board: KanbanBoard): LcncFacetGroup {
    return LcncFacetGroup(
        layoutHint = LayoutHint.Horizontal,
        wtkHint = WtkHint.Table,
        dagCoordinate = null
    )
}

/**
 * Convert board + cards to Cursor.
 */
fun boardToCursor(board: KanbanBoard, cards: List<KanbanCard>): List<Map<String, Any?>> {
    return cards.map { card ->
        mapOf(
            "id" to card.id.value,
            "title" to card.title,
            "column" to card.columnId.value,
            "priority" to card.priority.name,
            "order" to card.order
        )
    }
}

/**
 * Query board with CCEK choreography.
 */
fun queryBoard(board: KanbanBoard, cards: List<KanbanCard>, facets: LcncFacetGroup): List<QueryNode> {
    return cards.map { card ->
        QueryNode(
            id = card.id.value,
            label = card.title,
            columnId = card.columnId.value,
            facets = facets
        )
    }
}

/**
 * Emit user-signals from board.
 */
fun emitUserSignals(board: KanbanBoard, cards: List<KanbanCard>): List<UserSignal> {
    return cards.map { card ->
        UserSignal(
            type = "card-created",
            source = card.id.value,
            timestamp = Instant.now()
        )
    }
}

/**
 * Move a card between columns.
 */
fun moveCard(board: KanbanBoard, cards: List<KanbanCard>, cardId: KanbanCardId, toColumnId: KanbanColumnId): List<KanbanCard> {
    return cards.map { card ->
        if (card.id == cardId) {
            card.copy(columnId = toColumnId, updatedAt = System.currentTimeMillis())
        } else {
            card
        }
    }
}

/**
 * Query node result.
 */
data class QueryNode(
    val id: String,
    val label: String,
    val columnId: String,
    val facets: LcncFacetGroup
)

/**
 * User signal event.
 */
data class UserSignal(
    val type: String,
    val source: String,
    val timestamp: Instant
)