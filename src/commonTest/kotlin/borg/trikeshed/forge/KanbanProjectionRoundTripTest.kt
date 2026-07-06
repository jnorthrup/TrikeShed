package borg.trikeshed.forge

import borg.trikeshed.kanban.CardPriority
import borg.trikeshed.kanban.KanbanBoard
import borg.trikeshed.kanban.KanbanBoardId
import borg.trikeshed.kanban.KanbanCard
import borg.trikeshed.kanban.KanbanCardId
import borg.trikeshed.kanban.KanbanColumn
import borg.trikeshed.kanban.KanbanColumnId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for VAL-PROJ-001 / GAP-07: KanbanBoard.toForgeDocument() preserves rootPageId
 * and round-trip is structurally idempotent.
 */
class KanbanProjectionRoundTripTest {

    private fun seedBoard(): KanbanBoard = KanbanBoard(
        id = KanbanBoardId("board-roundtrip"),
        name = "Round Trip Board",
        columns = listOf(
            KanbanColumn(KanbanColumnId("col-backlog"), "Backlog", 0),
            KanbanColumn(KanbanColumnId("col-inprogress"), "In Progress", 1),
            KanbanColumn(KanbanColumnId("col-done"), "Done", 2),
        ),
        cards = listOf(
            KanbanCard(
                id = KanbanCardId("c1"), title = "Card 1",
                description = "", columnId = KanbanColumnId("col-backlog"), order = 0,
                priority = CardPriority.HIGH,
            ),
            KanbanCard(
                id = KanbanCardId("c2"), title = "Card 2",
                description = "description line", columnId = KanbanColumnId("col-inprogress"), order = 0,
                priority = CardPriority.CRITICAL,
            ),
            KanbanCard(
                id = KanbanCardId("c3"), title = "Card 3",
                description = "", columnId = KanbanColumnId("col-done"), order = 0,
                priority = CardPriority.LOW,
            ),
        ),
    )

    @Test
    fun preservesRootPageIdAcrossCalls() {
        val board = seedBoard()
        val doc1 = board.toForgeDocument()
        val root1 = doc1.rootPageId
        val doc2 = board.toForgeDocument()
        val root2 = doc2.rootPageId
        assertEquals(root1, root2, "rootPageId preserved on repeated calls")
        assertEquals(board.id.value, root1.value, "rootPageId equals board id")
    }

    @Test
    fun roundTripStructuralEquality() {
        val src = seedBoard()
        val doc = src.toForgeDocument()
        val out = doc.toKanbanBoard()
        assertEquals(src.id, out.id, "board id preserved")
        assertEquals(src.name, out.name, "board name preserved")
        assertEquals(
            src.cards.map { it.id }.toSet(),
            out.cards.map { it.id }.toSet(),
            "card ids preserved",
        )
    }
}
