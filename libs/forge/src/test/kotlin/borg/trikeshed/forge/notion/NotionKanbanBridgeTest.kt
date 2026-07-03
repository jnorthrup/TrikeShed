package borg.trikeshed.forge.notion

import borg.trikeshed.userspace.reactor.KanbanEvent
import borg.trikeshed.userspace.reactor.KanbanFSM
import borg.trikeshed.userspace.reactor.KanbanState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach

/**
 * Cut #2 test: Notion → Kanban emission bridge.
 *
 * Proves the endgame chain is real:
 *   CursorDrivenNotion.appendBlock  (HARD — the editor creates a block)
 *     → NotionKanbanBridge.projectTaxonomyEvents  (THIS CUT)
 *       → KanbanEvent.TaxonomyNodeCreated  (cut #1 contract)
 *         → KanbanFSM.reduce → KanbanState.taxonomyNodeCount  (cut #1)
 *
 * This is the first HARD link between the Notion-adjacent editor and the
 * kanban board state. No blackboard fabric required, no AI agent required.
 */
class NotionKanbanBridgeTest {

    @BeforeEach
    fun resetFsm() {
        KanbanFSM.reset()
    }

    @Test
    fun `appendBlock projects to TaxonomyNodeCreated event`() {
        val initial = CursorDrivenNotion.empty(title = "Taxonomy Doc")
        val state = CursorDrivenNotion.appendBlock(
            state = initial,
            parentId = initial.rootPageId,
            kind = NotionBlockKind.HEADING_1,
            text = "Phase 1",
        )
        val events = NotionKanbanBridge.projectTaxonomyEvents(state)
        assertEquals(1, events.size)
        val event = events.first()
        assertEquals("Phase 1", event.label)
        assertEquals("heading_1", event.kind)
        assertEquals(initial.rootPageId.value, event.parentId)
    }

    @Test
    fun `multiple appendBlocks project to multiple events in order`() {
        var state = CursorDrivenNotion.empty(title = "Endpoints")
        state = CursorDrivenNotion.appendBlock(
            state = state,
            parentId = state.rootPageId,
            kind = NotionBlockKind.DATABASE,
            text = "REST Endpoints",
        )
        state = CursorDrivenNotion.appendBlock(
            state = state,
            parentId = state.rootPageId,
            kind = NotionBlockKind.HEADING_2,
            text = "GET /health",
        )
        val events = NotionKanbanBridge.projectTaxonomyEvents(state)
        assertEquals(2, events.size)
        assertEquals("REST Endpoints", events[0].label)
        assertEquals("GET /health", events[1].label)
        assertTrue(events[0].timestampMs <= events[1].timestampMs)
    }

    @Test
    fun `non-creating mutations are not projected`() {
        var state = CursorDrivenNotion.empty(title = "Doc")
        state = CursorDrivenNotion.appendBlock(
            state = state,
            parentId = state.rootPageId,
            kind = NotionBlockKind.TEXT,
            text = "hello",
        )
        // updateFocusedText is a mutation but NOT a taxonomy-creating op.
        state = CursorDrivenNotion.updateFocusedText(state = state, text = "hello edited")
        // cursor move is a mutation but not taxonomy-creating.
        state = CursorDrivenNotion.moveCursor(state = state, direction = NotionCursorMove.PARENT)
        val events = NotionKanbanBridge.projectTaxonomyEvents(state)
        assertEquals(1, events.size, "only the appendBlock should project, not edits/moves")
    }

    @Test
    fun `projected events roll into KanbanState via FSM reduce`() {
        var state = CursorDrivenNotion.empty(title = "AI Taxonomy")
        state = CursorDrivenNotion.appendBlock(
            state = state,
            parentId = state.rootPageId,
            kind = NotionBlockKind.HEADING_1,
            text = "Category A",
        )
        state = CursorDrivenNotion.appendBlock(
            state = state,
            parentId = state.rootPageId,
            kind = NotionBlockKind.HEADING_2,
            text = "Subcategory A.1",
        )

        // The full endgame slice: Notion → bridge → KanbanEvent → FSM → KanbanState.
        val events: List<KanbanEvent> = NotionKanbanBridge.projectTaxonomyEvents(state)
        val reduced = events.fold(KanbanState()) { acc, e -> KanbanFSM.reduce(e, acc) }

        assertEquals(2, reduced.taxonomyNodeCount)
        assertEquals(listOf("Category A", "Subcategory A.1"), reduced.recentTaxonomyNodes)
        assertEquals("TaxonomyNodeCreated", reduced.lastEventKind)
    }
}
