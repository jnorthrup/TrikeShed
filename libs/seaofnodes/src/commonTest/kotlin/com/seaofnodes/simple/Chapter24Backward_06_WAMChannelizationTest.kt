package com.seaofnodes.simple

import borg.trikeshed.net.ProtocolId
import borg.trikeshed.net.channelization.*
import borg.trikeshed.net.channelization.ChannelGraph
import borg.trikeshed.net.channelization.ChannelGraphId
import borg.trikeshed.net.channelization.ChannelGraphState
import borg.trikeshed.net.channelization.ChannelJob
import borg.trikeshed.net.channelization.ChannelJobConfig
import borg.trikeshed.net.channelization.ChannelJobId
import borg.trikeshed.net.channelization.ChannelJobState
import borg.trikeshed.net.channelization.ChannelSessionId
import borg.trikeshed.net.channelization.JobType
import borg.trikeshed.net.channelization.PatternActivationRule
import borg.trikeshed.net.channelization.SimpleChannelGraph
import borg.trikeshed.net.channelization.SimpleChannelJob
import borg.trikeshed.net.channelization.WorkerKey
import borg.trikeshed.net.channelization.activateGraphJobs
import kotlin.collections.emptyList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Chapter24Backward_06_WAMChannelizationTest {
    @Test
    fun `WAM unification step creates ChannelJob with binding facts`() {
        val graph =
            SimpleChannelGraph(
                id = ChannelGraphId("wam-unify"),
                owner = WorkerKey("wam-worker"),
                activationRules = emptyList(),
            )
        graph.transitionTo(ChannelGraphState.Active)

        // RED: WamUnifier does not exist — no WAM layer over ChannelGraph
        val unifier = WamUnifier(graph)
        val result = unifier.unify("f(X,Y)", "f(1,2)")
        assertEquals(2, result.bindings.size)
        assertEquals("1", result.bindings["X"])
        assertEquals("2", result.bindings["Y"])

        // RED: no bridge from unification result to ChannelJob with GraphFacts
        val job = result.toChannelJob()
        assertEquals(JobType.CUSTOM, job.type)
        val bindingFacts = graph.queryFacts { it is GraphFact.CustomFact && it.key.startsWith("wam:") }
        assertEquals(2, bindingFacts.size)
    }

    @Test
    fun `WAM choicepoint creates ChannelSession with multiple activation rules`() {
        val graph =
            SimpleChannelGraph(
                id = ChannelGraphId("wam-choicepoint"),
                owner = WorkerKey("wam-worker"),
                activationRules = emptyList(),
            )
        graph.transitionTo(ChannelGraphState.Active)

        // RED: no WAM choicepoint → ChannelSession bridge
        val choicepoint =
            WamChoicepoint(
                clauses = listOf("member(X,[1|_])", "member(X,[_|T])"),
                variable = "X",
            )
        val session = choicepoint.toChannelSession(ProtocolId.HTTP)
        assertNotNull(session)
        assertEquals(ChannelSessionState.Active, session.state)

        val rules = choicepoint.toActivationRules()
        assertEquals(2, rules.size)
    }

    @Test
    fun `WAM answer substitution emits ChannelBlock`() {
        // RED: ChannelBlock does not carry substitution maps
        val substitution = mapOf("X" to "1", "Y" to "2")

        val block =
            ChannelBlock(
                id = ChannelJobId("answer-0"),
                graphId = ChannelGraphId("wam-answer"),
                substitution = substitution,
            )
        assertEquals(2, block.substitution.size)
        assertEquals("1", block.substitution["X"])

        // RED: no emission of ChannelBlock from WAM answer substitution
        val engine = WamEngine()
        val answers = engine.query("member(X,[1,2,3])")
        assertEquals(3, answers.size)
        answers.forEach { answer ->
            assertNotNull(answer.toChannelBlock())
        }
    }

    @Test
    fun `WAM proof stream emits ChannelEnvelope sequence per answer`() {
        // RED: ChannelEnvelope and WAM proof stream do not exist
        val engine = WamEngine()
        val envelopes = engine.queryEnvelopes("member(X,[1,2,3])")
        assertEquals(3, envelopes.size)

        envelopes.forEachIndexed { i, envelope ->
            val payload = envelope.payload as Map<*, *>
            assertEquals((i + 1).toString(), payload["X"])
        }
    }

    @Test
    fun `WAM cut maps to ChannelJob cancellation on remaining alternatives`() {
        val graph =
            SimpleChannelGraph(
                id = ChannelGraphId("wam-cut"),
                owner = WorkerKey("wam-worker"),
                activationRules = emptyList(),
            )
        graph.transitionTo(ChannelGraphState.Active)

        // Create 3 pending jobs representing 3 clause alternatives
        val jobs =
            (0..2).map { i ->
                SimpleChannelJob(
                    id = ChannelJobId("alt-$i"),
                    graphId = graph.id,
                    owner = WorkerKey("wam-worker"),
                    type = JobType.CUSTOM,
                    state = ChannelJobState.Pending,
                    priority = i,
                    sessionId = null,
                )
            }
        jobs.forEach { job -> graph.addFact(GraphFact.JobFact(job.id, "alternative", true)) }

        // RED: WAM cut does not exist, no cancellation bridge
        val cut = WamCut(graph, jobs)
        cut.execute()

        val remaining = jobs.filter { it.state != ChannelJobState.Cancelled }
        assertEquals(1, remaining.size)
    }
}

// RED: all WAM types below do not exist

private data class UnificationResult(
    val bindings: Map<String, String>,
) {
    fun toChannelJob(): ChannelJob = TODO()
}

private class WamUnifier(
    private val graph: ChannelGraph,
) {
    fun unify(
        goal: String,
        fact: String,
    ): UnificationResult = TODO()
}

private data class WamChoicepoint(
    val clauses: List<String>,
    val variable: String,
) {
    fun toChannelSession(protocol: ProtocolId): ChannelSession = TODO()

    fun toActivationRules(): List<ActivationRule> = TODO()
}

private data class ChannelBlock(
    val id: ChannelJobId,
    val graphId: ChannelGraphId,
    val substitution: Map<String, String>,
)

private data class ChannelEnvelope(
    val payload: Any,
)

private class WamEngine {
    fun query(goal: String): List<UnificationResult> = TODO()

    fun queryEnvelopes(goal: String): List<ChannelEnvelope> = TODO()
}

private class WamCut(
    private val graph: ChannelGraph,
    private val alternatives: List<ChannelJob>,
) {
    suspend fun execute() {
        alternatives.drop(1).forEach { it.cancel() }
    }
}

private typealias ActivationRule = borg.trikeshed.net.channelization.ActivationRule
