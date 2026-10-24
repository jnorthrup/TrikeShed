package borg.trikeshed.modelmux

import borg.trikeshed.htx.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.reify
import borg.trikeshed.agent.keymux.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import borg.trikeshed.agent.modelmux.*
import kotlin.test.*

class BrainChatOptionsTest {
    @Test
    fun defaultsAndBothWireFormsKeepAuthoredLimits() {
        val defaults = BrainChatOptions.parse(emptyMap<String, Any>())
        assertEquals(512, defaults.maxTokens)
        assertEquals(0.2, defaults.temperature)
        assertEquals(0, defaults.models.size)
        val pair = mutableMapOf("key" to "override", "model" to "fixture")
        val options = BrainChatOptions.parse(mapOf("maxTokens" to "73", "temperature" to 0.4, "models" to listOf(pair)))
        pair["model"] = "changed"
        assertEquals(73, options.maxTokens)
        assertEquals(0.4, options.temperature)
        assertEquals("fixture", options.models[0].b, "Admission must freeze the parsed row")
        val json = BrainChatOptions.parse(mapOf("models" to """[{"key":"override","model":"fixture"}]"""))
        assertEquals(options.models[0].pair, json.models[0].pair)
    }

    @Test
    fun invalidLimitsAndRouteShapesAreRefused() {
        for (tokens in listOf(0, -1, 1.5, 2147483648L, Double.NaN, "Infinity", "garbage", true)) {
            assertFailsWith<IllegalArgumentException>("maxTokens=$tokens") {
                BrainChatOptions.parse(mapOf("maxTokens" to tokens))
            }
        }
        for (temperature in listOf(-0.1, 2.1, Double.NaN, "Infinity", "garbage", true)) {
            assertFailsWith<IllegalArgumentException>("temperature=$temperature") {
                BrainChatOptions.parse(mapOf("temperature" to temperature))
            }
        }
        for (models in listOf("{}", "garbage", "[{}]", listOf(1), listOf(mapOf("key" to " ", "model" to "m")))) {
            assertFailsWith<IllegalArgumentException>("models=$models") {
                BrainChatOptions.parse(mapOf("models" to models))
            }
        }
    }

    class Transport(val status: (Map<*, *>) -> Int = { 200 }) : HtxRouteService {
        val bodies = mutableListOf<Map<*, *>>()
        override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
            val body = reify(request.body.toArray().decodeToString()) as Map<*, *>
            bodies += body
            val response = HtxResponse(status(body), body = ByteSeries(
                """{"choices":[{"message":{"content":"fixture answer"}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}""".encodeToByteArray(),
            ))
            return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, request = request, response = response))
        }
    }

    @Test
    fun selectedRouteCarriesLimitsAndPerCallProvenanceThroughModelMux() = runTest {
        val keys = KeyMux { bind("llm.default.key", FixedKeySource("fixture-key")) }
        val mux = ModelMux(keys) { model("fixture", setOf("chat", "conflict-resolve"), "https://fixture.invalid/v1") }
        val brain = BrainClient(apiKey = "fixture-key", base = "https://fixture.invalid/v1", model = "fixture", keyMux = keys, modelMux = mux)
        val transport = Transport()
        val htx = openHtxElement(routeService = transport)
        try {
            val answer = withContext(htx) {
                brain.chat(BrainChatOptions(73, 0.4, s_["override" j "fixture"]), listOf("system" to "rules", "user" to "question"), "fixture-context")
            }
            assertEquals("fixture answer" to "fixture", answer)
            val body = transport.bodies.single()
            assertEquals(73, (body["max_tokens"] as Number).toInt())
            assertEquals(0.4, (body["temperature"] as Number).toDouble())
            assertEquals("fixture", body["model"])
            assertEquals(reify("""[{"role":"system","content":"rules"},{"role":"user","content":"question"}]"""), body["messages"])
            assertEquals("fixture-context", mux.lastReceipt?.assessmentId)
        } finally { htx.close() }
    }

    @Test
    fun failoverStaysInsideAuthoredOrderEvenAfterAnotherModelSucceeded() = runTest {
        val keys = KeyMux { bind("llm.default.key", FixedKeySource("fixture-key")) }
        val roster = BrainClient(keyMux = keys).endpointSummaries().distinctBy { it.model }.take(3)
        assertEquals(3, roster.size)
        val mux = ModelMux(keys) { roster.forEach { model(it.model, setOf("chat", "conflict-resolve"), it.base, provider = it.provider) } }
        val brain = BrainClient(keyMux = keys, modelMux = mux)
        var refuseSelected = false
        val transport = Transport { body -> if (refuseSelected && body["model"] != roster[2].model) 403 else 200 }
        val htx = openHtxElement(routeService = transport)
        fun options(vararg indices: Int) = BrainChatOptions(models = indices.map { roster[it].name j roster[it].model }.toSeries())
        try {
            withContext(htx) {
                assertEquals(roster[2].model, brain.chat(options(2), listOf("user" to "unrestricted fixture" )).second)
                transport.bodies.clear()
                refuseSelected = true
                assertFailsWith<IllegalStateException> { brain.chat(options(1, 0), listOf("user" to "restricted fixture")) }
                assertEquals(listOf(roster[1].model, roster[0].model), transport.bodies.map { it["model"] })
                transport.bodies.clear()
                assertFailsWith<IllegalArgumentException> {
                    brain.chat(BrainChatOptions(models = s_[roster[0].name j roster[0].model, "unknown" j "unknown"]), listOf("user" to "no dispatch"))
                }
                assertTrue(transport.bodies.isEmpty(), "All rows must be validated before the first attempt")
            }
        } finally { htx.close() }
    }

    @Test
    fun reboundDestinationAndAirgapCannotEscapeTheSelectedRoute() = runTest {
        val keys = KeyMux {
            bind("llm.default.key", FixedKeySource("fixture-key"))
            bind("llm.default.base_url", FixedKeySource("https://different.invalid/v1"))
        }
        val brain = BrainClient(apiKey = "fixture-key", base = "https://fixture.invalid/v1", model = "fixture", keyMux = keys)
        val options = BrainChatOptions(models = s_["override" j "fixture"])
        val transport = Transport()
        val htx = openHtxElement(routeService = transport)
        try {
            withContext(htx) {
                assertFailsWith<IllegalStateException> { brain.chat(options, listOf("user" to "refuse rebound")) }
                withContext(Airgap()) {
                    assertFailsWith<Airgap.Rejection> { brain.chat(options, listOf("user" to "refuse airgap")) }
                }
            }
            assertTrue(transport.bodies.isEmpty())
        } finally { htx.close() }
    }
}
