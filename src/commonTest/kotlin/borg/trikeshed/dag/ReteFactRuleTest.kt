package borg.trikeshed.dag

import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
class ReteFactRuleTest {

    private fun fact(
        className: String,
        bytecodeOffset: Int = 0,
        timestamp: Long = 0L,
        threadId: Long = 1L
    ): ReteFact.DagFact {
        val coord = DagCoordinate(
            className = className,
            methodName = "m",
            bytecodeOffset = bytecodeOffset,
            timestamp = timestamp,
            threadId = threadId
        )
        return ReteFact.DagFact(
            coordinate = coord,
            event = BlackboardEvent.ClassLoad(coord, className, "loader")
        )
    }

    @Test fun sinkFeedFiresRulesAsynchronously() = runTest {
        val fired = Channel<ReteAgent.Fire>(capacity = Channel.UNLIMITED)
        val agent = ReteAgent.runFacts(
            rules = listOf(
                ReteAgent.ReteFactRule(
                    name = "any-fact",
                    predicate = { true },
                    transform = { f -> ReteAgent.Fire("any-fact", f.factId, "ckey", "ok", "x") },
                ),
            ),
            scope = this,
            onFire = { fired.trySend(it) },
        )

        agent.sink.send(fact("A"))
        agent.sink.send(fact("B"))
        agent.sink.send(fact("C"))
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
        assertEquals(setOf("dag:A@0", "dag:B@0", "dag:C@0"), collected.map { it.nodeId }.toSet())
        assertTrue(collected.all { it.agentId == "rete-fact-agent" })
        assertTrue(collected.all { it.ruleName == "any-fact" })

        ReteAgent.stop(agent)
        agent.job.join()
    }

    @Test fun classScopedRuleDoesNotFireForOtherClasses() = runTest {
        val fired = Channel<ReteAgent.Fire>(capacity = Channel.UNLIMITED)
        val agent = ReteAgent.runFacts(
            rules = listOf(
                ReteAgent.ReteFactRule(
                    name = "class-A-only",
                    predicate = { (it as? ReteFact.DagFact)?.coordinate?.className == "A" },
                    transform = { f -> ReteAgent.Fire("class-A-only", f.factId, "ckey", "ok", "x") },
                ),
            ),
            scope = this,
            onFire = { fired.trySend(it) },
        )

        agent.sink.send(fact("A", 1))
        agent.sink.send(fact("B", 1))
        agent.sink.send(fact("A", 2))
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
        assertEquals(setOf("dag:A@1", "dag:A@2"), collected.map { it.nodeId }.toSet())

        ReteAgent.stop(agent)
        agent.job.join()
    }

    @Test fun stopCancelsAgentAndStopsFiring() = runTest {
        val fired = Channel<ReteAgent.Fire>(capacity = Channel.UNLIMITED)
        val agent = ReteAgent.runFacts(
            rules = listOf(
                ReteAgent.ReteFactRule(
                    name = "f",
                    predicate = { true },
                    transform = { f -> ReteAgent.Fire("f", f.factId, "ckey", "ok", "x") },
                ),
            ),
            scope = this,
            onFire = { fired.trySend(it) },
        )

        agent.sink.send(fact("A"))
        advanceUntilIdle()
        val first = withTimeoutOrNull(2_000) { fired.receive() } ?: error("agent never fired")
        assertEquals("dag:A@0", first.nodeId)

        ReteAgent.stop(agent)
        agent.job.join()
        this.coroutineContext.cancelChildren()

        // After stop(), the agent's consumer is gone
        agent.sink.trySend(fact("B"))
        advanceUntilIdle()
        val lateFire = fired.tryReceive().getOrNull()
        assertEquals(null, lateFire, "agent must not fire more after stop()")
    }
}
