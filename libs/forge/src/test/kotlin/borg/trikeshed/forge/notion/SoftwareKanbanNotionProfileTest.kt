package borg.trikeshed.forge.notion

import borg.trikeshed.userspace.reactor.KanbanFSM
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Cut: Notion-specialization for software kanban.
 *
 * Proves the smallest software-kanban-shaped Notion surface end-to-end:
 *   SoftwareKanbanNotionProfile.specialize (THIS CUT)
 *     -> CursorDrivenNotion blocks with epic / story + flat property keys
 *       -> NotionKanbanBridge.projectTaxonomyEvents (cut #2, HARD)
 *         -> KanbanEvent.TaxonomyNodeCreated (cut #1, contract)
 *           -> KanbanFSM.reduce -> KanbanState (cut #1, HARD)
 *
 * No UI, no server, no recording.
 */
class SoftwareKanbanNotionProfileTest {

    @BeforeEach
    fun resetFsm() {
        KanbanFSM.reset()
    }

    @Test
    fun `specialize emits one epic heading and one story bullet per leaf`() {
        val epic = CausalNodeForProfile(
            title = "Auth epic",
            summary = "Login + logout + password reset",
            status = "in-progress",
            owner = "alice",
            estimate = "5d",
            due = "2026-07-12",
        )
        val stories = listOf(
            CausalNodeForProfile(title = "Login", status = "todo", owner = "alice", estimate = "1d", parent = "Auth epic"),
            CausalNodeForProfile(title = "Logout", status = "todo", owner = "alice", estimate = "1d", parent = "Auth epic"),
        )

        val state = SoftwareKanbanNotionProfile.specialize(epic, stories)

        // Root page carries the epic title.
        assertEquals("Auth epic", state.block(state.rootPageId)?.text)

        // 1 HEADING_1 epic + 1 QUOTE summary + 2 BULLET stories = 4 taxonomy-creating ops.
        val taxonomyOps = state.history.filter {
            it.operation == "append-block"
        }
        assertEquals(4, taxonomyOps.size, "1 epic heading + 1 summary quote + 2 story bullets")

        // Epic heading carries the full software-kanban property map.
        // Skip the empty firstParagraph placeholder created by empty() and find the HEADING_1.
        val epicBlock = state.requireBlock(state.rootPageId).children
            .map(state::requireBlock)
            .first { it.kind == NotionBlockKind.HEADING_1 }
        assertEquals("Auth epic", epicBlock.text)
        assertEquals("in-progress", epicBlock.properties["kanban.status"])
        assertEquals("alice", epicBlock.properties["kanban.owner"])
        assertEquals("5d", epicBlock.properties["kanban.estimate"])
        assertEquals("2026-07-12", epicBlock.properties["kanban.due"])

        // Story bullets carry their own property maps with parent pinned to the epic.
        val storyBlocks = state.requireBlock(state.rootPageId).children
            .map(state::requireBlock)
            .filter { it.kind == NotionBlockKind.BULLET }
        assertEquals(2, storyBlocks.size)
        assertTrue(storyBlocks.all { it.kind == NotionBlockKind.BULLET })
        assertTrue(storyBlocks.all { it.properties["kanban.parent"] == "Auth epic" })
        assertEquals("alice", storyBlocks[0].properties["kanban.owner"])
        assertEquals("1d", storyBlocks[0].properties["kanban.estimate"])
    }

    @Test
    fun `property keys are the flat software kanban vocabulary`() {
        val expectedKeys = listOf(
            "kanban.status",
            "kanban.owner",
            "kanban.estimate",
            "kanban.due",
            "kanban.blocker",
            "kanban.parent",
        )
        assertEquals(expectedKeys, SoftwareKanbanNotionProfile.PROPERTY_KEYS)
    }

    @Test
    fun `specialize rolls through the existing endgame spine into KanbanState`() {
        val epic = CausalNodeForProfile(
            title = "Search epic",
            summary = "Full-text + faceted",
            status = "in-progress",
            owner = "bob",
            estimate = "8d",
        )
        val stories = listOf(
            CausalNodeForProfile(title = "Indexer", status = "todo", owner = "bob", estimate = "3d", parent = "Search epic"),
            CausalNodeForProfile(title = "Query API", status = "todo", owner = "bob", estimate = "2d", parent = "Search epic"),
        )

        val state = SoftwareKanbanNotionProfile.specialize(epic, stories)
        val reduced = SoftwareKanbanNotionProfile.toKanbanState(state)

        // 1 epic heading + 1 summary quote + 2 story bullets = 4 taxonomy nodes.
        assertEquals(4, reduced.taxonomyNodeCount)
        assertEquals("TaxonomyNodeCreated", reduced.lastEventKind)
        assertTrue(reduced.recentTaxonomyNodes.contains("Search epic"))
        assertTrue(reduced.recentTaxonomyNodes.contains("Indexer"))
    }
}