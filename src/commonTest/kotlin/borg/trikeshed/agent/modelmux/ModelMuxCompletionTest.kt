package borg.trikeshed.agent.modelmux

import borg.trikeshed.htx.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.userspace.reactor.MuxReactorElement
import borg.trikeshed.agent.keymux.KeyMux
import borg.trikeshed.agent.keymux.TestKeySource
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
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
    fun sessionAttributionOwnsSubmittedToolsAndKeepsAssessmentIndependent() = runTest {
        val sent = mutableListOf<String>()
        val htx = openHtxElement(routeService = service {
            sent.add(it.body.toArray().decodeToString())
            HtxResponse(200, body = ByteSeries(reply.encodeToByteArray()))
        })
        val breadcrumbs = BreadcrumbElement()
        val mux = mux()
        try {
            withContext(htx + breadcrumbs + MuxCallContext(conversationId = 42, turnId = 7)) {
                mux.completion("fixture/local", confixDoc(request), assessmentId = "turn-cause").getOrThrow()
                mux.completion("fixture/local", confixDoc(request), assessmentId = "input-cause", contextId = "hermes-session").getOrThrow()
            }
            val calls = mux.activity.snapshot()
            assertEquals(listOf("42", "hermes-session"), calls.map { it.contextId })
            assertEquals(listOf("turn-cause", "input-cause"), calls.map { it.assessmentId })
            assertTrue(calls.all { it.conversationId == 42L && it.turnId == 7L })
            for (id in listOf("42", "hermes-session")) {
                val snapshot = breadcrumbs.transact(id, create = false) { confixDoc(it.submittedJson(id)) }
                assertEquals(true, snapshot.value("exact"))
                assertEquals("call-1", snapshot.value("messages", 0, "tool_call_id"))
                assertEquals(confixDoc(sent.first()).value("messages", 2, "content"), snapshot.value("messages", 0, "content"))
                assertEquals(null, snapshot.getAt("messages", 1))
            }
            assertEquals(2, sent.size)
            assertEquals(request.replace("\"not-leased\"", "\"wire-model\""), sent.last())
        } finally { htx.close() }
    }

    @Test
    fun completedRoutingSurvivesRestartWithCacheAndAdmissionFailure() = runTest {
        val store = Store()
        val contexts = BreadcrumbElement(store = store)
        val htx = openHtxElement(routeService = service { HtxResponse(200, ByteSeries(reply.encodeToByteArray())) })
        val reactor = MuxReactorElement()
        val mux = mux()
        try {
            withContext(htx + reactor + contexts + MuxCallContext(conversationId = 42, turnId = 7)) {
                repeat(2) { mux.completion("fixture/local", confixDoc(request), assessmentId = "cause").getOrThrow() }
                assertTrue(mux.completion("missing", confixDoc(request), assessmentId = "admission").isFailure)
            }
            val restored = BreadcrumbElement(store = store)
            val calls = restored.transact("42", create = false) { it.receipts() }
            assertEquals(3, calls.size)
            assertEquals(listOf(false, true, false), calls.view.map { it.cachedHit })
            assertEquals(listOf("completed", "completed", "failed"), calls.view.map { it.status })
            assertTrue(calls.view.all { it.contextId == "42" && it.conversationId == 42L && it.turnId == 7L && it.endedAt != null })
            assertEquals(listOf("cause", "cause", "admission"), calls.view.map { it.assessmentId })
            assertEquals("llm.fixture.key", calls[0].keyId)
            assertNull(calls[2].keyId)
            val response = BreadcrumbModelHost(mux, restored).route(parseHtxRequest(
                "http://localhost/v1/breadcrumbs?context=42&view=routing", method = HtxMethod.GET))!!
            assertEquals(200, response.status)
            val json = response.body.toArray().decodeToString()
            val document = confixDoc(json)
            assertEquals("42", document.value("context"))
            assertEquals("llm.fixture.key", document.value("calls", 0, "keyId"))
            assertEquals(true, document.value("calls", 1, "cachedHit"))
            assertEquals("failed", document.value("calls", 2, "status"))
            assertFalse(json.contains("fixture-secret"))
            assertTrue(store.rows.values.none { it.contains("fixture-secret") })
        } finally { try { reactor.close() } finally { htx.close() } }
    }

    @Test
    fun providerCompletionDoesNotRecreateADeletedContext() = runTest {
        val store = Store()
        val contexts = BreadcrumbElement(store = store)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val htx = openHtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                entered.complete(Unit)
                release.await()
                val response = HtxResponse(200, ByteSeries(reply.encodeToByteArray()))
                return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, request = request, response = response))
            }
        })
        val mux = mux()
        try {
            val completion = async {
                withContext(htx + contexts) { mux.completion("fixture/local", confixDoc(request), contextId = "deleted") }
            }
            entered.await()
            val admitted = contexts.transact("deleted", create = false) { it }
            contexts.remove("deleted")
            release.complete(Unit)
            completion.await().getOrThrow()
            withContext(htx + contexts + BreadcrumbAdmission(contexts, "deleted", admitted)) {
                mux.completion("fixture/local", confixDoc(request), contextId = "deleted").getOrThrow()
            }
            assertEquals(0, contexts.snapshot().size)
            assertEquals(0, BreadcrumbElement(store = store).snapshot().size)
            assertTrue(mux.activity.snapshot().all { it.status == "completed" })
        } finally { htx.close() }
    }

    @Test
    fun failedFinalReceiptWriteCannotReportCompletionSuccess() = runTest {
        val store = Store()
        val failure = IllegalStateException("receipt write failed")
        store.beforeWrite = { state ->
            if (state != null && BreadcrumbContext.restore(state).receipts().size > 0) throw failure
        }
        val contexts = BreadcrumbElement(store = store)
        val htx = openHtxElement(routeService = service { HtxResponse(200, ByteSeries(reply.encodeToByteArray())) })
        val mux = mux()
        try {
            val thrown = assertFailsWith<IllegalStateException> {
                withContext(htx + contexts) { mux.completion("fixture/local", confixDoc(request), contextId = "session") }
            }
            assertTrue(generateSequence<Throwable>(thrown) { it.cause }.any { it === failure },
                "Coroutine recovery must retain the original persistence failure")
            assertEquals(0, contexts.transact("session", create = false) { it.receipts().size })
            assertEquals(0, BreadcrumbElement(store = store).transact("session", create = false) { it.receipts().size })
            assertEquals("completed", mux.activity.snapshot().single().status, "Provider outcome is separate from durable receipt failure")
        } finally { htx.close() }
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

    private class Store : BreadcrumbStore {
        val rows = linkedMapOf<String, String>()
        var beforeWrite: (String?) -> Unit = {}
        override suspend fun load(): Series<Join<String, String>> = rows.entries.map { it.key j it.value }.toSeries()
        override suspend fun write(id: String, state: String?) {
            beforeWrite(state)
            if (state == null) rows.remove(id) else rows[id] = state
        }
    }
}
