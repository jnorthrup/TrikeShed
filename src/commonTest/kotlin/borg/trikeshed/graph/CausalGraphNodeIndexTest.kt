package borg.trikeshed.graph

import borg.trikeshed.cursor.blackboardContext
import borg.trikeshed.cursor.provenance
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals

class CausalGraphNodeIndexTest {

    @Test fun sameCausalConstructionReturnsExistingNodePosition() {
        val context = blackboardContext(
            id = "board-a",
            provenance = provenance(source = "unit", timestamp = 1L)
        )
        val nodes = CausalGraphNodeIndex()

        val first = causalGraphNode(
            nodeId = "n1",
            opId = "reduce",
            opVersion = "v1",
            parentNodeIds = emptyList(),
            inputFingerprint = "cursor:abc",
            blackboard = context,
            causalClock = 10L,
            topoOrdinal = 0,
            outputHash = "out:1"
        )
        val duplicate = first.copy(nodeId = "n1-duplicate", topoOrdinal = 99)

        assertEquals(0, nodes.addOrGet(first))
        assertEquals(0, nodes.addOrGet(duplicate))
        assertEquals(first, nodes[0])
        assertEquals(1, nodes.size)
    }

    @Test fun graphNodesExposeDeterministicOrderRangeAndBlackboardFacets() {
        val boardA = blackboardContext(id = "board-a")
        val boardB = blackboardContext(id = "board-b")
        val nodes = CausalGraphNodeIndex()

        nodes.addOrGet(causalGraphNode("late", "op", "v1", emptyList(), "in:2", boardB, causalClock = 20L, topoOrdinal = 1, outputHash = "out:late"))
        nodes.addOrGet(causalGraphNode("early", "op", "v1", emptyList(), "in:1", boardA, causalClock = 10L, topoOrdinal = 0, outputHash = "out:early"))
        nodes.addOrGet(causalGraphNode("middle", "op", "v1", listOf("early"), "in:3", boardA, causalClock = 15L, topoOrdinal = 2, outputHash = "out:middle"))

        val topo = nodes.byTopoOrdinal()
        assertEquals(listOf("early", "late", "middle"), (0 until topo.size).map { nodes[topo[it]].nodeId })

        val replay = nodes.byCausalClockRange(10L, 15L)
        assertEquals(listOf("early", "middle"), (0 until replay.size).map { nodes[replay[it]].nodeId })

        assertEquals(1, nodes.byNodeId("early"))
        val boardAView: borg.trikeshed.lib.Series<Int> = nodes.byBlackboard("board-a")
        assertEquals(listOf("early", "middle"), (0 until boardAView.size).map { nodes[boardAView[it]].nodeId })
    }

    @Test fun graphNodesProjectToBlackboardCursorRows() {
        val context = blackboardContext(
            id = "board-a",
            provenance = provenance(source = "cursor", timestamp = 7L)
        )
        val nodes = CausalGraphNodeIndex()
        nodes.addOrGet(causalGraphNode("n1", "op", "v1", listOf("root"), "in", context, 1L, 0, "out"))

        val cursor = nodes.asCursor()
        val row = cursor[0]

        assertEquals(10, row.size)
        assertEquals("n1", row[0].a)
        assertEquals("op", row[1].a)
        assertEquals("board-a", row[4].a)
        assertEquals("cursor", row[9].a)
        assertEquals("nodeId", row[0].b().name)
    }
}
