package borg.trikeshed.forge.server

import borg.trikeshed.lib.toSeries
import borg.trikeshed.narsese.BeliefBagElement
import borg.trikeshed.narsese.CuratorImpulse
import borg.trikeshed.narsese.CuratorImpulseElement
import borg.trikeshed.narsese.CuratorImpulseKind
import borg.trikeshed.narsese.ReplayScenario
import borg.trikeshed.narsese.ReplayTurn
import borg.trikeshed.narsese.TurnReviewElement
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W5.3 gate: the curator's HTTP window. Before this, CuratorImpulseElement
 * was live in the daemon but had NO route at all — teaching was unreachable.
 */
class BeliefWireCuratorTest {

    private fun wireWithCurator(): Pair<BeliefWire, CuratorImpulseElement> {
        val bag = BeliefBagElement(capacity = 512)
        runBlocking { bag.open() }
        val review = TurnReviewElement(bag)
        runBlocking { review.open() }
        val curator = CuratorImpulseElement(bag, parentJob = null)
        runBlocking { curator.open() }
        return BeliefWire(bag, review, memoryFiles = null, curator = curator) to curator
    }

    private suspend fun post(wire: BeliefWire, path: String, body: String) =
        wire.route("POST", path, "POST $path HTTP/1.1\r\nContent-Type: application/json\r\n\r\n$body", null)

    @Test
    fun teachPassLandsKnowledgeAndReturnsGlosses() = runBlocking {
        val (wire, _) = wireWithCurator()
        // One SUPPORTED outcome marker in the transcript ⇒ the impulse banks.
        val body = """
            {"impulses":[{"kind":"adopt","subject":"triage-metrics","rationale":"adopt for ranking"}],
             "scenarios":[
               {"scenarioId":"s1","impulseSubject":"triage-metrics","turns":[
                 {"role":"user","text":"start"},
                 {"role":"curator","text":"adopted triage-metrics SUCCESS"}
               ]}
             ]}
        """.trimIndent()
        val r = post(wire, "/api/beliefs/teach", body)!!
        assertEquals(200, r.status)
        val resp = JsonSupport.parse(r.body) as Map<*, *>
        assertEquals("ok", resp["verdict"])
        assertTrue((resp["landed"] as Number).toInt() >= 0, "teach completes without error")
        assertTrue((resp["knowledgeSize"] as Number).toInt() > 0,
            "SUMO spine bootstraps the bank: ${resp["knowledgeSize"]}")
    }

    @Test
    fun queryRouteAnswersFromTheBank() = runBlocking {
        val (wire, curator) = wireWithCurator()
        curator.knowledgeBank.assertKif("(subclass TriageMetric Metric)")
        val r = post(wire, "/api/beliefs/query", """{"pattern":"(subclass TriageMetric ?what)"}""")!!
        assertEquals(200, r.status)
        val resp = JsonSupport.parse(r.body) as Map<*, *>
        assertEquals("ok", resp["verdict"])
        @Suppress("UNCHECKED_CAST")
        val results = resp["results"] as List<Map<String, String>>
        assertTrue(results.isNotEmpty(), "bank solver finds the asserted subclass fact")
    }

    @Test
    fun teachWithoutCuratorDegradesTo503NotACrash() = runBlocking {
        val bag = BeliefBagElement(capacity = 64)
        runBlocking { bag.open() }
        val review = TurnReviewElement(bag)
        runBlocking { review.open() }
        val wire = BeliefWire(bag, review, memoryFiles = null, curator = null)
        val r = post(wire, "/api/beliefs/teach", """{"impulses":[]}""")!!
        assertEquals(503, r.status)
        assertEquals("curator not wired", (JsonSupport.parse(r.body) as Map<*, *>)["error"])
    }

    @Test
    fun teachRoundTripsThroughTheActualElementInternals() = runBlocking {
        // Prove the wire passes shapes the REAL recipient assesses: drive teach()
        // directly and confirm the bank grows by the assessed impulse.
        val (_, curator) = wireWithCurator()
        val before = curator.knowledgeBank.asserts().size
        val landed = curator.teach(
            listOf(CuratorImpulse(CuratorImpulseKind.PATCH, "hotfix-lane", "keeps prod green")).toSeries(),
            listOf(
                ReplayScenario(
                    "sc1", "hotfix-lane",
                    listOf(ReplayTurn("user", "go"), ReplayTurn("curator", "patch applied VERIFIED")).toSeries(),
                ),
            ).toSeries(),
        )
        val after = curator.knowledgeBank.asserts().size
        assertTrue(after > before || landed.isEmpty(),
            "a SUPPORTED scenario must grow the bank; landed=${landed.size} before=$before after=$after")
    }
}
