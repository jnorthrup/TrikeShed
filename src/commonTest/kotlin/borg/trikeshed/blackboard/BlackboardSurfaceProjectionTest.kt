package borg.trikeshed.blackboard

import borg.trikeshed.cursor.blackboardContext
import borg.trikeshed.graph.CausalGraphNodeIndex
import borg.trikeshed.graph.causalGraphNode
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lcnc.isam.LcncBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlackboardSurfaceProjectionTest {

    @Test fun lcncEntitiesProjectFirstAndCausalNodesAttachByCausalKey() {
        val index = CausalGraphNodeIndex()
        val boardA = blackboardContext(id = "board-a")
        val boardB = blackboardContext(id = "board-b")

        index.addOrGet(causalGraphNode("root", "plan", "v1", emptyList(), "seed", boardA, 1L, 0, "out:root"))
        index.addOrGet(causalGraphNode("child", "agent-work", "v1", listOf("root"), "seed+root", boardA, 2L, 1, "out:child"))
        index.addOrGet(causalGraphNode("other", "plan", "v1", emptyList(), "seed", boardB, 3L, 0, "out:other"))

        val rootKey = index.byNodeId("root")?.let { index[it].causalKey } ?: error("root causalKey missing")
        val childKey = index.byNodeId("child")?.let { index[it].causalKey } ?: error("child causalKey missing")

        val entities = listOf(
            LcncBlock(id = "e-child", type = "Facet", parentId = null, content = mapOf("lane" to "col-causal-blocked", "facet" to "facet/A", "causalKey" to childKey, "title" to "Child Node Title")),
            LcncBlock(id = "e-other", type = "Taxonomy", parentId = null, content = mapOf("lane" to "col-causal-blocked", "facet" to "facet/B", "title" to "Other Node Title")),
            LcncBlock(id = "e-root", type = "Facet", parentId = null, content = mapOf("lane" to "col-causal-ready", "facet" to "facet/A", "causalKey" to rootKey, "title" to "Root Node Title")),
        )

        val surface = BlackboardSurface.project("board-a", index, entities)

        assertEquals(3, surface.rows.size, "one row per lcnc entity, even when no causal node matches")
        assertEquals(listOf("e-child", "e-other", "e-root"), surface.rows.map { it.cardId })
        assertEquals(listOf("Facet", "Taxonomy", "Facet"), surface.rows.map { it.lcncKind })

        val byCard = surface.rows.associateBy { it.cardId }

        assertEquals("col-causal-blocked", byCard.getValue("e-child").lane)
        assertEquals("pre-agent", byCard.getValue("e-child").phase)
        assertEquals("facet/A", byCard.getValue("e-child").facet)
        assertEquals(childKey, byCard.getValue("e-child").causalKey)
        assertEquals("Facet", byCard.getValue("e-child").lcncKind)

        assertEquals("col-causal-blocked", byCard.getValue("e-other").lane)
        assertEquals("lcnc:e-other", byCard.getValue("e-other").causalKey)

        assertEquals("col-causal-ready", byCard.getValue("e-root").lane)
        assertEquals(rootKey, byCard.getValue("e-root").causalKey)

        val cursor = surface.asCursor()
        assertEquals(3, cursor.size)
        assertEquals("e-child", cursor[0][0].a)
        assertEquals("card_id", cursor[0][0].b().name)

        val board = surface.board
        assertEquals(listOf("Causal Ready", "Causal Blocked", "Agentic Work"), board.columns.map { it.name })
        val cardsById = board.cards.associateBy { it.id.value }
        assertTrue(cardsById.containsKey("e-child"))
        assertTrue(cardsById.containsKey("e-root"))
        assertTrue(cardsById.containsKey("e-other"))
        assertEquals("Facet", cardsById.getValue("e-root").tags.first { it != "lcnc-entity" })

        // Ensure card titles are projected correctly, not falling back to cardId
        assertEquals("Child Node Title", cardsById.getValue("e-child").title)
        assertEquals("Root Node Title", cardsById.getValue("e-root").title)
        assertEquals("Other Node Title", cardsById.getValue("e-other").title)
    }
}
