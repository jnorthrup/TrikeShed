package borg.trikeshed.graph

import borg.trikeshed.cursor.blackboardContext
import borg.trikeshed.kanban.KanbanCardId
import borg.trikeshed.kanban.toMermaidCausal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CausalGraphKanbanTest {

    @Test fun causalGraphNodesProjectToVisualKanbanBeforeAgenticWork() {
        val index = CausalGraphNodeIndex()
        val boardA = blackboardContext(id = "board-a")
        val boardB = blackboardContext(id = "board-b")

        index.addOrGet(causalGraphNode("root", "plan", "v1", emptyList(), "seed", boardA, 1L, 0, "out-root"))
        index.addOrGet(causalGraphNode("child", "agent-work", "v1", listOf("root"), "seed+root", boardA, 2L, 1, "out-child"))
        index.addOrGet(causalGraphNode("other", "plan", "v1", emptyList(), "seed", boardB, 3L, 0, "out-other"))

        val kanban = index.toKanbanBoard("board-a", name = "Agent causal preflight")

        assertEquals("Agent causal preflight", kanban.name)
        assertEquals(listOf("Causal Ready", "Causal Blocked", "Agentic Work"), kanban.columns.map { it.name })
        assertEquals(listOf("root", "child"), kanban.cards.map { it.id.value })
        assertEquals("col-causal-ready", kanban.cards.first { it.id.value == "root" }.columnId.value)
        assertEquals("col-causal-blocked", kanban.cards.first { it.id.value == "child" }.columnId.value)
        assertEquals(listOf(KanbanCardId("root")), kanban.cards.first { it.id.value == "child" }.dependencies)
        assertEquals("board-a", kanban.cards.first().metadata["blackboardId"])

        val mermaid = kanban.toMermaidCausal()
        assertTrue(mermaid.contains("flowchart LR"), mermaid)
        assertTrue(mermaid.contains("subgraph col_causal_ready[\"Causal Ready\"]"), mermaid)
        assertTrue(mermaid.contains("card_root[\"root\"]"), mermaid)
        assertTrue(mermaid.contains("card_root --> card_child"), mermaid)
        assertTrue(!mermaid.contains("other"), mermaid)
    }
}
