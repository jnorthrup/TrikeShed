package borg.trikeshed.lcnc

import borg.trikeshed.htx.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.agent.keymux.KeyMux
import borg.trikeshed.agent.keymux.TestKeySource
import borg.trikeshed.agent.modelmux.BreadcrumbElement
import borg.trikeshed.agent.modelmux.ModelMux
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class HeadhunterCouncilTest {
    private fun mux() = ModelMux(KeyMux { bind("llm.fixture.key", TestKeySource(value = "fixture-key")) }) {
        for (seat in listOf("fixture-a", "fixture-b", "fixture-c"))
            model(seat, setOf("chat"), provider = "fixture", baseUrl = "https://fixture.invalid/v1")
    }

    private fun completion(content: String): String = jsonOf(mapOf(
        "choices" to listOf(mapOf("message" to mapOf("role" to "assistant", "content" to content))),
        "usage" to mapOf("prompt_tokens" to 3, "completion_tokens" to 2)))

    /** The last message names the phase; the retained environment never reaches it. */
    private fun phaseOf(doc: ConfixDoc): String {
        val messages = requireNotNull(doc.getAt("messages")).kids
        val last = doc.value("messages", messages.size - 1, "content") as String
        return when {
            "You chair a drafting council" in last -> "synthesis"
            "Identify each concrete disagreement" in last -> "critique"
            else -> "opening"
        }
    }

    private fun lastPrompt(doc: ConfixDoc): String {
        val messages = requireNotNull(doc.getAt("messages")).kids
        return doc.value("messages", messages.size - 1, "content") as String
    }

    @Test fun seatsOpenCritiqueAndSynthesizeWithRetainedTrailsAndReleasedMemory() = runTest {
        val memory = borg.trikeshed.agent.modelmux.BreadcrumbElement()
        val sent = mutableListOf<ConfixDoc>()
        val htx = openHtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                val doc = confixDoc(request.body.toArray().decodeToString())
                sent.add(doc)
                val model = doc.value("model") as String
                val content = "${phaseOf(doc)} by $model"
                return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED,
                    request = request, response = HtxResponse(200, ByteSeries(completion(content).encodeToByteArray()))))
            }
        })
        try {
            val answer = withContext(htx) { mux().council(memory, s_["fixture-a", "fixture-b", "fixture-c"], "grounded drafting request") }
            assertEquals("synthesis by fixture-a", answer.drafts)
            assertEquals("fixture-a", answer.chair)
            assertEquals(listOf("opening", "opening", "opening", "critique", "critique", "critique", "synthesis"),
                answer.trail.map { it.phase })
            assertEquals(listOf("fixture-a", "fixture-b", "fixture-c", "fixture-a", "fixture-b", "fixture-c", "fixture-a"),
                answer.trail.map { it.model })
            assertEquals("opening by fixture-b", answer.trail[1].statement)
            assertEquals("synthesis by fixture-a", answer.trail.last().statement)

            // Every critique examined the other seats' openings, never its own.
            for (doc in sent) {
                if (phaseOf(doc) != "critique") continue
                val model = doc.value("model") as String
                val prompt = lastPrompt(doc)
                for (seat in listOf("fixture-a", "fixture-b", "fixture-c"))
                    assertEquals(seat != model, "opening by $seat" in prompt)
            }
            // The chair saw the original request, every opening and every critique.
            val chairPrompt = lastPrompt(sent.last { phaseOf(it) == "synthesis" })
            assertTrue("grounded drafting request" in chairPrompt)
            for (seat in listOf("fixture-a", "fixture-b", "fixture-c")) {
                assertTrue("opening by $seat" in chairPrompt)
                assertTrue("critique by $seat" in chairPrompt)
            }
            // Each seat's retained environment carried its own opening into its critique phase.
            val critiqueA = sent.first { phaseOf(it) == "critique" && (it.value("model") as String) == "fixture-a" }
            val retained = critiqueA.value("messages", 1, "content") as String
            assertTrue("opening by fixture-a" in retained, "the seat's own opening stays retained as untrusted evidence")
            // Council memory is released once the trail is the record.
            assertEquals(0, memory.snapshot().view.count { (it["context"] as String).startsWith("headhunter:") })
        } finally { htx.close() }
    }

    @Test fun aRefusedSeatStaysOnTheTrailAndTheRemainingSeatsCarryTheCouncil() = runTest {
        val memory = borg.trikeshed.agent.modelmux.BreadcrumbElement()
        val sent = mutableListOf<ConfixDoc>()
        val htx = openHtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                val doc = confixDoc(request.body.toArray().decodeToString())
                sent.add(doc)
                val model = doc.value("model") as String
                val phase = phaseOf(doc)
                if (model == "fixture-b" && phase == "opening") return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED,
                    request = request, response = HtxResponse(500, ByteSeries("{}".encodeToByteArray()))))
                return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED,
                    request = request, response = HtxResponse(200, ByteSeries(completion("$phase by $model").encodeToByteArray()))))
            }
        })
        try {
            val answer = withContext(htx) { mux().council(memory, s_["fixture-a", "fixture-b", "fixture-c"], "grounded drafting request") }
            assertEquals("synthesis by fixture-a", answer.drafts)
            val refusal = answer.trail[1]
            assertEquals("fixture-b", refusal.model)
            assertEquals("opening", refusal.phase)
            assertNotEquals("opening by fixture-b", refusal.statement, "A refused seat records its failure class")
            // The refused seat still critiques the answered openings; the answered seats never see it as an opening.
            assertEquals(listOf("fixture-a", "fixture-b", "fixture-c"), answer.trail.filter { it.phase == "critique" }.map { it.model })
            val chairPrompt = lastPrompt(sent.last { phaseOf(it) == "synthesis" })
            assertTrue("opening by fixture-a" in chairPrompt)
            assertTrue("opening by fixture-c" in chairPrompt)
            assertFalse("opening by fixture-b" in chairPrompt)
            for (doc in sent) {
                if (phaseOf(doc) != "critique") continue
                val model = doc.value("model") as String
                val prompt = lastPrompt(doc)
                assertFalse("opening by $model" in prompt, "A seat never critiques its own opening")
                for (answered in listOf("fixture-a", "fixture-c")) {
                    if (answered == model) continue
                    assertTrue("opening by $answered" in prompt, "The answered openings reach seat $model")
                }
            }
        } finally { htx.close() }
    }

    @Test fun aCouncilWithoutAnswersRefusesWithTheWholeTrail() = runTest {
        val htx = openHtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest) =
                HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED,
                    request = request, response = HtxResponse(500, ByteSeries("{}".encodeToByteArray()))))
        })
        try {
            val failure = assertFails {
                withContext(htx) { mux().council(BreadcrumbElement(), s_["fixture-a", "fixture-b"], "grounded drafting request") }
            }
            val refusal = assertIs<CouncilRefusal>(failure)
            assertTrue(failure.message.orEmpty().contains("no council seat answered"), failure.message)
            assertEquals(listOf("fixture-a", "fixture-b"), refusal.trail.map { it.model })
            assertTrue(refusal.trail.all { it.phase == HeadhunterCouncil.OPENING && it.statement.isNotBlank() })
        } finally { htx.close() }
    }

    @Test fun aCouncilDemandsDistinctSeats() = runTest {
        assertFailsWith<IllegalArgumentException> {
            mux().council(borg.trikeshed.agent.modelmux.BreadcrumbElement(), s_["fixture-a"], "grounded drafting request")
        }
        assertFailsWith<IllegalArgumentException> {
            mux().council(borg.trikeshed.agent.modelmux.BreadcrumbElement(), s_["fixture-a", "fixture-a"], "grounded drafting request")
        }
    }
}
