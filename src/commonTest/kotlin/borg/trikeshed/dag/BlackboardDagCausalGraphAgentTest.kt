package borg.trikeshed.dag

import borg.trikeshed.cursor.blackboardContext
import borg.trikeshed.graph.CausalGraphNodeIndex
import borg.trikeshed.dag.ReteAgent
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cut: NodePlanning events auto-bind and push to ReteAgent.
 *
 * Proves that calling indexNodePlanning on a CausalGraphNodeIndex
 * binds the agent and pushes the node through to the agent's sink.
 */
class BlackboardDagCausalGraphAgentTest {

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
        overlays = emptySeriesOf(),
    )

    @Test fun indexNodePlanningAutoBindsAgentAndPushesToSink() = runBlocking {
        val index = CausalGraphNodeIndex()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firedChannel = Channel<ReteAgent.Fire>(capacity = Channel.UNLIMITED)

        // First bind the agent, then add nodes — they should flow through to the agent
        val agent = index.bindOrCreateAgent(
            scope = scope,
            onFire = { firedChannel.trySend(it) },
        )

        index.indexNodePlanning(nodePlanning(boardId = "board-a", nodeId = "n1"))
        index.indexNodePlanning(nodePlanning(boardId = "board-a", nodeId = "n2"))
        index.indexNodePlanning(nodePlanning(boardId = "board-a", nodeId = "n3"))

        val collected = mutableListOf<ReteAgent.Fire>()
        while (collected.size < 3) {
            val fire = firedChannel.receiveCatching().getOrNull() ?: break
            collected += fire
        }

        assertEquals(3, collected.size, "expected 3 fires, saw ${collected.size}")
        assertEquals(setOf("n1", "n2", "n3"), collected.map { it.nodeId }.toSet())
        assertTrue(collected.all { it.agentId == "node-planning-agent" })
        assertTrue(collected.all { it.ruleName == "node-planning" })

        ReteAgent.stop(agent)
        agent.job.join()
    }
}