package borg.trikeshed.dag

import borg.trikeshed.cursor.blackboardContext
import borg.trikeshed.graph.CausalGraphNode
import borg.trikeshed.graph.causalGraphNode
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Cut: agents run rete rules asynchronously from a Channel sink.
 *
 * Proves three things:
 *   1. Nodes fed to [ReteAgent.Agent.sink] fire matching rules asynchronously.
 *   2. Cross-board isolation — rules filtered by boardId do not fire for
 *      nodes on other boards.
 *   3. [ReteAgent.stop] cancels the consumer loop and no further fires happen.
 *
 * Uses `runTest` (from kotlinx-coroutines-test, which is present in commonTest
 * for every target) instead of `runBlocking` because runBlocking is not on the
 * commonTest classpath for JS/Wasm.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReteAgentTest {

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

    @Test fun sinkFeedFiresRulesAsynchronously() = runTest {
        val fired = Channel<ReteAgent.Fire>(capacity = Channel.UNLIMITED)
        val agent = ReteAgent.run(
            rules = listOf(
                ReteAgent.ReteRule(
                    name = "any-node",
                    predicate = { true },
                    transform = { n -> ReteAgent.Fire("any-node", n.nodeId, n.causalKey, "ok", "x") },
                ),
            ),
            scope = this,
            onFire = { fired.trySend(it) },
        )

        agent.sink.send(node("n1", "board-a"))
        agent.sink.send(node("n2", "board-a"))
        agent.sink.send(node("n3", "board-a"))
        advanceUntilIdle()

        val collected: List<ReteAgent.Fire> = withTimeoutOrNull(2_000) {
            buildList {
                repeat(3) {
                    val fire = fired.receiveCatching().getOrNull() ?: return@buildList
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

    @Test fun boardScopedRuleDoesNotFireForOtherBoards() = runTest {
        val fired = Channel<ReteAgent.Fire>(capacity = Channel.UNLIMITED)
        val agent = ReteAgent.run(
            rules = listOf(
                ReteAgent.ReteRule(
                    name = "board-a-only",
                    predicate = { it.blackboard.id == "board-a" },
                    transform = { n -> ReteAgent.Fire("board-a-only", n.nodeId, n.causalKey, "ok", "x") },
                ),
            ),
            scope = this,
            onFire = { fired.trySend(it) },
        )

        agent.sink.send(node("a1", "board-a"))
        agent.sink.send(node("b1", "board-b"))
        agent.sink.send(node("a2", "board-a"))
        advanceUntilIdle()

        val collected: List<ReteAgent.Fire> = withTimeoutOrNull(2_000) {
            buildList {
                repeat(2) {
                    val fire = fired.receiveCatching().getOrNull() ?: return@buildList
                    add(fire)
                }
            }
        } ?: emptyList()

        assertEquals(2, collected.size)
        assertEquals(setOf("a1", "a2"), collected.map { it.nodeId }.toSet())

        ReteAgent.stop(agent)
        agent.job.join()
    }

    @Test fun stopCancelsAgentAndStopsFiring() = runTest {
        val fired = Channel<ReteAgent.Fire>(capacity = Channel.UNLIMITED)
        val agent = ReteAgent.run(
            rules = listOf(
                ReteAgent.ReteRule(
                    name = "n",
                    predicate = { true },
                    transform = { n -> ReteAgent.Fire("n", n.nodeId, n.causalKey, "ok", "x") },
                ),
            ),
            scope = this,
            onFire = { fired.trySend(it) },
        )

        agent.sink.send(node("n1", "board-a"))
        advanceUntilIdle()
        val first = withTimeoutOrNull(2_000) { fired.receive() } ?: error("agent never fired")
        assertEquals("n1", first.nodeId)

        ReteAgent.stop(agent)
        agent.job.join()
        this.coroutineContext.cancelChildren()

        // After stop(), the agent's consumer is gone — even rapid subsequent
        // sends must not produce another fire.
        agent.sink.trySend(node("n2", "board-a"))
        advanceUntilIdle()
        val lateFire = fired.tryReceive().getOrNull()
        assertEquals(null, lateFire, "agent must not fire more after stop()")
    }
}
