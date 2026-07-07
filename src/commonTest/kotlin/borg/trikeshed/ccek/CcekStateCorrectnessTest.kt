package borg.trikeshed.ccek

import borg.trikeshed.forge.ForgeBlockKind
import borg.trikeshed.forge.ForgeDoc
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
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import borg.trikeshed.runBlocking

/**
 * Tests for VAL-CCEK-002 / GAP-08 (MoveCard idempotent) and VAL-CCEK-003 /
 * GAP-20 (agents map CME-safe under concurrent subscribeAgent).
 */
class CcekStateCorrectnessTest {

    private fun seedBoard(): KanbanBoard = KanbanBoard(
        id = KanbanBoardId("board-move"),
        name = "Move Card Test",
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
            KanbanCard(
                id = KanbanCardId("c2"),
                title = "Card 2",
                description = "",
                columnId = KanbanColumnId("col-a"),
                order = 1,
                priority = CardPriority.MEDIUM,
            ),
        ),
    )

    private fun newNode(doc: borg.trikeshed.forge.ForgeDocument): Pair<ArticulatedNode, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val node = ArticulatedNode(
            initialDoc = doc,
            scope = scope,
            record = true,
            enabledProjections = setOf(ProjectionKind.BOARD, ProjectionKind.DOCUMENT),
        )
        return node to scope
    }

    @Test
    fun moveCardIdempotentAcrossManyApplications() = runBlocking {
        val (node, scope) = newNode(seedBoard().toForgeDocument())

        val baselineRootId = node.applySignalForTest(
            ForgeSignal.MoveCard(cardId = "c1", toColumnId = "col-b"),
        ).rootPageId

        val finalDoc = (1..99).fold(node.applySignalForTest(
            ForgeSignal.MoveCard(cardId = "c1", toColumnId = "col-b"),
        )) { acc, _ ->
            node.applySignalForTest(ForgeSignal.MoveCard(cardId = "c1", toColumnId = "col-b"))
        }
        // applySignalForTest returns a doc derived from internal `doc`, which
        // is itself updated by the synchronous applySignal so the 100th call
        // matches the 1st call structurally.
        assertEquals(baselineRootId, finalDoc.rootPageId, "rootPageId must not change")
        val finalBoard = finalDoc.toKanbanBoard()
        val movedCard = finalBoard.cards.firstOrNull { it.id.value == "c1" }
            ?: fail("card c1 missing after 100 MoveCard applications")
        assertEquals(KanbanColumnId("col-b"), movedCard.columnId, "c1 must end in col-b")

        scope.cancel()
    }

    @Test
    fun subscribeAgentMidFanoutIsSafe() = runBlocking {
        val (node, scope) = newNode(seedBoard().toForgeDocument())

        val preExisting = (0 until 50).map { i ->
            "pre-$i" to { _: ForgeSignal -> /* no-op */ }
        }
        preExisting.forEach { (name, handler) -> node.subscribeAgent(name, handler) }

        val fireJob: Deferred<Unit> = async(Dispatchers.Default) {
            node.sendSignal(
                ForgeSignal.AppendBlock(
                    kind = ForgeBlockKind.TEXT,
                    text = "hello",
                )
            )
        }

        val subscribedMid = (0 until 50).map { i ->
            "post-$i" to { _: ForgeSignal -> /* no-op */ }
        }
        subscribedMid.forEach { (name, handler) -> node.subscribeAgent(name, handler) }

        fireJob.await()
        scope.cancel()
        // If we reach here without CME / IllegalStateException the assertion
        // is "no exception".
        assertTrue(true, "agents map modified during fanout did not throw")
    }
}
