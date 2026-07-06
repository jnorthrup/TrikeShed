package borg.trikeshed.forge

import borg.trikeshed.kanban.KanbanBoardId
import borg.trikeshed.kanban.KanbanColumn
import borg.trikeshed.kanban.KanbanColumnId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sanity tests for the column-set projection round-trip.
 */
class KanbanColumnsRoundTripTest {

    @Test
    fun pageColumnsPropertyIsEncodedOntoForgeDocument() {
        val cols = listOf(
            KanbanColumn(KanbanColumnId("col-a"), "A", 0),
            KanbanColumn(KanbanColumnId("col-b"), "B", 1),
        )
        val board = borg.trikeshed.kanban.KanbanBoard(
            id = KanbanBoardId("board-rc"),
            name = "RC Board",
            columns = cols,
            cards = emptyList(),
        )
        val doc = board.toForgeDocument()
        val page = doc.requireBlock(doc.rootPageId)
        val raw = page.properties["kanban.columns"]
        assertTrue(raw != null, "page must have kanban.columns property")
        // Quick structural sanity: both column ids must appear in the encoded JSON.
        assertTrue("col-a" in raw!!, "col-a present in encoded columns")
        assertTrue("col-b" in raw, "col-b present in encoded columns")
    }

    @Test
    fun customColumnsPropertyRoundTrip() {
        val cols = listOf(
            KanbanColumn(KanbanColumnId("col-a"), "A", 0),
            KanbanColumn(KanbanColumnId("col-b"), "B", 1),
        )
        val board = borg.trikeshed.kanban.KanbanBoard(
            id = KanbanBoardId("board-rc2"),
            name = "RC2 Board",
            columns = cols,
            cards = emptyList(),
        )
        val doc = board.toForgeDocument()
        val out = doc.toKanbanBoard()
        val outIds = out.columns.map { it.id.value }.toSet()
        assertEquals(setOf("col-a", "col-b"), outIds, "columns survive round-trip")
    }
}
