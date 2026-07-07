package borg.trikeshed.kanban

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import borg.trikeshed.runBlocking

/**
 * Tests for VAL-FSM-001 / GAP-04: ForgeBoardFSM CardCreated lost-update race
 * 100 concurrent CardCreated events on the same column -> distinct monotonic order values.
 */
class ForgeBoardFsmConcurrencyTest {

    private fun seedBoard(): KanbanBoard = KanbanBoard(
        id = KanbanBoardId("board-test"),
        name = "Test Board",
        columns = listOf(
            KanbanColumn(KanbanColumnId("col-target"), "Target", 0),
            KanbanColumn(KanbanColumnId("col-other"), "Other", 1),
        ),
        cards = emptyList(),
    )

    @Test
    fun cardCreatedConcurrentOrdersAreDistinctAndMonotonic() = runBlocking {
        ForgeBoardFSM.reset()
        val board = seedBoard()
        ForgeBoardFSM.emit(ForgeBoardEvent.BoardLoaded(board, 0L))

        val perCoroutine = 100
        val coroutines = 8
        val target = KanbanColumnId("col-target")

        val jobs = (1..coroutines).map { wid ->
            async(Dispatchers.Default) {
                (1..perCoroutine).forEach { i ->
                    ForgeBoardFSM.emit(
                        ForgeBoardEvent.CardCreated(
                            boardId = board.id,
                            cardId = KanbanCardId("c-${wid}-${i}"),
                            title = "Card $wid-$i",
                            description = "",
                            columnId = target,
                            priority = CardPriority.MEDIUM,
                            assignee = null,
                            timestampMs = 0L,
                        )
                    )
                }
            }
        }
        jobs.awaitAll()

        val finalBoard = ForgeBoardFSM.current().boards[board.id]!!
        val cards = finalBoard.cardsInColumn(target)
        val orders = cards.map { it.order }

        assertEquals(perCoroutine * coroutines, cards.size, "all cards persisted")
        assertEquals(orders.size, orders.toSet().size, "no duplicate orders")
        assertEquals(
            orders.sorted(),
            (0 until perCoroutine * coroutines).toList(),
            "monotonic from 0",
        )
    }

    @Test
    fun cardCreatedAcross2CoroutinesAreDistinct() = runBlocking {
        ForgeBoardFSM.reset()
        val board = seedBoard()
        ForgeBoardFSM.emit(ForgeBoardEvent.BoardLoaded(board, 0L))

        val target = KanbanColumnId("col-target")
        val jobs = (1..2).map { wid ->
            async(Dispatchers.Default) {
                (1..50).forEach { i ->
                    ForgeBoardFSM.emit(
                        ForgeBoardEvent.CardCreated(
                            boardId = board.id,
                            cardId = KanbanCardId("c2-${wid}-${i}"),
                            title = "Card 2-$wid-$i",
                            description = "",
                            columnId = target,
                            priority = CardPriority.MEDIUM,
                            assignee = null,
                            timestampMs = 0L,
                        )
                    )
                }
            }
        }
        jobs.awaitAll()
        val finalBoard = ForgeBoardFSM.current().boards[board.id]!!
        val orders = finalBoard.cardsInColumn(target).map { it.order }
        assertEquals(100, orders.size)
        assertEquals(orders.size, orders.toSet().size)
    }
}
