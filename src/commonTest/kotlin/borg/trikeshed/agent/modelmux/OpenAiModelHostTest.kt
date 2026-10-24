package borg.trikeshed.agent.modelmux

import borg.trikeshed.htx.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.agent.keymux.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.*

class OpenAiModelHostTest {
    private val reply = """{"id":"turn-1","object":"chat.completion","model":"fixture","choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[{"id":"call-1","type":"function","function":{"name":"lookup","arguments":"{}"}}]}}],"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}"""

    private fun request(body: String, path: String = "/v1/chat/completions", method: HtxMethod = HtxMethod.POST) =
        parseHtxRequest("http://localhost$path", method = method, body = ByteSeries(body.encodeToByteArray()))

    private fun local(keys: KeyMux = KeyMux {}): ModelMux = ModelMux(keys) {
        model("host/fixture", setOf("chat", "tools"), baseUrl = "http://127.0.0.1:8000/v1/",
            wireModel = "fixture", auth = LlmAuth.NONE)
    }

    private fun service(handler: (HtxRequest) -> HtxResponse) = object : HtxRouteService {
        override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult =
            HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, request = request, response = handler(request)))
    }

    @Test
    fun hostedCompletionUsesConfiguredDestinationWithoutDefaultCredentialsAndPreservesToolEnvelopes() = runTest {
        val keys = KeyMux {
            bind("llm.default.key", TestKeySource(value = "operator-secret"))
            bind("llm.default.base_url", TestKeySource(value = "https://wrong.invalid/v1"))
        }
        val mux = local(keys)
        var sent: HtxRequest? = null
        val htx = openHtxElement(routeService = service { sent = it; HtxResponse(200, ByteSeries(reply.encodeToByteArray())) })
        try {
            val body = """{"model":"host/fixture","messages":[{"role":"tool","tool_call_id":"prior","content":"µ 🦉"}],"tools":[{"type":"function","function":{"name":"lookup","parameters":{"type":"object"}}}],"parallel_tool_calls":false}"""
            val result = withContext(htx) { OpenAiModelHost(mux).handle(request(body))!! }
            assertEquals(200, result.status)
            assertEquals(reply, result.body.toArray().decodeToString())
            val upstream = assertNotNull(sent)
            assertEquals("127.0.0.1", upstream.target.host)
            assertEquals("/v1/chat/completions", upstream.target.requestPath)
            assertNull(upstream.headerValue("Authorization"))
            assertNull(upstream.headerValue("x-api-key"))
            assertEquals(body.replace("host/fixture", "fixture"), upstream.body.toArray().decodeToString())
            assertNull(mux.modelKeyId("host/fixture"))
            assertEquals(3, mux.lastReceipt?.inputTokens)
            assertEquals(2, mux.lastReceipt?.outputTokens)
        } finally { htx.close() }
    }

    @Test
    fun invalidRequestsAndUnsupportedStreamingNeverReachCompletion() = runTest {
        var calls = 0
        val host = OpenAiModelHost(local(), completion = { _, _ -> calls++; Result.success(confixDoc(reply)) })
        val cases = s_[
            400 j """{"model":"host/fixture","messages":[]""",
            400 j """{"model":"host/fixture","messages":[]} {}""",
            400 j """{"model":"host/fixture","messages":[],"headers":{}}""",
            400 j """{"model":"host/fixture","messages":[],"model":"host/fixture"}""",
            400 j """{"messages":[]}""",
            404 j """{"model":"unknown","messages":[]}""",
            501 j """{"model":"host/fixture","messages":[],"stream":true}""",
        ]
        for ((status, body) in cases.view) assertEquals(status, host.handle(request(body))?.status, body)
        assertEquals(405, host.handle(request("", method = HtxMethod.GET))?.status)
        assertEquals(0, calls)
        val models = host.handle(request("", "/v1/models", HtxMethod.GET))!!
        assertEquals("host/fixture", confixDoc(models.body.toArray(), Syntax.JSON).value("data", 0, "id"))
        assertNull(host.handle(request("", "/elsewhere", HtxMethod.GET)))
    }

    @Test
    fun catalogExtensionKeepsHostedCredentialsSeparateFromOperatorFallbacks() = runTest {
        val original = ModelMux(KeyMux { bind("llm.default.key", TestKeySource(value = "operator-secret")) }) {
            model("operator", setOf("chat"), baseUrl = "https://operator.invalid/v1")
        }
        val extended = original.withModels(KeyMux {}) {
            model("host/private", setOf("chat"), baseUrl = "http://127.0.0.1:8000/v1", provider = "host:private")
        }
        assertTrue(extended.session("host/private").isFailure)
        assertNull(extended.modelKeyId("host/private"))
        val inherited = extended.session("operator").getOrThrow()
        assertEquals("Bearer operator-secret", inherited.authHeaders().headerValue("Authorization"))
        inherited.close()
        assertFailsWith<IllegalArgumentException> {
            ModelMux(KeyMux {}) { model("missing-destination", setOf("chat"), auth = LlmAuth.NONE) }
        }
    }

    @Test
    fun publicDiscoveryDoesNotForwardDefaultCredentialAndReadsVllmContextLimit() = runTest {
        var sent: HtxRequest? = null
        val htx = openHtxElement(routeService = service { sent = it; HtxResponse(200, ByteSeries(
            """{"data":[{"id":"fixture","max_model_len":8192}]}""".encodeToByteArray())) })
        try {
            val models = withContext(htx) {
                ModelDiscovery.discover("local", "http://127.0.0.1:8000/v1/",
                    KeyMux { bind("llm.default.key", TestKeySource(value = "operator-secret")) }, LlmAuth.NONE).getOrThrow()
            }
            assertEquals(8192, models[0].contextWindow)
            assertEquals("/v1/models", sent?.target?.requestPath)
            assertNull(sent?.headerValue("Authorization"))
        } finally { htx.close() }
    }

    @Test
    fun upstreamHttpStatusSurvivesWithoutDisclosingProviderDiagnostics() = runTest {
        var status = 400
        val htx = openHtxElement(routeService = service {
            HtxResponse(status, ByteSeries("""{"error":{"message":"operator-secret"}}""".encodeToByteArray()))
        })
        try {
            val host = OpenAiModelHost(local())
            for (upstream in s_[202, 400, 429, 503].view) {
                status = upstream
                val response = withContext(htx) {
                    host.handle(request("""{"model":"host/fixture","messages":[]}"""))!!
                }
                assertEquals(upstream, response.status)
                assertFalse(response.body.toArray().decodeToString().contains("operator-secret"))
                if (upstream == 202) assertEquals("model_pending",
                    confixDoc(response.body.toArray(), Syntax.JSON).value("error", "type"))
                if (upstream == 429) assertEquals("rate_limit_error",
                    confixDoc(response.body.toArray(), Syntax.JSON).value("error", "type"))
            }
        } finally { htx.close() }
    }

    @Test
    fun hostPropagatesCancellationAndDoesNotDiscloseUpstreamExceptionText() = runTest {
        val body = """{"model":"host/fixture","messages":[]}"""
        val cancelled = OpenAiModelHost(local(), completion = { _, _ -> throw CancellationException("caller stopped") })
        assertFailsWith<CancellationException> { cancelled.handle(request(body)) }
        val failed = OpenAiModelHost(local(), completion = { _, _ -> Result.failure(IllegalStateException("operator-secret")) })
        val response = failed.handle(request(body))!!
        assertEquals(502, response.status)
        assertFalse(response.body.toArray().decodeToString().contains("operator-secret"))
    }
}
