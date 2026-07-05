package borg.trikeshed.forge

import borg.trikeshed.kanban.CardPriority
import borg.trikeshed.kanban.KanbanBoardId
import borg.trikeshed.kanban.KanbanCardId
import borg.trikeshed.kanban.KanbanColumnId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForgeDocTest {

    @Test fun emptyDocumentHasPageAndFirstChild() {
        val doc = ForgeDoc.empty("My Page")
        assertEquals("My Page", doc.requireBlock(doc.rootPageId).text)
        assertEquals(ForgeBlockKind.PAGE, doc.requireBlock(doc.rootPageId).kind)
        assertEquals(2, doc.blocks.size, "page + first child")
    }

    @Test fun appendBlockAddsChildToParent() {
        val doc = ForgeDoc.empty("Doc")
        val child = ForgeDoc.appendBlock(doc, doc.rootPageId, ForgeBlockKind.HEADING_1, "Section 1")
        val parent = child.requireBlock(doc.rootPageId)
        assertEquals(2, parent.children.size, "first child + appended")
        val appended = child.requireBlock(parent.children.last())
        assertEquals("Section 1", appended.text)
        assertEquals(ForgeBlockKind.HEADING_1, appended.kind)
    }

    @Test fun insertBlockAfterPutsBlockInCorrectPosition() {
        var doc = ForgeDoc.empty("Doc")
        doc = ForgeDoc.appendBlock(doc, doc.rootPageId, ForgeBlockKind.TEXT, "A")
        val aId = doc.cursor.blockId
        doc = ForgeDoc.appendBlock(doc, doc.rootPageId, ForgeBlockKind.TEXT, "B")
        doc = ForgeDoc.insertBlockAfter(doc, aId, ForgeBlockKind.TEXT, "A.5")

        val page = doc.requireBlock(doc.rootPageId)
        // Page starts with an empty first child, then A, then A.5, then B
        assertEquals(listOf("", "A", "A.5", "B"), page.children.map { doc.block(it)!!.text })
    }

    @Test fun updateTextChangesBlockContent() {
        val doc = ForgeDoc.empty("Doc")
        val childId = doc.requireBlock(doc.rootPageId).children.first()
        val updated = ForgeDoc.updateText(doc, childId, "hello edited")
        assertEquals("hello edited", updated.requireBlock(childId).text)
    }

    @Test fun deleteBlockRemovesBlock() {
        var doc = ForgeDoc.empty("Doc")
        doc = ForgeDoc.appendBlock(doc, doc.rootPageId, ForgeBlockKind.TEXT, "delete me")
        val targetId = doc.cursor.blockId
        val beforeSize = doc.blocks.size
        doc = ForgeDoc.deleteBlock(doc, targetId)
        assertNull(doc.block(targetId))
        assertEquals(beforeSize - 1, doc.blocks.size)
    }

    @Test fun deleteBlockOnRootPageIsNoOp() {
        val doc = ForgeDoc.empty("Doc")
        val result = ForgeDoc.deleteBlock(doc, doc.rootPageId)
        assertEquals(doc.blocks.size, result.blocks.size)
    }

    @Test fun setPropertyAddsKey() {
        val doc = ForgeDoc.empty("Doc")
        val childId = doc.requireBlock(doc.rootPageId).children.first()
        val updated = ForgeDoc.setProperty(doc, childId, "checked", "true")
        assertEquals("true", updated.requireBlock(childId).properties["checked"])
    }

    @Test fun renderMarkdownProducesHeading() {
        var doc = ForgeDoc.empty("My Page")
        doc = ForgeDoc.appendBlock(doc, doc.rootPageId, ForgeBlockKind.HEADING_2, "Section A")
        doc = ForgeDoc.appendBlock(doc, doc.rootPageId, ForgeBlockKind.TEXT, "Some body text.")
        val md = ForgeDoc.renderMarkdown(doc)
        assertTrue(md.contains("# My Page"))
        assertTrue(md.contains("## Section A"))
        assertTrue(md.contains("Some body text."))
    }

    @Test fun renderMarkdownHandlesTodo() {
        var doc = ForgeDoc.empty("Doc")
        doc = ForgeDoc.appendBlock(doc, doc.rootPageId, ForgeBlockKind.TODO, "Task", mapOf("checked" to "true"))
        val md = ForgeDoc.renderMarkdown(doc)
        assertTrue(md.contains("- [x] Task"))
    }

    @Test fun forgeDocumentProjectsToKanbanBoard() {
        var doc = ForgeDoc.empty("Sprint Board")
        doc = ForgeDoc.appendBlock(doc, doc.rootPageId, ForgeBlockKind.HEADING_2, "Auth Story",
            mapOf("kanban.status" to "backlog", "kanban.priority" to "high"))
        doc = ForgeDoc.appendBlock(doc, doc.rootPageId, ForgeBlockKind.HEADING_2, "Search Story",
            mapOf("kanban.status" to "in-progress", "kanban.priority" to "critical"))
        doc = ForgeDoc.appendBlock(doc, doc.rootPageId, ForgeBlockKind.HEADING_2, "Old Bug",
            mapOf("kanban.status" to "done"))

        val board = doc.toKanbanBoard()
        assertEquals("Sprint Board", board.name)
        assertEquals(3, board.cards.size)

        val authCard = board.cards.first { it.title == "Auth Story" }
        assertEquals(KanbanColumnId("col-backlog"), authCard.columnId)
        assertEquals(CardPriority.HIGH, authCard.priority)

        val searchCard = board.cards.first { it.title == "Search Story" }
        assertEquals(KanbanColumnId("col-inprogress"), searchCard.columnId)
        assertEquals(CardPriority.CRITICAL, searchCard.priority)

        val doneCard = board.cards.first { it.title == "Old Bug" }
        assertEquals(KanbanColumnId("col-done"), doneCard.columnId)
    }

    @Test fun kanbanBoardProjectsToForgeDocument() {
        val board = borg.trikeshed.kanban.KanbanBoard(
            id = KanbanBoardId("board-1"),
            name = "Test Board",
            columns = listOf(
                borg.trikeshed.kanban.KanbanColumn(KanbanColumnId("col-backlog"), "Backlog", 0),
                borg.trikeshed.kanban.KanbanColumn(KanbanColumnId("col-done"), "Done", 1),
            ),
            cards = listOf(
                borg.trikeshed.kanban.KanbanCard(
                    id = KanbanCardId("c1"),
                    title = "Task A",
                    columnId = KanbanColumnId("col-backlog"),
                    priority = CardPriority.HIGH,
                    order = 0,
                ),
                borg.trikeshed.kanban.KanbanCard(
                    id = KanbanCardId("c2"),
                    title = "Task B",
                    columnId = KanbanColumnId("col-done"),
                    priority = CardPriority.LOW,
                    order = 1,
                ),
            ),
        )

        val doc = board.toForgeDocument()
        assertEquals("Test Board", doc.requireBlock(doc.rootPageId).text)

        val md = ForgeDoc.renderMarkdown(doc)
        assertTrue(md.contains("Task A"))
        assertTrue(md.contains("Task B"))

        // Round-trip back to kanban
        val roundTripped = doc.toKanbanBoard()
        assertEquals(2, roundTripped.cards.size)
        assertEquals("Task A", roundTripped.cards.first().title)
    }

    @Test fun roundTripForgeKanbanPreservesCards() {
        var doc = ForgeDoc.empty("Round Trip")
        doc = ForgeDoc.appendBlock(doc, doc.rootPageId, ForgeBlockKind.HEADING_2, "Epic 1",
            mapOf("kanban.status" to "backlog", "kanban.priority" to "critical", "kanban.assignee" to "alice"))

        val board = doc.toKanbanBoard()
        assertEquals(1, board.cards.size)
        val card = board.cards.first()
        assertEquals("Epic 1", card.title)
        assertEquals("alice", card.assignee)
        assertEquals(CardPriority.CRITICAL, card.priority)

        val doc2 = board.toForgeDocument()
        val board2 = doc2.toKanbanBoard()
        assertEquals(1, board2.cards.size)
        assertEquals("Epic 1", board2.cards.first().title)
    }
}
