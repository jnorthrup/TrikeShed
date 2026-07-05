package borg.trikeshed.dag

import borg.trikeshed.cursor.blackboardContext
import borg.trikeshed.graph.CausalGraphNode
import borg.trikeshed.graph.CausalGraphNodeIndex
import borg.trikeshed.graph.causalGraphNode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cut: CausalGraphNodeIndex binds to ReteAgent and forwards new nodes.
 *
 * Proves the end-to-end wiring:
 *   CausalGraphNodeIndex.addOrGet(node) -> boundAgent.sink.trySend(node)
 *     -> agent consumes -> rule fires -> Fire emitted
 */
class CausalGraphNodeIndexBindAgentTest {

    private fun board(id: String) = blackboardContext(id = id)

    private fun node(
        nodeId: String,
        boardId: String,
        parents: List<String> = emptyList(),
        opId: String = "op",
    ): CausalGraphNode = causalGraphNode(
        nodeId = nodeId,
        opId = opId,
        opVersion = "v1",
        parentNodeIds = parents,
        inputFingerprint = "seed",
        blackboard = board(boardId),
        causalClock = 0L,
        topoOrdinal = 0,
        outputHash = null,
    )

    @Test fun bindAgentForwardsNodesToAgent() = runTest {
        val scope = backgroundScope
        val firedChannel = Channel<ReteAgent.Fire>(capacity = Channel.UNLIMITED)
        val agent = ReteAgent.run(
            rules = listOf(
                ReteAgent.ReteRule(
                    name = "any-node",
                    predicate = { true },
                    transform = { n -> ReteAgent.Fire("any-node", n.nodeId, n.causalKey, "ok", "x") },
                ),
            ),
            scope = scope,
            onFire = { firedChannel.trySend(it) },
        )

        val index = CausalGraphNodeIndex()

        // Bind the agent to the index
        index.bindAgent(agent)

        // Add nodes — they should flow through to the agent
        index.addOrGet(node("n1", "board-a"))
        index.addOrGet(node("n2", "board-a"))
        index.addOrGet(node("n3", "board-a"))
        advanceUntilIdle()

        val collected = withTimeoutOrNull(2_000) {
            buildList {
                repeat(3) {
                    val fire = firedChannel.receiveCatching().getOrNull() ?: return@buildList
                    add(fire)
                }
            }
        } ?: emptyList()

        assertEquals(3, collected.size, "expected 3 fires, saw ${collected.size}")
        assertEquals(setOf("n1", "n2", "n3"), collected.map { it.nodeId }.toSet())
        assertTrue(collected.all { it.agentId == "rete-agent" })
        assertTrue(collected.all { it.ruleName == "any-node" })

        ReteAgent.stop(agent)
        agent.job.join()
    }
}