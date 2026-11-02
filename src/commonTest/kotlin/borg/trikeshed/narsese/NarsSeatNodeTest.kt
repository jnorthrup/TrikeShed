package borg.trikeshed.narsese

import borg.trikeshed.parse.reifyMap

import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.job.ContentId
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.CouncilDialog
import borg.trikeshed.lcnc.CouncilNodes
import borg.trikeshed.lcnc.InMemoryRecordStore
import borg.trikeshed.lcnc.RecordSeams
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * nars.seat reports nearby beliefs as unverified context and asks for
 * clarification without an exact reviewed proposition-to-evidence binding.
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
    fun nearbyBeliefsDoNotDetermineAChoice() = runTest {
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
        val verdict = reifyMap(content.substring(content.indexOf('{')..content.lastIndexOf('}')))
        assertEquals("INSUFFICIENT CURATED EVIDENCE", verdict["disposition"])
        assertEquals(true, verdict["needsClarification"])
        assertEquals(false, verdict["mistrial"])
        assertEquals("unverified-proximity", verdict["basis"])
        @Suppress("UNCHECKED_CAST")
        val ranking = verdict["ranking"] as List<Map<String, Any?>>
        assertEquals(2, ranking.size)
        val detained = ranking.first { it["choice"] == "delivery was detained" }
        val refused = ranking.first { it["choice"] == "delivery was refused" }
        assertEquals(0L, (detained["evidencePositive"] as Number).toLong())
        assertEquals(0L, (detained["evidenceNegative"] as Number).toLong())
        assertEquals(0L, (refused["evidencePositive"] as Number).toLong())
        assertEquals(0L, (refused["evidenceNegative"] as Number).toLong())
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
        val verdict = reifyMap(content.substring(content.indexOf('{')..content.lastIndexOf('}')))
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
        assertEquals("unverified-proximity", record["basis"])
        val labeled = out["labeled"] as String
        assertTrue(labeled.startsWith("[p1.review1 · review · round 2 · nars:belief-bag]"))
        bag.drain()
    }
    @Test
    fun unrelatedNearbySignalIsContextNotEvidenceAndCannotPublishARuling() = runTest {
        val choice = "payment was received"
        val centroid = angularOf(choice)
        val signal = SemanticSignal(
            angular = centroid xor (1L shl AngularCodec.Fields.RELATION_SHIFT),
            evidence = EvidenceCoord(4 * Nal.UNIT, 0L),
            relation = RelationKind.ATTRACTION,
            subjectCid = ContentId.of("unrelated source".encodeToByteArray()).value,
        )
        val bag = BeliefBagElement(capacity = 64)
        bag.open()
        bag.intake.send(BeliefIntake.Mint(signal, BudgetCoord(0.8f, 0.5f, 0.6f)))
        await("nearby signal must land") { bag.size == 1 }
        val output = NarsSeatNode.seatRunner(bag).run(
            node("nars.seat", mapOf("taxonomy" to "review", "maxDistance" to "1", "minEvidence" to "0")),
            mapOf("charge" to "what happened", "choices" to listOf(choice)),
        )
        val record = output["record"] as Map<*, *>
        val choices = record["choices"] as List<*>
        val first = choices.single() as Map<*, *>
        val front = first["front"] as List<*>
        assertEquals(1, front.size)
        assertEquals("unverified-proximity", (front.single() as Map<*, *>)["status"])
        assertEquals(0L, (first["evidencePositive"] as Number).toLong())
        val store = InMemoryRecordStore()
        val runners = CouncilNodes.registry(CouncilDialog { error("no model dispatch") }, RecordSeams.inMemory(store))
        val parsed = runners.getValue("ruling.parse").run(
            LcncNode("parse", "ruling.parse"), mapOf("text" to output["content"]),
        )
        assertEquals(true, parsed["needsClarification"])
        assertFailsWith<IllegalArgumentException> {
            runners.getValue("council.record").run(
                LcncNode("record", "council.record", params = mapOf("caseId" to "nars-advisory")),
                mapOf("verdict" to parsed["verdict"], "transcript" to output["content"],
                    "documentCid?" to ContentId.of("source".encodeToByteArray()).value),
            )
        }
        assertTrue(store.rulings.isEmpty())
        assertTrue(store.cas.isEmpty())
        bag.drain()
    }

    @Test
    fun exactCoordinateDoesNotEstablishPropositionIdentity() = runTest {
        val choice = "payment was received"
        val signal = SemanticSignal(
            angular = angularOf(choice),
            evidence = EvidenceCoord(4 * Nal.UNIT, 0L),
            relation = RelationKind.CAUSALITY,
            subjectCid = ContentId.of("different source".encodeToByteArray()).value,
        )
        val bag = BeliefBagElement(capacity = 64)
        bag.open()
        bag.intake.send(BeliefIntake.Mint(signal, BudgetCoord(0.8f, 0.5f, 0.6f)))
        await("colliding signal must land") { bag.size == 1 }
        val out = NarsSeatNode.seatRunner(bag).run(
            node("nars.seat", mapOf("taxonomy" to "review", "maxDistance" to "0")),
            mapOf("charge" to "what happened", "choices" to listOf(choice)),
        )
        val content = out["content"] as String
        val verdict = reifyMap(content.substring(content.indexOf('{')..content.lastIndexOf('}')))
        assertEquals(true, verdict["needsClarification"])
        assertEquals("INSUFFICIENT CURATED EVIDENCE", verdict["disposition"])
        val ranking = verdict["ranking"] as List<*>
        assertEquals(0L, (((ranking.single() as Map<*, *>)["evidencePositive"]) as Number).toLong())
        bag.drain()
    }

    @Test
    fun emptyBagWithZeroThresholdStillRequiresClarification() = runTest {
        val bag = mintedBag()
        val out = NarsSeatNode.seatRunner(bag).run(
            node("nars.seat", mapOf("minEvidence" to "0")),
            mapOf("charge" to "what happened", "choices" to listOf("a claim")),
        )
        val content = out["content"] as String
        val verdict = reifyMap(content.substring(content.indexOf('{')..content.lastIndexOf('}')))
        assertEquals(true, verdict["needsClarification"])
        assertEquals("INSUFFICIENT CURATED EVIDENCE", verdict["disposition"])
        bag.drain()
    }

}
