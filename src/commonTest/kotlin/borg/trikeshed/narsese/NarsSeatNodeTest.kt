package borg.trikeshed.narsese

import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.job.ContentId
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * nars.seat gate: the non-model council seat ranks choices by revised curated
 * evidence, asks for clarification when the bag is silent, and keeps the
 * council.seat record shape so the folds and ruling.parse compose.
 */
class NarsSeatNodeTest {

    private fun node(type: String, params: Map<String, String> = emptyMap()) =
        LcncNode(id = "n", type = type, params = params)

    private fun angularOf(subject: String) = AngularCodec.encode(
        relation = RelationKind.CAUSALITY,
        taxonomyKey = "review",
        subjectTerm = subject,
    )

    private fun signalFor(subject: String, positive: Boolean = true) = SemanticSignal(
        angular = angularOf(subject),
        evidence = if (positive) EvidenceCoord(4 * Nal.UNIT, 0L) else EvidenceCoord(0L, 4 * Nal.UNIT),
        relation = RelationKind.CAUSALITY,
        subjectCid = ContentId.of(subject.encodeToByteArray()).value,
    )

    private suspend fun await(message: String, cond: () -> Boolean) {
        withTimeout(60_000) { while (!cond()) delay(10) }
        assertTrue(cond(), message)
    }

    private suspend fun mintedBag(vararg subjects: Pair<String, Boolean>): BeliefBagElement {
        val bag = BeliefBagElement(capacity = 64)
        bag.open()
        val distinct = subjects.map { (s, _) -> angularOf(s) }.toSet().size
        for ((subject, positive) in subjects) {
            bag.intake.send(BeliefIntake.Mint(signalFor(subject, positive), BudgetCoord(0.8f, 0.5f, 0.6f)))
        }
        await("minted beliefs must land") { bag.size >= distinct }
        return bag
    }

    @Test
    fun ranksChoicesByRevisedEvidence() = runTest {
        val bag = mintedBag("delivery was detained" to true, "delivery was refused" to false)
        val out = NarsSeatNode.seatRunner(bag).run(
            node("nars.seat", mapOf("taxonomy" to "review", "maxDistance" to "0")),
            mapOf(
                "charge" to "what happened to the res",
                "choices" to listOf("delivery was detained", "delivery was refused"),
            ),
        )
        assertEquals("nars:belief-bag", out["model"])
        val content = out["content"] as String
        val verdict = JsonSupport.parseMap(content.substring(content.indexOf('{')..content.lastIndexOf('}')))
        assertEquals("delivery was detained", verdict["disposition"])
        assertEquals(false, verdict["needsClarification"])
        assertEquals(false, verdict["mistrial"])
        assertEquals("nars:belief-bag", verdict["basis"])
        @Suppress("UNCHECKED_CAST")
        val ranking = verdict["ranking"] as List<Map<String, Any?>>
        assertEquals(2, ranking.size)
        val detained = ranking.first { it["choice"] == "delivery was detained" }
        val refused = ranking.first { it["choice"] == "delivery was refused" }
        assertTrue((detained["expectation"] as Double) > 0.5, "supported choice expects above half")
        assertTrue((refused["expectation"] as Double) < 0.5, "refuted choice expects below half")
        bag.drain()
    }

    @Test
    fun silentBagAsksForClarification() = runTest {
        val bag = mintedBag("unrelated belief" to true)
        val out = NarsSeatNode.seatRunner(bag).run(
            node("nars.seat", mapOf("taxonomy" to "review", "maxDistance" to "0")),
            mapOf("charge" to "rule on the unheard", "choices" to listOf("a claim nobody curated")),
        )
        val content = out["content"] as String
        val verdict = JsonSupport.parseMap(content.substring(content.indexOf('{')..content.lastIndexOf('}')))
        assertEquals(true, verdict["needsClarification"])
        assertEquals("INSUFFICIENT CURATED EVIDENCE", verdict["disposition"])
        assertTrue((verdict["clarificationQuestion"] as String).isNotBlank())
        bag.drain()
    }

    @Test
    fun recordMirrorsCouncilSeatShape() = runTest {
        val bag = mintedBag("the contract was enforced" to true)
        val out = NarsSeatNode.seatRunner(bag).run(
            node("nars.seat", mapOf(
                "taxonomy" to "review", "maxDistance" to "0",
                "panel" to "p1", "seat" to "review1", "role" to "review", "round" to "2",
                "caseId" to "case-7", "contextId" to "council/case-7/p1/review1",
            )),
            mapOf("charge" to "was the contract enforced", "choices" to listOf("the contract was enforced")),
        )
        @Suppress("UNCHECKED_CAST")
        val record = out["record"] as Map<String, Any?>
        for (key in listOf("seat", "panel", "role", "round", "charge", "requestedModel",
            "answeredBy", "status", "cid", "text", "chars", "contextId")) {
            assertTrue(key in record, "record carries council.seat key $key")
        }
        assertEquals("review1", record["seat"])
        assertEquals("p1", record["panel"])
        assertEquals(2, record["round"])
        assertEquals("", record["requestedModel"])
        assertEquals("nars:belief-bag", record["answeredBy"])
        assertEquals("ok", record["status"])
        assertEquals("nars:belief-bag", record["basis"])
        val labeled = out["labeled"] as String
        assertTrue(labeled.startsWith("[p1.review1 · review · round 2 · nars:belief-bag]"))
        bag.drain()
    }
}
