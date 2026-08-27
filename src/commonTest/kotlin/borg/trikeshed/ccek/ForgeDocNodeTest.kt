package borg.trikeshed.ccek

import borg.trikeshed.forge.ForgeBlockKind
import borg.trikeshed.forge.ForgeDoc
import borg.trikeshed.kanban.KanbanColumnId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ForgeDocNodeTest {

    @Test fun appendBlockFansOutAllProjections() = runTest {
        val node = ForgeDocNode(ForgeDoc.empty("Test Page"), scope = backgroundScope)
        node.start()

        // Drain initial emissions on the test scheduler
        advanceUntilIdle()

        node.signalIn.send(ForgeSignal.AppendBlock(
            ForgeBlockKind.HEADING_2, "Task A",
            mapOf("kanban.status" to "backlog", "kanban.priority" to "high")
        ))
        advanceUntilIdle()

        // Collect board projection — it must contain the new card
        val boardProj = withTimeout(3000) {
            node.projections.first { proj ->
                proj is ForgeProjection.BoardChanged &&
                proj.board.cards.any { it.title == "Task A" }
            } as ForgeProjection.BoardChanged
        }

        assertEquals("Test Page", boardProj.board.name)
        assertEquals(1, boardProj.board.cards.size)
        assertEquals("Task A", boardProj.board.cards.first().title)

        // Markdown must also contain the new text
        val mdProj = withTimeout(3000) {
            node.projections.first { proj ->
                proj is ForgeProjection.MarkdownChanged &&
                proj.markdown.contains("Task A")
            } as ForgeProjection.MarkdownChanged
        }
        assertTrue(mdProj.markdown.contains("Task A"))

        node.stop()
    }

    @Test fun moveCardUpdatesBoardProjection() = runTest {
        val node = ForgeDocNode(ForgeDoc.empty("Board"), scope = backgroundScope)
        node.start()
        advanceUntilIdle()

        // Add a card in backlog
        node.signalIn.send(ForgeSignal.AppendBlock(
            ForgeBlockKind.HEADING_2, "Move Me",
            mapOf("kanban.status" to "backlog")
        ))
        advanceUntilIdle()

        // Wait for board to contain the card
        val initialBoard = withTimeout(3000) {
            node.projections.first { proj ->
                proj is ForgeProjection.BoardChanged &&
                proj.board.cards.any { it.title == "Move Me" }
            } as ForgeProjection.BoardChanged
        }

        assertEquals(KanbanColumnId("col-backlog"), initialBoard.board.cards.first().columnId)

        // Move it to done
        val cardId = initialBoard.board.cards.first().id.value
        node.signalIn.send(ForgeSignal.MoveCard(cardId, "col-done"))
        advanceUntilIdle()

        // Wait for the updated board showing the card in done
        val updatedBoard = withTimeout(3000) {
            node.projections.first { proj ->
                proj is ForgeProjection.BoardChanged &&
                proj.board.cards.any { it.columnId == KanbanColumnId("col-done") }
            } as ForgeProjection.BoardChanged
        }

        assertEquals(KanbanColumnId("col-done"), updatedBoard.board.cards.first().columnId)

        node.stop()
    }

    @Test fun markdownProjectionReflectsEdits() = runTest {
        val node = ForgeDocNode(ForgeDoc.empty("Doc"), scope = backgroundScope)
        node.start()
        advanceUntilIdle()

        node.signalIn.send(ForgeSignal.AppendBlock(
            ForgeBlockKind.TEXT, "hello world"
        ))
        advanceUntilIdle()

        val mdProjection = withTimeout(3000) {
            node.projections.first { proj ->
                proj is ForgeProjection.MarkdownChanged &&
                proj.markdown.contains("hello world")
            } as ForgeProjection.MarkdownChanged
        }

        assertTrue(mdProjection.markdown.contains("hello world"))

        node.stop()
    }
}
