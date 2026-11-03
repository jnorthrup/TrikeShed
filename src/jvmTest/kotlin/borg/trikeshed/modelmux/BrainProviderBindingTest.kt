package borg.trikeshed.modelmux

import borg.trikeshed.agent.keymux.*
import borg.trikeshed.agent.modelmux.*
import borg.trikeshed.htx.*
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.reactor.MuxReactorElement
import kotlinx.coroutines.*
import kotlin.test.*

class BrainProviderBindingTest {
    @Test fun endpointCredentialsAreRedeemedPerAttemptWithoutDefaultFallback() = runBlocking {
        val wire = "nousresearch/hermes-3-llama-3.1-405b"
        val specs = _l[
            BrainClient.EndpointSpec("hermes-go", "HERMES_CUSTOM_API_TOKENROUTER_COM_API_KEY", "https://one.invalid/v1", wire),
            BrainClient.EndpointSpec("hermes-nvidia", "HERMES_CUSTOM_INTEGRATE_API_NVIDIA_COM_API_KEY", "https://one.invalid/v1", wire),
            BrainClient.EndpointSpec("hermes-synth", "HERMES_CUSTOM_API_SYNTHETIC_NEW_API_KEY", "https://three.invalid/v1", wire),
        ]
        val first = TestKeySource(value = "one")
        val second = TestKeySource(value = "two")
        val third = TestKeySource(value = "three")
        val keys = KeyMux {
            bind("llm.api-tokenrouter-com.key", first)
            bind("llm.integrate-api-nvidia-com.key", second)
            bind("llm.api-synthetic-new.key", third)
            bind("llm.default.key", TestKeySource(value = "default"))
        }
        val requests = mutableListOf<HtxRequest>()
        val htx = openHtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                requests.add(request)
                val body = """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1}}"""
                return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED,
                    request = request, response = HtxResponse(200, body = ByteSeries(body.encodeToByteArray()))))
            }
        })
        val brain = BrainClient(keyMux = keys, endpointSpecs = specs)
        val records = mutableListOf<MuxCallRecord>()
        val reactor = MuxReactorElement()
        suspend fun invoke(spec: BrainClient.EndpointSpec): Pair<String, String> =
            withContext(htx + reactor + MuxCallContext(onFinished = { records.add(it) })) {
                brain.chat(BrainChatOptions(models = s_[spec.name j spec.model]), _l["user" to "hello"])
            }
        try {
            for ((index, spec) in specs.withIndex()) {
                val result = invoke(spec)
                assertEquals("ok", result.first)
                val request = requests.last()
                assertEquals(_l["one.invalid", "one.invalid", "three.invalid"][index], request.target.host)
                assertEquals("Bearer " + _l["one", "two", "three"][index], request.headers.view.first { it.a == "Authorization" }.b)
                assertContains(request.body.toArray().decodeToString(), "\"model\":\"$wire\"")
                assertEquals(result.second, records.last().model)
                assertEquals(spec.provider, records.last().provider)
                assertEquals("llm.${spec.provider}.key", records.last().keyId)
            }
            assertEquals(3, records.map { it.model }.distinct().size)
            first.value = "rotated"
            invoke(specs[0])
            assertEquals(4, requests.size)
            assertEquals("Bearer rotated", requests.last().headers.view.first { it.a == "Authorization" }.b)
            assertEquals(false, records.last().cachedHit)
            third.value = null
            val before = requests.size
            assertFailsWith<IllegalStateException> { invoke(specs[2]) }
            assertEquals(before, requests.size)

            val mismatched = ModelMux(keys) {
                model(wire, caps = _s["chat", "conflict-resolve"], provider = "api-synthetic-new", baseUrl = specs[0].base)
            }
            val external = BrainClient(keyMux = keys, modelMux = mismatched, endpointSpecs = specs)
            assertFailsWith<IllegalStateException> {
                withContext(htx) { external.chat(BrainChatOptions(models = s_[specs[0].name j wire]), _l["user" to "hello"]) }
            }
            assertEquals(before, requests.size)

            val wrongWire = ModelMux(keys) {
                model(wire, caps = _s["chat", "conflict-resolve"], provider = specs[0].provider,
                    baseUrl = specs[0].base, wireModel = "different-model")
            }
            val wrongModel = BrainClient(keyMux = keys, modelMux = wrongWire, endpointSpecs = specs)
            assertFailsWith<IllegalStateException> {
                withContext(htx) { wrongModel.chat(BrainChatOptions(models = s_[specs[0].name j wire]), _l["user" to "hello"]) }
            }
            assertEquals(before, requests.size)

            val redirectedKeys = KeyMux {
                bind("llm.api-tokenrouter-com.key", TestKeySource(value = "one"))
                bind("llm.api-tokenrouter-com.base_url", TestKeySource(value = "https://wrong.invalid/v1"))
            }
            val redirected = BrainClient(keyMux = redirectedKeys, endpointSpecs = specs)
            assertFailsWith<IllegalStateException> {
                withContext(htx) { redirected.chat(BrainChatOptions(models = s_[specs[0].name j wire]), _l["user" to "hello"]) }
            }
            assertEquals(before, requests.size)
        } finally { htx.close() }
    }
}
