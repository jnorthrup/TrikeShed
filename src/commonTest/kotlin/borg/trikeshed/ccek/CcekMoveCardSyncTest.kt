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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest

/**
 * Test CCEK MoveCard via the synchronous test seam.
 * This avoids SharedFlow timing (replay cache population is async) by
 * going directly through `applySignalForTest`.
 */
class CcekMoveCardSyncTest {

    private fun seedDoc(): borg.trikeshed.forge.ForgeDocument {
        val board = KanbanBoard(
            id = KanbanBoardId("board-sync"),
            name = "Sync",
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
        return board.toForgeDocument()
    }

    @Test
    fun applySignalMoveCardSetsKanbanColumnId() = runTest {
        val doc = seedDoc()
        // Wrap into a working CCEK node so applySignalForTest sees a doc
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val node = ArticulatedNode(
            initialDoc = doc,
            scope = scope,
            record = false,
            enabledProjections = setOf(ProjectionKind.DOCUMENT),
        )

        val newDoc = node.applySignalForTest(
            ForgeSignal.MoveCard(cardId = "c1", toColumnId = "col-b"),
        )
        val c1 = newDoc.blocks["c1"]!!
        assertEquals(
            "col-b", c1.properties["kanban.column.id"],
            "CCEK MoveCard must set kanban.column.id=col-b",
        )

        // Now round-trip: c1 should resolve to col-b via projection.
        val outBoard = newDoc.toKanbanBoard()
        val c1out = outBoard.cards.first { it.id.value == "c1" }
        assertEquals(KanbanColumnId("col-b"), c1out.columnId, "c1 projects to col-b")

        scope.cancel()
    }

    @Test
    fun moveCardIdempotentSynchronous() = runTest {
        val doc = seedDoc()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val node = ArticulatedNode(
            initialDoc = doc,
            scope = scope,
            record = false,
            enabledProjections = setOf(ProjectionKind.DOCUMENT),
        )

        val after1 = node.applySignalForTest(
            ForgeSignal.MoveCard(cardId = "c1", toColumnId = "col-b"),
        )
        var current = after1
        repeat(99) {
            current = node.applySignalForTest(
                ForgeSignal.MoveCard(cardId = "c1", toColumnId = "col-b"),
            )
        }

        assertEquals(
            after1.rootPageId, current.rootPageId, "rootPageId stable",
        )
        assertEquals(after1.blocks["c1"], current.blocks["c1"], "c1 unchanged after 100 calls")
        scope.cancel()
    }
}
