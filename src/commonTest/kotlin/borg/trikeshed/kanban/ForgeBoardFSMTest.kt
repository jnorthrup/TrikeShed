package borg.trikeshed.kanban

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForgeBoardFSMTest {

    private fun now() = Clock.System.now().toEpochMilliseconds()

    @BeforeTest
    fun reset() {
        ForgeBoardFSM.reset()
    }

    // ── Board loading ────────────────────────────────────────────────────────

    @Test
    fun `loadDefault populates board and selects it`() {
        ForgeBoardFSM.loadDefault()
        val state = ForgeBoardFSM.current()
        assertNotNull(state.activeBoard, "active board must be set after loadDefault")
        assertEquals("Forge Board", state.activeBoard!!.name)
        assertEquals(4, state.activeBoard!!.columns.size)
        assertTrue(state.activeBoard!!.cards.isNotEmpty())
    }

    @Test
    fun `BoardLoaded adds board and selects it if none active`() {
        val col = KanbanColumnId("c1")
        val board = KanbanBoard(
            id = KanbanBoardId("b1"),
            name = "Test",
            columns = listOf(KanbanColumn(col, "Todo", 0)),
            cards = emptyList(),
        )
        ForgeBoardFSM.emit(ForgeBoardEvent.BoardLoaded(board, now()))
        val state = ForgeBoardFSM.current()
        assertEquals("b1", state.activeBoardId?.value)
        assertEquals("Test", state.boards[KanbanBoardId("b1")]?.name)
    }

    // ── Card mutation ────────────────────────────────────────────────────────

    @Test
    fun `CardCreated appends card to board`() {
        ForgeBoardFSM.loadDefault()
        val board = ForgeBoardFSM.current().activeBoard!!
        val targetCol = board.columns.first().id
        val cardId = KanbanCardId.generate()
        ForgeBoardFSM.emit(
            ForgeBoardEvent.CardCreated(
                boardId = board.id,
                cardId = cardId,
                columnId = targetCol,
                title = "New card",
                timestampMs = now(),
            )
        )
        val updated = ForgeBoardFSM.current().activeBoard!!
        assertTrue(updated.cards.any { it.id == cardId }, "card must be in board after CardCreated")
        assertEquals("New card", updated.cards.first { it.id == cardId }.title)
    }

    @Test
    fun `CardMoved changes card column`() {
        ForgeBoardFSM.loadDefault()
        val board = ForgeBoardFSM.current().activeBoard!!
        val card = board.cards.first()
        val otherCol = board.columns.first { it.id != card.columnId }.id
        ForgeBoardFSM.emit(ForgeBoardEvent.CardMoved(board.id, card.id, otherCol, now()))
        val moved = ForgeBoardFSM.current().activeBoard!!.cards.first { it.id == card.id }
        assertEquals(otherCol, moved.columnId)
    }

    @Test
    fun `CardDeleted removes card`() {
        ForgeBoardFSM.loadDefault()
        val board = ForgeBoardFSM.current().activeBoard!!
        val card = board.cards.first()
        ForgeBoardFSM.emit(ForgeBoardEvent.CardDeleted(board.id, card.id, now()))
        assertTrue(ForgeBoardFSM.current().activeBoard!!.cards.none { it.id == card.id })
    }

    @Test
    fun `CardUpdated changes card fields`() {
        ForgeBoardFSM.loadDefault()
        val board = ForgeBoardFSM.current().activeBoard!!
        val original = board.cards.first()
        val updated = original.copy(title = "Updated title", priority = CardPriority.CRITICAL)
        ForgeBoardFSM.emit(ForgeBoardEvent.CardUpdated(board.id, updated, now()))
        val result = ForgeBoardFSM.current().activeBoard!!.cards.first { it.id == original.id }
        assertEquals("Updated title", result.title)
        assertEquals(CardPriority.CRITICAL, result.priority)
    }

    // ── Drag lifecycle ───────────────────────────────────────────────────────

    @Test
    fun `DragStarted sets dragState`() {
        ForgeBoardFSM.loadDefault()
        val board = ForgeBoardFSM.current().activeBoard!!
        val card = board.cards.first()
        ForgeBoardFSM.emit(ForgeBoardEvent.DragStarted(board.id, card.id, card.columnId, now()))
        assertNotNull(ForgeBoardFSM.current().dragState)
        assertEquals(card.id, ForgeBoardFSM.current().dragState!!.cardId)
    }

    @Test
    fun `DragOver updates overColumnId`() {
        ForgeBoardFSM.loadDefault()
        val board = ForgeBoardFSM.current().activeBoard!!
        val card = board.cards.first()
        val otherCol = board.columns.first { it.id != card.columnId }.id
        ForgeBoardFSM.emit(ForgeBoardEvent.DragStarted(board.id, card.id, card.columnId, now()))
        ForgeBoardFSM.emit(ForgeBoardEvent.DragOver(otherCol, now()))
        assertEquals(otherCol, ForgeBoardFSM.current().dragState!!.overColumnId)
    }

    @Test
    fun `DragDropped commits move and clears dragState`() {
        ForgeBoardFSM.loadDefault()
        val board = ForgeBoardFSM.current().activeBoard!!
        val card = board.cards.first()
        val otherCol = board.columns.first { it.id != card.columnId }.id
        ForgeBoardFSM.emit(ForgeBoardEvent.DragStarted(board.id, card.id, card.columnId, now()))
        ForgeBoardFSM.emit(ForgeBoardEvent.DragOver(otherCol, now()))
        ForgeBoardFSM.emit(ForgeBoardEvent.DragDropped(now()))
        assertNull(ForgeBoardFSM.current().dragState, "dragState must be null after drop")
        val movedCard = ForgeBoardFSM.current().activeBoard!!.cards.first { it.id == card.id }
        assertEquals(otherCol, movedCard.columnId)
    }

    @Test
    fun `DragCancelled clears dragState without moving card`() {
        ForgeBoardFSM.loadDefault()
        val board = ForgeBoardFSM.current().activeBoard!!
        val card = board.cards.first()
        val originalCol = card.columnId
        val otherCol = board.columns.first { it.id != card.columnId }.id
        ForgeBoardFSM.emit(ForgeBoardEvent.DragStarted(board.id, card.id, card.columnId, now()))
        ForgeBoardFSM.emit(ForgeBoardEvent.DragOver(otherCol, now()))
        ForgeBoardFSM.emit(ForgeBoardEvent.DragCancelled(now()))
        assertNull(ForgeBoardFSM.current().dragState)
        // card stays in original column
        val unchanged = ForgeBoardFSM.current().activeBoard!!.cards.first { it.id == card.id }
        assertEquals(originalCol, unchanged.columnId)
    }

    // ── Board extensions ─────────────────────────────────────────────────────

    @Test
    fun `cardsInColumn returns only cards in that column sorted by order`() {
        val col1 = KanbanColumnId("c1")
        val col2 = KanbanColumnId("c2")
        val board = KanbanBoard(
            id = KanbanBoardId.generate(),
            name = "Test",
            columns = listOf(KanbanColumn(col1, "A", 0), KanbanColumn(col2, "B", 1)),
            cards = listOf(
                KanbanCard(KanbanCardId("k1"), "First",  columnId = col1, order = 1),
                KanbanCard(KanbanCardId("k2"), "Second", columnId = col2, order = 0),
                KanbanCard(KanbanCardId("k3"), "Third",  columnId = col1, order = 0),
            ),
        )
        val col1Cards = board.cardsInColumn(col1)
        assertEquals(2, col1Cards.size)
        assertEquals("k3", col1Cards[0].id.value, "lower order should come first")
        assertEquals("k1", col1Cards[1].id.value)
    }

    @Test
    fun `wipCount counts cards in column`() {
        val col = KanbanColumnId("c1")
        val board = KanbanBoard(
            id = KanbanBoardId.generate(), name = "T",
            columns = listOf(KanbanColumn(col, "Todo", 0, wipLimit = 2)),
            cards = listOf(
                KanbanCard(KanbanCardId("a"), "A", columnId = col),
                KanbanCard(KanbanCardId("b"), "B", columnId = col),
                KanbanCard(KanbanCardId("c"), "C", columnId = col),
            ),
        )
        assertEquals(3, board.wipCount(col))
    }

    @Test
    fun `moveCard changes columnId`() {
        val c1 = KanbanColumnId("c1"); val c2 = KanbanColumnId("c2")
        val cardId = KanbanCardId("x")
        val board = KanbanBoard(
            id = KanbanBoardId.generate(), name = "T",
            columns = listOf(KanbanColumn(c1, "A", 0), KanbanColumn(c2, "B", 1)),
            cards = listOf(KanbanCard(cardId, "X", columnId = c1)),
        )
        val moved = board.moveCard(cardId, c2)
        assertEquals(c2, moved.cards.first { it.id == cardId }.columnId)
    }
}
