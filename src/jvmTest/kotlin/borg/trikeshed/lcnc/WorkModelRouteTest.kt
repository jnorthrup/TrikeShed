package borg.trikeshed.lcnc

import borg.trikeshed.agent.keymux.*
import borg.trikeshed.agent.modelmux.*
import borg.trikeshed.htx.*
import borg.trikeshed.lib.*
import borg.trikeshed.modelmux.*
import kotlinx.coroutines.*
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.*

class WorkModelRouteTest {
    @Test fun selectedPaidModelDoesNotFallBackToAnotherAllowedRoute() = runBlocking {
        val policyActive = Airgap.active()
        val keys = KeyMux {
            bind("llm.one.key", TestKeySource(value = "one"))
            bind("llm.two.key", TestKeySource(value = "two"))
        }
        val mux = ModelMux(keys) {
            model("model-one", caps = _s["chat"], provider = "one", baseUrl = "https://one.invalid/v1")
            model("model-two", caps = _s["chat"], provider = "two", baseUrl = "https://two.invalid/v1")
        }
        val specs = _l[
            BrainClient.EndpointSpec("one", "", "https://one.invalid/v1", "model-one", provider = "one"),
            BrainClient.EndpointSpec("two", "", "https://two.invalid/v1", "model-two", provider = "two"),
        ]
        val brain = BrainClient(keyMux = keys, modelMux = mux, endpointSpecs = specs)
        val requests = mutableListOf<HtxRequest>()
        val htx = openHtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                requests.add(request)
                return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED,
                    request = request, response = HtxResponse(503, body = ByteSeries("unavailable".encodeToByteArray()))))
            }
        })
        val node = LcncNode("chat", WorkSelectNode.MODEL, _m["prompt" j "test",
            "models" j """[{"key":"one","model":"model-one"},{"key":"two","model":"model-two"}]"""])
        val selection = _m["kind" j "model", "endpoint" j "one", "model" j "model-one"]
        try {
            val failure = assertFailsWith<IllegalStateException> { withContext(htx) {
                TribunalNodes.registry(modelMuxDialog(brain, EmptyCoroutineContext)).getValue(WorkSelectNode.MODEL)
                    .run(node, _m["selection" j selection])
            } }
            if (policyActive) {
                assertIs<Airgap.Rejection>(failure)
                assertEquals(0, requests.size, "Airgap admission must refuse before the fixture transport")
            } else {
                assertFalse(failure is Airgap.Rejection)
                assertEquals(1, requests.size)
                assertEquals("one.invalid", requests.single().target.host)
                assertEquals("Bearer one", requests.single().headers.view.first { it.a == "Authorization" }.b)
            }
            println("WorkModelRouteTest paid: mode=" +
                (if (policyActive) "airgap-refusal" else "pinned-fixture-503") + "; fakeHtxCalls=" + requests.size)
        } finally { htx.close() }
    }

    @Test fun selectedLocalModelUsesOnlyItsExplicitKeylessRoute() = runBlocking {
        // A keyless HTTP endpoint remains networked; it is not in-process Kotlin inference.
        val policyActive = Airgap.active()
        val expectedRequests = if (policyActive) 0 else 1
        val keys = KeyMux { bind("llm.default.key", TestKeySource(value = "must-not-be-sent")) }
        val mux = ModelMux(keys) {
            model("local-model", caps = _s["chat"], baseUrl = "http://local.invalid/v1", provider = "local",
                auth = LlmAuth.NONE)
        }
        val spec = BrainClient.EndpointSpec("local", "", "http://local.invalid/v1", "local-model", provider = "local")
        val brain = BrainClient(keyMux = keys, modelMux = mux, endpointSpecs = _l[spec])
        val requests = mutableListOf<HtxRequest>()
        val htx = openHtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                requests.add(request)
                val body = """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
                return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED,
                    request = request, response = HtxResponse(200, body = ByteSeries(body.encodeToByteArray()))))
            }
        })
        val offer = _m["id" j "local-quote", "kind" j "model", "feature" j "classify", "currency" j "USD",
            "totalMicros" j "0", "expiresAt" j 2000L, "source" j "declared", "available" j true,
            "endpoint" j "local", "model" j "local-model"]
        val source = LcncNodeRunner { _, _ -> _m["offers" j _l[offer], "feature" j "classify",
            "currency" j "USD", "ceilingMicros" j "0"] }
        val registry = PureNodes.registry { 1000L } + TribunalNodes.registry(modelMuxDialog(brain, EmptyCoroutineContext)) + _m["test.source" j source]
        val chat = LcncNode("chat", WorkSelectNode.MODEL, _m["prompt" j "classify this",
            "models" j """[{"key":"local","model":"local-model"}]"""])
        val program = LcncProgram("work-model", s_[LcncNode("source", "test.source"), LcncNode("select", WorkSelectNode.TYPE), chat], s_[
            LcncWire("source", "offers", "select", "offers"), LcncWire("source", "feature", "select", "feature"),
            LcncWire("source", "currency", "select", "currency"), LcncWire("source", "ceilingMicros", "select", "ceilingMicros"),
            LcncWire("select", "selection", "chat", "selection")])
        try {
            if (policyActive) {
                assertFailsWith<Airgap.Rejection> { withContext(htx) { LcncRunner(registry).runAll(program) } }
            } else {
                val result = withContext(htx) { LcncRunner(registry).runAll(program) }
                assertEquals("ok", result.getValue("chat")["content"])
                assertEquals(1, requests.size)
                assertEquals("local.invalid", requests.single().target.host)
                assertFalse(requests.single().headers.view.any { it.a.equals("Authorization", ignoreCase = true) })
                assertContains(requests.single().body.toArray().decodeToString(), "\"model\":\"local-model\"")
            }
            assertEquals(expectedRequests, requests.size)

            val emptySource = LcncNodeRunner { _, _ -> _m["offers" j emptyList<Any?>(), "feature" j "classify",
                "currency" j "USD", "ceilingMicros" j "0"] }
            withContext(htx) { LcncRunner(registry + _m["test.source" j emptySource]).runAll(program) }
            assertEquals(expectedRequests, requests.size)

            val node = registry.getValue(WorkSelectNode.MODEL)
            val selection = _m["kind" j "model", "endpoint" j "local", "model" j "other"]
            assertFailsWith<IllegalArgumentException> { withContext(htx) { node.run(chat, _m["selection" j selection]) } }
            assertFailsWith<IllegalArgumentException> { withContext(htx) { node.run(chat, _m["selection" j (selection + _m["kind" j "service"])]) } }
            assertFailsWith<IllegalArgumentException> { withContext(htx) {
                node.run(chat.copy(params = chat.params + _m["models" j "[]"]),
                    _m["selection" j _m["kind" j "model", "endpoint" j "local", "model" j "local-model"]])
            } }
            assertFailsWith<IllegalArgumentException> { withContext(htx) {
                node.run(chat.copy(params = chat.params + _m["models" j """[{"key":"missing","model":"other"}]"""]),
                    _m["selection" j _m["kind" j "model", "endpoint" j "missing", "model" j "other"]])
            } }
            val duplicate = BrainClient(keyMux = keys, modelMux = mux,
                endpointSpecs = _l[spec, spec.copy(base = "http://other.invalid/v1")])
            assertFailsWith<IllegalArgumentException> { withContext(htx) {
                TribunalNodes.registry(modelMuxDialog(duplicate, EmptyCoroutineContext)).getValue(WorkSelectNode.MODEL)
                    .run(chat, _m["selection" j _m["kind" j "model", "endpoint" j "local", "model" j "local-model"]])
            } }
            assertEquals(expectedRequests, requests.size)
            println("WorkModelRouteTest keyless HTTP: mode=" +
                (if (policyActive) "airgap-refusal" else "pinned-fixture-success") + "; fakeHtxCalls=" + requests.size +
                "; empty-offer-and-invalid-selection-checks=executed")
        } finally { htx.close() }
    }
}
