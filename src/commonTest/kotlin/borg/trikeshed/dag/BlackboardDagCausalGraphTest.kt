package borg.trikeshed.dag

import borg.trikeshed.graph.CausalGraphNodeIndex
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals

class BlackboardDagCausalGraphTest {

    @Test fun nodePlanningEventIndexesDeterministicCausalGraphNode() {
        val index = CausalGraphNodeIndex()
        val coordinate = DagCoordinate(
            className = "ForgeBoard",
            methodName = "planNode",
            bytecodeOffset = 42,
            timestamp = 1234L,
            threadId = 7L
        )
        val event = BlackboardEvent.NodePlanning(
            coordinate = coordinate,
            boardId = "board-a",
            nodeId = "node-1",
            overlays = emptySeriesOf()
        )

        val first = index.indexNodePlanning(event)
        val duplicate = index.indexNodePlanning(event)

        assertEquals(first, duplicate)
        assertEquals(1, index.size)
        assertEquals(first, index.byNodeId("node-1"))

        val node = index[first]
        assertEquals("node-1", node.nodeId)
        assertEquals("node-planning", node.opId)
        assertEquals("board-a", node.blackboard.id)
        assertEquals(1234L, node.causalClock)
        assertEquals(42, node.topoOrdinal)

        val cursor = index.asCursor()
        assertEquals(1, cursor.size)
        assertEquals("node-1", cursor[0][0].a)
        assertEquals("board-a", cursor[0][4].a)
    }
}
