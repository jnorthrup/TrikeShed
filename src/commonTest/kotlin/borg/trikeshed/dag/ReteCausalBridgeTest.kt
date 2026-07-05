package borg.trikeshed.dag

import borg.trikeshed.cursor.blackboardContext
import borg.trikeshed.graph.CausalGraphNodeIndex
import borg.trikeshed.lib.j
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Cut: unify causal graph nodes and rete facts.
 *
 * Proves that a single [BlackboardEvent.NodePlanning] produces both:
 *   - a deterministic position in [CausalGraphNodeIndex]
 *   - one [ReteFact.NodeFact] mirroring that node by `node:<boardId>:<nodeId>`
 *
 * The same `nodeId`, `boardId`, and `causalKey` must appear on both sides,
 * so any downstream consumer can join the two surfaces by either identity.
 */
class ReteCausalBridgeTest {

    private fun nodePlanning(
        boardId: String = "board-a",
        nodeId: String = "node-1",
        methodName: String = "planNode",
    ): BlackboardEvent.NodePlanning = BlackboardEvent.NodePlanning(
        coordinate = DagCoordinate(
            className = "ForgeBoard",
            methodName = methodName,
            bytecodeOffset = 42,
            timestamp = 1234L,
            threadId = 7L,
        ),
        boardId = boardId,
        nodeId = nodeId,
        overlays = emptySeries(),
    )

    private fun emptySeries(): borg.trikeshed.lib.Series<Any> =
        0 j { _: Int -> error("empty series") }

    @Test fun nodePlanningProducesMatchingCausalNodeAndReteFact() {
        val index = CausalGraphNodeIndex()
        val event = nodePlanning(boardId = "board-a", nodeId = "node-1")

        val projection = ReteCausalBridge.project(event, index)

        // Causal side
        assertEquals(0, projection.position)
        assertEquals(1, index.size)
        val causal = index[projection.position]
        assertEquals("node-1", causal.nodeId)
        assertEquals("board-a", causal.blackboard.id)

        // Rete side
        assertEquals(1, projection.facts.size)
        val fact = projection.facts[0]
        assertTrue(fact is ReteFact.NodeFact, "expected NodeFact, got ${fact::class.simpleName}")
        assertEquals("node:board-a:node-1", fact.factId)
        assertEquals("node-1", fact.nodeId)
        assertEquals("board-a", fact.boardId)
        assertEquals(causal.causalKey, fact.causalKey)
        assertEquals(causal.opId, fact.opId)
        assertEquals(causal.opVersion, fact.opVersion)
    }

    @Test fun duplicateEventsCollapseToSameCausalPositionAndSameReteFact() {
        val index = CausalGraphNodeIndex()
        val event = nodePlanning(boardId = "board-a", nodeId = "node-1")

        val first = ReteCausalBridge.project(event, index)
        val second = ReteCausalBridge.project(event, index)

        // The causal side de-dupes on causalKey.
        assertEquals(first.position, second.position)
        assertEquals(1, index.size)

        // The rete side mirrors that collapse.
        assertEquals(1, second.facts.size)
        assertEquals("node:board-a:node-1", second.facts[0].factId)
        assertEquals(first.facts[0].factId, second.facts[0].factId)
    }

    @Test fun differentBoardsProduceDifferentReteFactIds() {
        val index = CausalGraphNodeIndex()
        val boardA = ReteCausalBridge.project(nodePlanning(boardId = "board-a", nodeId = "n1"), index)
        val boardB = ReteCausalBridge.project(nodePlanning(boardId = "board-b", nodeId = "n1"), index)

        assertEquals(2, index.size)
        assertNotNull(boardA.facts[0] as ReteFact.NodeFact)
        assertNotNull(boardB.facts[0] as ReteFact.NodeFact)
        assertEquals("node:board-a:n1", boardA.facts[0].factId)
        assertEquals("node:board-b:n1", boardB.facts[0].factId)
        val keyA = (boardA.facts[0] as ReteFact.NodeFact).causalKey
        val keyB = (boardB.facts[0] as ReteFact.NodeFact).causalKey
        assertTrue(keyA != keyB, "causalKey must differ across boards")
    }
}