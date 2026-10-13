package modelmux

import borg.trikeshed.htx.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.userspace.reactor.MuxReactorElement
import keymux.KeyMux
import keymux.TestKeySource
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.*

class ModelMuxCompletionTest {
    private val reply = """{"id":"turn-1","choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[{"id":"call-2","type":"function","function":{"name":"lookup","arguments":"{\"q\":\"µ 🦉\"}"}}],"reasoning_content":"retain"}}],"usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}"""
    private val request = """{"model":"not-leased","messages":[{"role":"user","content":"µ 🦉"},{"role":"assistant","content":null,"tool_calls":[{"id":"call-1","type":"function","function":{"name":"lookup","arguments":"{}"}}]},{"role":"tool","tool_call_id":"call-1","content":"found"}],"tools":[{"type":"function","function":{"name":"lookup","description":"read only","parameters":{"type":"object","properties":{"q":{"type":"string"}}}}}],"tool_choice":"auto","parallel_tool_calls":false,"max_tokens":12}"""

    private fun service(handler: (HtxRequest) -> HtxResponse) = object : HtxRouteService {
        override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
            val response = handler(request)
            return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, request = request, response = response))
        }
    }

    private fun mux(legion: QuotaLegion? = null): ModelMux = ModelMux(KeyMux {
        bind("llm.fixture.key", TestKeySource(value = "fixture-secret"))
    }) {
        model("fixture/local", caps = setOf("chat", "tools"), provider = "fixture",
            wireModel = "wire-model", baseUrl = "https://fixture.invalid/v1")
        if (legion != null) quota(legion)
    }

    @Test
    fun toolHistoryAndReplySurviveTransportCacheAndMetering() = runTest {
        val requests = mutableListOf<String>()
        val htx = openHtxElement(routeService = service {
            requests.add(it.body.toArray().decodeToString())
            HtxResponse(200, body = ByteSeries(reply.encodeToByteArray()))
        })
        val reactor = MuxReactorElement()
        val mux = mux(QuotaLegion(defaultLimit = 1000))
        try {
            withContext(htx + reactor) {
                repeat(2) {
                    val result = mux.completion("fixture/local", confixDoc(request), contextId = "conversation-1").getOrThrow()
                    assertEquals(reply, result.src.toArray().decodeToString())
                    assertNull(result.value("choices", 0, "message", "content"))
                    assertEquals("call-2", result.value("choices", 0, "message", "tool_calls", 0, "id"))
                    assertEquals("tool_calls", result.value("choices", 0, "finish_reason"))
                    assertEquals(it == 1, mux.lastReceipt?.cachedHit)
                    assertEquals("conversation-1", mux.lastReceipt?.assessmentId)
                    assertEquals(4, mux.lastReceipt?.inputTokens)
                    assertEquals(2, mux.lastReceipt?.outputTokens)
                }
                assertEquals(6, mux.quotaStandings(kotlin.time.Clock.System.now().toEpochMilliseconds()).single().spent.toInt())
            }
            assertEquals(1, requests.size)
            assertEquals(request.replace("\"not-leased\"", "\"wire-model\""), requests.single())
            val sent = confixDoc(requests.single())
            assertEquals("call-1", sent.value("messages", 2, "tool_call_id"))
            assertEquals("µ 🦉", sent.value("messages", 0, "content"))
            assertEquals("read only", sent.value("tools", 0, "function", "description"))
            assertEquals(2, mux.activity.snapshot().count { it.status == "completed" })
        } finally { try { reactor.close() } finally { htx.close() } }
    }

    @Test
    fun invalidProtocolDoesNotReachTransport() = runTest {
        var calls = 0
        val htx = openHtxElement(routeService = service { calls++; HtxResponse(200) })
        val mux = mux()
        try {
            for (json in listOf(
                """{"messages":[],"stream":true}""",
                """{"messages":[],"stream":"false"}""",
                """{"messages":[],"model":"a","model":"b"}""",
                """{"messages":[],"api_key":"guest-key"}""",
                """{"messages":[],"base_url":"https://elsewhere.invalid"}""",
                """{"messages":[],"headers":{}}""", "{}", "[]",
            )) {
                assertTrue(withContext(htx) { mux.completion("fixture/local", confixDoc(json)) }.isFailure, json)
            }
            assertEquals(0, calls)
            assertTrue(mux.activity.snapshot().isEmpty(), "invalid protocol is not admitted")
        } finally { htx.close() }
    }

    @Test
    fun destinationAndModelAdmissionStillApply() = runTest {
        var calls = 0
        val htx = openHtxElement(routeService = service { calls++; HtxResponse(200) })
        val mux = mux()
        try {
            withContext(htx) {
                assertTrue(mux.completion("fixture/local", confixDoc(request), expectedBaseUrl = "https://elsewhere.invalid").isFailure)
                assertTrue(mux.completion("not-leased", confixDoc(request)).isFailure)
            }
            assertEquals(0, calls)
            assertEquals(2, mux.activity.snapshot().count { it.status == "failed" && it.endedAt != null })
            assertNotNull(mux.lastReceipt)
        } finally { htx.close() }
    }

    @Test
    fun failedAndMalformedRepliesAreNotCachedAsSuccess() = runTest {
        for (status in listOf(503, 200)) {
            var calls = 0
            val htx = openHtxElement(routeService = service {
                calls++
                HtxResponse(status, body = ByteSeries("""{"error":"unavailable"}""".encodeToByteArray()))
            })
            val reactor = MuxReactorElement()
            val mux = mux()
            try {
                withContext(htx + reactor) {
                    repeat(2) { assertTrue(mux.completion("fixture/local", confixDoc(request)).isFailure) }
                }
                assertEquals(2, calls)
                assertEquals(0, reactor.cache.apiCallCount())
                assertEquals(2, mux.activity.snapshot().count { it.status == "failed" })
                assertEquals(status, mux.lastReceipt?.httpStatus)
            } finally { try { reactor.close() } finally { htx.close() } }
        }
    }

    @Test
    fun textChatRetainsItsWireAndReturnShape() = runTest {
        var sent = ""
        val htx = openHtxElement(routeService = service {
            sent = it.body.toArray().decodeToString()
            HtxResponse(200, body = ByteSeries("""{"choices":[{"message":{"content":"answer"}}],"usage":{"prompt_tokens":4,"completion_tokens":2}}""".encodeToByteArray()))
        })
        val mux = mux()
        try {
            val result = withContext(htx) { mux.chat("fixture/local", s_["user" j "hello"], maxTokens = 12) }.getOrThrow()
            assertEquals("answer", result.a)
            assertEquals(4, result.b.a)
            assertEquals(2, result.b.b)
            assertEquals("""{"model":"wire-model","messages":[{"role":"user","content":"hello"}],"max_tokens":12}""", sent)
        } finally { htx.close() }
    }
}
