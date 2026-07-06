package borg.trikeshed.ccek

import borg.trikeshed.forge.toForgeDocument
import borg.trikeshed.forge.toKanbanBoard
import borg.trikeshed.kanban.CardPriority
import borg.trikeshed.kanban.KanbanBoard
import borg.trikeshed.kanban.KanbanBoardId
import borg.trikeshed.kanban.KanbanCard
import borg.trikeshed.kanban.KanbanCardId
import borg.trikeshed.kanban.KanbanColumn
import borg.trikeshed.kanban.KanbanColumnId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct test of the projection layer only — bypasses CCEK entirely.
 * If THIS test fails, the projection layer is broken. If it passes but
 * CcekMoveCardGranularTest fails, the bug is in CCEK.
 */
class ProjectionDirectTest {

    @Test
    fun directRoundTripPreservesColumnSet() {
        val board = KanbanBoard(
            id = KanbanBoardId("board-direct"),
            name = "Direct",
            columns = listOf(
                KanbanColumn(KanbanColumnId("col-a"), "A", 0),
                KanbanColumn(KanbanColumnId("col-b"), "B", 1),
            ),
            cards = listOf(
                KanbanCard(
                    id = KanbanCardId("c1"),
                    title = "Card 1",
                    description = "",
                    columnId = KanbanColumnId("col-a"),
                    order = 0,
                    priority = CardPriority.MEDIUM,
                ),
            ),
        )
        val doc = board.toForgeDocument()
        // Assert projection-level column set is round-trip stable
        val out = doc.toKanbanBoard()
        val outCols = out.columns.map { it.id.value }.toSet()
        assertEquals(setOf("col-a", "col-b"), outCols, "columns round-trip preserves")
        // c1's projection has its source KanbanCardId preserved
        val c1out = out.cards.first { it.id.value == "c1" }
        assertEquals(KanbanColumnId("col-a"), c1out.columnId, "c1 starts in col-a")
        // Now manually set kanban.column.id on doc's c1 heading -> round-trip -> should reach col-b
        val c1Block = doc.blocks["c1"]!!
        val updated = borg.trikeshed.forge.ForgeDoc.setProperty(
            doc,
            c1Block.id,
            "kanban.column.id",
            "col-b",
        )
        val out2 = updated.toKanbanBoard()
        val c1out2 = out2.cards.first { it.id.value == "c1" }
        assertEquals(
            KanbanColumnId("col-b"),
            c1out2.columnId,
            "c1 manual column.id=col-b must project to KanbanColumnId(col-b)",
            // If this fails, the lookup in toKanbanBoard() is wrong.
        )
    }
}
