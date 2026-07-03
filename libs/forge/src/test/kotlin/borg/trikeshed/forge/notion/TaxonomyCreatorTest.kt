package borg.trikeshed.forge.notion

import borg.trikeshed.userspace.reactor.KanbanEvent
import borg.trikeshed.userspace.reactor.KanbanFSM
import borg.trikeshed.userspace.reactor.KanbanState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach

/**
 * Cut #3 test: deterministic stub taxonomy creator.
 *
 * Proves the FULL endgame spine end-to-end:
 *   TaxonomyCreator.createFromTopic("Endpoints")
 *     → CursorDrivenNotion blocks (HARD)
 *       → NotionKanbanBridge.projectTaxonomyEvents (cut #2)
 *         → KanbanEvent.TaxonomyNodeCreated events (cut #1)
 *           → KanbanFSM.reduce → KanbanState.taxonomyNodeCount (cut #1)
 *
 * No LLM, no blackboard fabric, no ACL — just the pure spine.
 */
class TaxonomyCreatorTest {

    @BeforeEach
    fun resetFsm() {
        KanbanFSM.reset()
    }

    @Test
    fun `createFromTopic produces a taxonomy page with heading and facets`() {
        val state = TaxonomyCreator.createFromTopic("Endpoints")

        // The root page should exist with the topic as title.
        assertEquals("Endpoints", state.block(state.rootPageId)?.text)

        // The page should contain 1 HEADING_1 (topic) + 4 HEADING_2 (facets)
        // + 4 TEXT (descriptions) = 9 taxonomy-creating blocks.
        val taxonomyOps = state.history.filter {
            it.operation == "append-block"
        }
        assertEquals(9, taxonomyOps.size, "topic + 4 facets + 4 descriptions")
    }

    @Test
    fun `decompose produces deterministic facets for any topic`() {
        val facets = TaxonomyCreator.decompose("Quantum Computing")
        assertEquals(4, facets.size)
        assertTrue(facets.any { it.contains("Definition") })
        assertTrue(facets.any { it.contains("Properties") })
        assertTrue(facets.any { it.contains("Relationships") })
        assertTrue(facets.any { it.contains("Examples") })
    }

    @Test
    fun `createFromTopic output feeds through the full spine to KanbanState`() {
        val state = TaxonomyCreator.createFromTopic("REST APIs")

        // The complete endgame slice: creator → Notion → bridge → events → FSM.
        val events: List<KanbanEvent> = NotionKanbanBridge.projectTaxonomyEvents(state)
        assertTrue(events.isNotEmpty(), "creator output must produce taxonomy events")
        // 9 append-block ops → 9 TaxonomyNodeCreated events.
        assertEquals(9, events.size)

        val reduced = events.fold(KanbanState()) { acc, e -> KanbanFSM.reduce(e, acc) }
        assertEquals(9, reduced.taxonomyNodeCount)
        assertEquals("TaxonomyNodeCreated", reduced.lastEventKind)

        // The most recent taxonomy nodes should include the topic heading
        // and the last facet's description.
        assertTrue(reduced.recentTaxonomyNodes.isNotEmpty())
    }

    @Test
    fun `multiple topics accumulate independently in KanbanState`() {
        val state1 = TaxonomyCreator.createFromTopic("Topic A")
        val state2 = TaxonomyCreator.createFromTopic("Topic B")

        val events1 = NotionKanbanBridge.projectTaxonomyEvents(state1)
        val events2 = NotionKanbanBridge.projectTaxonomyEvents(state2)

        KanbanFSM.reset()
        val allEvents = events1 + events2
        val reduced = allEvents.fold(KanbanState()) { acc, e -> KanbanFSM.reduce(e, acc) }

        assertEquals(18, reduced.taxonomyNodeCount, "two topics × 9 nodes each")
    }
}
