package borg.trikeshed.provenance

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProvenanceTraceTest {

    private fun cid(v: String): ContentId = ContentId("sha256:" + v.repeat(64).take(64))

    private fun edge(label: String, inputs: List<ContentId>, outputs: List<ContentId>, receipt: ContentId? = null) =
        ProvenanceEdge(label, "test", receipt, inputs.toSeries(), outputs.toSeries())

    @Test
    fun backwardWalksFromOutcomeToByteFootings() {
        val a = cid("a")
        val b = cid("b")
        val c = cid("c")
        val edges = listOf(
            edge("nlp", listOf(a), listOf(b)),
            edge("model", listOf(b), listOf(c)),
        )
        val nodes = ProvenanceTrace.trace(c, edges, TraceDirection.BACKWARD)
        assertEquals(
            listOf("Bytes", "Production", "Bytes", "Production", "Bytes"),
            nodes.map { when (it) {
                is ProvenanceNode.Bytes -> "Bytes"
                is ProvenanceNode.Production -> "Production"
            } },
        )
        val productions = nodes.filterIsInstance<ProvenanceNode.Production>().map { it.edge.label }
        assertEquals(listOf("model", "nlp"), productions)
        assertEquals(listOf(c, b, a), ProvenanceTrace.frontier(nodes))
    }

    @Test
    fun forwardWalksFromBytesToEveryDerivedOutcome() {
        val a = cid("a")
        val b = cid("b")
        val c = cid("c")
        val d = cid("d")
        val edges = listOf(
            edge("nlp", listOf(a), listOf(b)),
            edge("model", listOf(b), listOf(c)),
            edge("grounding", listOf(b), listOf(d)),
        )
        val nodes = ProvenanceTrace.trace(a, edges, TraceDirection.FORWARD)
        val productions = nodes.filterIsInstance<ProvenanceNode.Production>().map { it.edge.label }.toSet()
        assertEquals(setOf("nlp", "model", "grounding"), productions)
        val outcomes = ProvenanceTrace.frontier(nodes).toSet()
        assertEquals(setOf(a, b, c, d), outcomes)
    }

    @Test
    fun receiptBytesOpenTheirOwnStage() {
        val a = cid("a")
        val b = cid("b")
        val receiptBytes = cid("r")
        val edges = listOf(
            edge("nlp", listOf(a), listOf(b), receipt = receiptBytes),
            edge("audit", listOf(b), listOf(receiptBytes)),
        )
        // the audit stage consumed b and retained the nlp receipt's bytes; backward from the
        // receipt bytes must surface the nlp stage itself
        val nodes = ProvenanceTrace.trace(receiptBytes, edges, TraceDirection.BACKWARD)
        assertTrue(nodes.filterIsInstance<ProvenanceNode.Production>().any { it.edge.label == "nlp" })
    }

    @Test
    fun cyclesAreGuardedAndBudgetRespected() {
        val a = cid("a")
        val b = cid("b")
        val self = listOf(edge("loop", listOf(a), listOf(b)), edge("loop", listOf(b), listOf(a)))
        val nodes = ProvenanceTrace.trace(a, self, TraceDirection.FORWARD, depth = 4)
        assertTrue(nodes.size <= 9)
        val star = List(64) { i -> edge("fan", listOf(a), listOf(cid(i.toString()))) }
        assertEquals(1 + 1 + 64, ProvenanceTrace.trace(a, star, TraceDirection.FORWARD).size)
        assertEquals(3, ProvenanceTrace.trace(a, star, TraceDirection.FORWARD, budget = 3).size)
    }
}
