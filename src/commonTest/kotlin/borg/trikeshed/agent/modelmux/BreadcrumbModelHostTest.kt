package borg.trikeshed.agent.modelmux

import borg.trikeshed.htx.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.confix.*
import borg.trikeshed.agent.keymux.KeyMux
import borg.trikeshed.agent.keymux.TestKeySource
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import borg.trikeshed.agent.modelmux.acp.TOOL_BREADCRUMB
import kotlin.test.*

class BreadcrumbModelHostTest {
    private val reply = """{"id":"answer-1","choices":[{"index":0,"message":{"role":"assistant","content":"answer"},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":1}}"""
    private val history = """{"model":"fixture/local","breadcrumb_context":"session-a","messages":[{"role":"system","content":"system instructions"},{"role":"user","content":"user instructions"},{"role":"assistant","content":null,"tool_calls":[{"id":"call-1","type":"function","function":{"name":"read","arguments":"{}"}}],"reasoning_content":"opaque reasoning","provider_signature":{"sequence":9007199254740993,"value":"µ 🦉"}},{"role":"tool","tool_call_id":"call-1","content":"0123456789abcdef","vendor":{"sequence":9007199254740993}}],"tools":[{"type":"function","function":{"name":"read","description":"read evidence","parameters":{"type":"object"}}}],"vendor_sequence":9007199254740993,"max_tokens":16}"""

    private fun mux(): ModelMux = ModelMux(KeyMux {
        bind("llm.fixture.key", TestKeySource(value = "fixture-secret"))
    }) {
        model("fixture/local", caps = setOf("chat", "tools"), provider = "fixture",
            wireModel = "wire-model", baseUrl = "https://fixture.invalid/v1")
    }

    private fun request(body: String = "", path: String = "/v1/chat/completions", method: HtxMethod = HtxMethod.POST): HtxRequest =
        parseHtxRequest("http://localhost$path", method = method, body = ByteSeries(body.encodeToByteArray()))

    private fun service(handler: (HtxRequest) -> HtxResponse) = object : HtxRouteService {
        override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
            val response = handler(request)
            return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, request = request, response = response))
        }
    }

    private fun HtxResponse.document(): ConfixDoc = confixDoc(body.toArray().decodeToString())

    @Test fun submittedInspectionRetainsActualToolInputWithoutEnrollmentOrDecay() = runTest {
        var now = 0L
        val contexts = BreadcrumbElement({ BreadcrumbContext({ now }, BreadcrumbRetention(1_000, 1_000)) })
        val host = BreadcrumbModelHost(mux(), contexts)
        contexts.transact("observed") {
            it.put(BreadcrumbId("omitted"), "read", "evidence absent from the request")
            it.resize(BreadcrumbId("omitted"), BreadcrumbPresence(0.4))
        }
        val input = confixDoc("""{"messages":[{"role":"system","content":"private instructions"},{"role":"tool","tool_call_id":"actual","content":[{"type":"text","text":"exact µ 🦉"}]},{"role":"user","content":"private prompt"}]}""")
        var sent = ""
        val htx = openHtxElement(routeService = service {
            sent = it.body.toArray().decodeToString()
            HtxResponse(200, body = ByteSeries(reply.encodeToByteArray()))
        })
        try {
            val result = withContext(htx) {
                host.completion("fixture/local", input, contextId = "observed", assessmentId = "manual-cause")
            }
            assertTrue(result.isSuccess)
            val outgoing = confixDoc(sent)
            assertNull(outgoing.getAt("tools"), "Observation must not advertise a retrieval tool")
            val first = host.handle(request(path = "/v1/breadcrumbs?context=observed&view=submitted", method = HtxMethod.GET))!!
            val submitted = first.document()
            assertEquals(true, submitted.value("exact"))
            assertEquals("fixture/local", submitted.value("model"))
            assertEquals("observed", submitted.value("context"))
            assertEquals(1, (submitted.value("messageCount") as Number).toInt())
            assertEquals("actual", submitted.value("messages", 0, "tool_call_id"))
            assertEquals(BreadcrumbModelHost.raw(outgoing, outgoing.getAt("messages", 1, "content")!!),
                BreadcrumbModelHost.raw(submitted, submitted.getAt("messages", 0, "content")!!))
            assertFalse(first.body.toArray().decodeToString().contains("private"))
            assertFalse(first.body.toArray().decodeToString().contains("omitted"))
            now = 100_000
            val second = host.handle(request(path = "/v1/breadcrumbs?context=observed&view=submitted", method = HtxMethod.GET))!!
            assertEquals(first.body.toArray().decodeToString(), second.body.toArray().decodeToString())
            val listing = host.handle(request(path = "/v1/breadcrumbs", method = HtxMethod.GET))!!.document()
            assertEquals("observed", listing.value("contexts", 0, "context"))
            assertEquals(1, (listing.value("contexts", 0, "records") as Number).toInt(), "Observation retains no new evidence")
            assertEquals(1, contexts.snapshot().size)
            contexts.transact("observed") {
                assertEquals(0.4, it.nextToolResult()[0].presence.value, "Inspection must not consume the pending resize")
            }
        } finally { htx.close() }
    }

    @Test fun submittedProjectionMatchesOutgoingRowsAndReplacesOmittedHistory() = runTest {
        var now = 0L
        val contexts = BreadcrumbElement({ BreadcrumbContext({ now }, BreadcrumbRetention(1_000, 1_000)) })
        val host = BreadcrumbModelHost(mux(), contexts)
        var sent = ""
        val htx = openHtxElement(routeService = service {
            sent = it.body.toArray().decodeToString()
            HtxResponse(200, body = ByteSeries(reply.encodeToByteArray()))
        })
        try {
            withContext(htx) { assertEquals(200, host.handle(request(history))!!.status) }
            now = 1_000
            withContext(htx) { assertEquals(200, host.handle(request(history))!!.status) }
            val outgoing = confixDoc(sent)
            val captured = host.handle(request(path = "/v1/breadcrumbs?context=session-a&view=submitted", method = HtxMethod.GET))!!.document()
            assertEquals(outgoing.value("messages", 3, "content"), captured.value("messages", 0, "content"))
            assertEquals("0123", confixDoc(captured.value("messages", 0, "content") as String).value("content"))
            val omitted = """{"model":"fixture/local","breadcrumb_context":"session-a","messages":[{"role":"user","content":"next request omits earlier evidence"}]}"""
            withContext(htx) { assertEquals(200, host.handle(request(omitted))!!.status) }
            val latest = host.handle(request(path = "/v1/breadcrumbs?context=session-a&view=submitted", method = HtxMethod.GET))!!.document()
            assertEquals(0, latest.getAt("messages")!!.kids.size)
            assertEquals(true, latest.value("exact"))
            assertEquals(1, contexts.transact("session-a") { it.snapshot().size })
        } finally { htx.close() }
    }

    @Test fun observationCapacityDoesNotBlockModelCallsOrClaimAnExactEmptyPayload() = runTest {
        val contexts = BreadcrumbElement(capacity = 1)
        val host = BreadcrumbModelHost(mux(), contexts)
        var calls = 0
        val htx = openHtxElement(routeService = service {
            calls++
            HtxResponse(200, body = ByteSeries(reply.encodeToByteArray()))
        })
        try {
            val tools = (0..1000).joinToString(",") { """{"role":"tool","tool_call_id":"call-$it","content":"evidence"}""" }
            val input = confixDoc("""{"messages":[$tools]}""")
            withContext(htx) { assertTrue(host.completion("fixture/local", input, contextId = "retained").isSuccess) }
            val submitted = host.handle(request(path = "/v1/breadcrumbs?context=retained&view=submitted", method = HtxMethod.GET))!!.document()
            assertEquals(false, submitted.value("exact"))
            assertEquals("capacity", submitted.value("reason"))
            assertEquals(1001, (submitted.value("messageCount") as Number).toInt())
            assertEquals(0, submitted.getAt("messages")!!.kids.size)
            withContext(htx) { assertTrue(host.completion("fixture/local", input, contextId = "over-capacity").isSuccess) }
            assertEquals(2, calls)
            assertEquals(1, contexts.snapshot().size)
        } finally { htx.close() }
    }

    @Test fun malformedToolArgumentsRemainRecoverableProtocolErrors() = runTest {
        val host = BreadcrumbModelHost(mux(), BreadcrumbElement())
        for (body in listOf("{\"context\":\"unterminated", "{\"context\":\"bad\\", "[]")) {
            assertEquals(400, host.handle(request(body, "/v1/breadcrumbs"))!!.status)
        }
        val context = BreadcrumbContext()
        val target = "x".repeat(257)
        val input = """{"messages":[{"role":"assistant","tool_calls":[{"id":"failed-resize","function":{"name":"breadcrumb","arguments":"{\"id\":\"$target\",\"presence\":1}"}}]},{"role":"tool","tool_call_id":"failed-resize","content":"invalid id"}]}"""
        repeat(2) { BreadcrumbModelHost.project(confixDoc(input), context) }
        assertEquals(0, context.snapshot().size)
    }

    @Test fun optedInHostDecaysToolEvidenceAndPreservesOtherWireFields() = runTest {
        var now = 0L
        val contexts = BreadcrumbElement({ BreadcrumbContext({ now }, BreadcrumbRetention(1_000, 1_000)) })
        val mux = mux()
        val host = BreadcrumbModelHost(mux, contexts)
        val sent = mutableListOf<String>()
        val htx = openHtxElement(routeService = service {
            sent.add(it.body.toArray().decodeToString())
            HtxResponse(200, body = ByteSeries(reply.encodeToByteArray()))
        })
        try {
            withContext(htx) {
                assertEquals(200, host.handle(request(history))!!.status)
                now = 1_000
                val result = host.handle(request(history))!!
                assertEquals(200, result.status)
                assertEquals(reply, result.body.toArray().decodeToString())
            }
            assertEquals(2, sent.size)
            val projected = confixDoc(sent.last())
            assertEquals("wire-model", projected.value("model"))
            assertNull(projected.getAt("breadcrumb_context"))
            assertEquals("system instructions", projected.value("messages", 0, "content"))
            assertEquals("user instructions", projected.value("messages", 1, "content"))
            assertNull(projected.value("messages", 2, "content"))
            assertEquals("opaque reasoning", projected.value("messages", 2, "reasoning_content"))
            assertEquals("µ 🦉", projected.value("messages", 2, "provider_signature", "value"))
            assertEquals("call-1", projected.value("messages", 2, "tool_calls", 0, "id"))
            assertEquals("call-1", projected.value("messages", 3, "tool_call_id"))
            assertEquals("tool", projected.value("messages", 3, "role"))
            assertTrue(sent.last().contains("\"provider_signature\":{\"sequence\":9007199254740993,\"value\":\"µ 🦉\"}"))
            assertTrue(sent.last().contains("\"vendor\":{\"sequence\":9007199254740993}"))
            assertTrue(sent.last().contains("\"vendor_sequence\":9007199254740993"))
            val crumb = confixDoc(projected.value("messages", 3, "content") as String)
            assertEquals("call-1", crumb.value("id"))
            assertEquals(0.25, (crumb.value("presence") as Number).toDouble())
            assertEquals("0123", crumb.value("content"))
            assertEquals("read", projected.value("tools", 0, "function", "name"))
            assertEquals("read evidence", projected.value("tools", 0, "function", "description"))
            assertEquals(TOOL_BREADCRUMB, projected.value("tools", 1, "function", "name"))
            assertEquals("session-a", mux.lastReceipt?.assessmentId)
            contexts.transact("session-a") {
                val record = it.lookup(BreadcrumbId("call-1"))!!
                assertEquals("0123456789abcdef", record.content)
                assertEquals(BreadcrumbTimestamp(0), record.createdAt)
                assertEquals(BreadcrumbTimestamp(0), record.lastAccessAt)
            }
            withContext(htx) {
                val conflicting = history.replace("0123456789abcdef", "replacement")
                assertEquals(400, host.handle(request(conflicting))!!.status)
            }
            assertEquals(2, sent.size, "Conflicting evidence must not reach the hosted model")
            assertEquals("0123456789abcdef", contexts.transact("session-a") { it.lookup(BreadcrumbId("call-1"))!!.content })
        } finally { htx.close() }
    }

    @Test fun ordinaryHostingDoesNotEnrollHistoryOrAdvertiseBreadcrumbTools() = runTest {
        val contexts = BreadcrumbElement()
        val host = BreadcrumbModelHost(mux(), contexts)
        val original = history.replace("\"breadcrumb_context\":\"session-a\",", "")
        var sent = ""
        val htx = openHtxElement(routeService = service {
            sent = it.body.toArray().decodeToString()
            HtxResponse(200, body = ByteSeries(reply.encodeToByteArray()))
        })
        try {
            assertEquals(200, withContext(htx) { host.handle(request(original))!!.status })
            assertEquals(original.replace("\"fixture/local\"", "\"wire-model\""), sent)
            assertEquals(0, contexts.transact("session-a") { it.snapshot().size })
        } finally { htx.close() }
    }

    @Test fun resizeRouteAppliesZeroFractionAndFullOnceWithoutLosingEvidence() = runTest {
        var now = 0L
        val contexts = BreadcrumbElement({ BreadcrumbContext({ now }, BreadcrumbRetention(1_000, 1_000)) })
        val host = BreadcrumbModelHost(mux(), contexts)
        val id = BreadcrumbId("call-1")
        contexts.transact("session-a") { it.put(id, "read", "0123456789") }
        contexts.transact("session-b") { it.put(id, "read", "different evidence") }
        now = 100_000

        suspend fun resize(context: String, presence: String): HtxResponse = host.handle(request(
            """{"context":"$context","id":"call-1","presence":$presence}""", "/v1/breadcrumbs"))!!
        suspend fun snapshot(): ConfixDoc = host.handle(request(path = "/v1/breadcrumbs?context=session-a", method = HtxMethod.GET))!!.document()

        contexts.transact("session-a") { it.resize(id, BreadcrumbPresence(0.3)) }
        assertNull(snapshot().value("breadcrumbs", 0, "content"), "GET observes decay without consuming a pending override")
        for (invalid in arrayOf("-0.1", "1.1", "\"0.5\"", "null")) assertEquals(400, resize("session-a", invalid).status)
        assertEquals("012", contexts.transact("session-a") { it.nextToolResult()[0].content })

        val zero = resize("session-a", "0")
        assertEquals(200, zero.status)
        assertEquals("call-1", zero.document().value("breadcrumbs", 0, "id"))
        assertNull(zero.document().getAt("breadcrumbs", 0, "content"))
        val fraction = resize("session-a", "0.4")
        assertEquals(200, fraction.status)
        assertEquals("0123", fraction.document().value("breadcrumbs", 0, "content"))
        val full = resize("session-a", "1")
        assertEquals(200, full.status)
        assertEquals("0123456789", full.document().value("breadcrumbs", 0, "content"))
        assertNull(snapshot().getAt("breadcrumbs", 0, "content"), "The resize belongs only to its returned tool result")
        assertEquals("0123456789", contexts.transact("session-a") { it.lookup(id)!!.content })
        assertEquals(BreadcrumbTimestamp(0), contexts.transact("session-b") { it.lookup(id)!!.lastAccessAt })
        assertEquals("different evidence", resize("session-b", "1").document().value("breadcrumbs", 0, "content"))
        assertEquals(400, host.handle(request("""{"context":"session-a","id":"missing","presence":1}""", "/v1/breadcrumbs"))!!.status)
    }

    @Test fun concurrentTransactionsRetainEveryResultAndDrainAfterFailure() = runTest {
        val contexts = BreadcrumbElement({ BreadcrumbContext({ 0L }) })
        coroutineScope {
            val owner = coroutineContext[Job]!!
            val ids = (0 until 48).map { index ->
                async(Dispatchers.Default) {
                    contexts.transact("shared") { it.put("tool:$index", "evidence:$index").id }
                }
            }.awaitAll()
            assertEquals(48, ids.toSet().size)
            val results = contexts.transact("shared") { it.snapshot() }
            assertEquals((0 until 48).map { "evidence:$it" }.toSet(), results.view.map { it.content }.toSet())
            assertTrue(owner.children.none { it.isActive }, "Successful transactions leave no active worker")

            val failure = assertFailsWith<IllegalStateException> {
                contexts.transact("shared") { throw IllegalStateException("transaction failure") }
            }
            assertEquals("transaction failure", failure.message)
            assertTrue(owner.isActive)
            assertTrue(owner.children.none { it.isActive }, "Failed transactions also join their worker")
            assertEquals(48, contexts.transact("shared") { it.snapshot().size })
            assertEquals(0, contexts.transact("isolated") { it.snapshot().size })
        }
    }

    @Test fun retrievalToolOutputIsPresentedOnceThenReplacedByReferenceWithoutRecursiveRetention() = runTest {
        var now = 0L
        val contexts = BreadcrumbElement({ BreadcrumbContext({ now }, BreadcrumbRetention(1_000, 1_000)) })
        val host = BreadcrumbModelHost(mux(), contexts)
        val sent = mutableListOf<String>()
        val toolReply = """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"resize-call","type":"function","function":{"name":"breadcrumb","arguments":"{\"id\":\"call-1\",\"presence\":0.75}"}}]},"finish_reason":"tool_calls"}]}"""
        val htx = openHtxElement(routeService = service {
            sent.add(it.body.toArray().decodeToString())
            when (sent.size) {
                1 -> HtxResponse(200, body = ByteSeries(toolReply.encodeToByteArray()))
                2 -> HtxResponse(503, body = ByteSeries("""{"error":"try again"}""".encodeToByteArray()))
                else -> HtxResponse(200, body = ByteSeries(reply.encodeToByteArray()))
            }
        })
        try {
            withContext(htx) {
                val firstResponse = host.handle(request(history))!!
                assertEquals(200, firstResponse.status)
                val response = firstResponse.document()
                val call = confixDoc(BreadcrumbModelHost.raw(response, response.getAt("choices", 0, "message", "tool_calls", 0)!!))
                assertEquals(TOOL_BREADCRUMB, call.value("function", "name"))
                val args = confixDoc(call.value("function", "arguments") as String)
                now = 1_000
                val resized = host.handle(request(
                    """{"context":"session-a","id":${jsonOf(args.value("id"))},"presence":${jsonOf(args.value("presence"))}}""", "/v1/breadcrumbs"))!!
                assertEquals(200, resized.status)
                val toolOutput = resized.body.toArray().decodeToString()
                val assistant = BreadcrumbModelHost.raw(response, response.getAt("choices", 0, "message")!!)
                val retrievalHistory = """{"model":"fixture/local","breadcrumb_context":"session-a","messages":[{"role":"assistant","content":null,"tool_calls":[{"id":"call-1","type":"function","function":{"name":"read","arguments":"{}"}}]},{"role":"tool","tool_call_id":"call-1","content":"0123456789abcdef"},$assistant,{"role":"tool","tool_call_id":${jsonOf(call.value("id"))},"content":${jsonOf(toolOutput)}}]}"""

                assertEquals(503, host.handle(request(retrievalHistory))!!.status)
                assertEquals(200, host.handle(request(retrievalHistory))!!.status)
                val first = confixDoc(sent.last())
                assertEquals(toolOutput, first.value("messages", 3, "content"), "Failed upstream delivery must not consume the retrieval")
                val submitted = host.handle(request(path = "/v1/breadcrumbs?context=session-a&view=submitted", method = HtxMethod.GET))!!.document()
                assertEquals(toolOutput, submitted.value("messages", 1, "content"), "Inspection must retain the exact one-time retrieval envelope")
                assertEquals("0123456789ab", confixDoc(first.value("messages", 3, "content") as String).value("breadcrumbs", 0, "content"))
                assertEquals(1, contexts.transact("session-a") { it.snapshot().size })
                assertNull(contexts.transact("session-a") { it.lookup(BreadcrumbId("resize-call")) })

                now = 2_000
                assertEquals(200, host.handle(request(retrievalHistory))!!.status)
                val replay = confixDoc(sent.last())
                assertEquals("resize-call", replay.value("messages", 3, "tool_call_id"))
                val decayed = confixDoc(replay.value("messages", 3, "content") as String)
                val replaySubmission = host.handle(request(path = "/v1/breadcrumbs?context=session-a&view=submitted", method = HtxMethod.GET))!!.document()
                assertEquals(replay.value("messages", 3, "content"), replaySubmission.value("messages", 1, "content"))
                assertEquals("call-1", decayed.value("id"))
                assertEquals(0.0, (decayed.value("presence") as Number).toDouble())
                assertNull(decayed.value("content"))
                val original = confixDoc(replay.value("messages", 1, "content") as String)
                assertEquals(0.125, (original.value("presence") as Number).toDouble())
                assertEquals("01", original.value("content"))
                contexts.transact("session-a") {
                    assertEquals(1, it.snapshot().size)
                    assertEquals("0123456789abcdef", it.lookup(BreadcrumbId("call-1"))!!.content)
                    assertEquals(BreadcrumbTimestamp(1_000), it.lookup(BreadcrumbId("call-1"))!!.lastAccessAt)
                }
            }
        } finally { htx.close() }
    }

    @Test fun firstProjectionRetainsShortNewEvidenceWhenClockAdvancesBetweenReads() {
        var tick = 0L
        val context = BreadcrumbContext({ tick++ })
        val projected = BreadcrumbModelHost.project(confixDoc(
            """{"messages":[{"role":"tool","tool_call_id":"boolean-result","content":"1"}]}"""), context)
        val crumb = confixDoc(projected.value("messages", 0, "content") as String)
        assertEquals("1", crumb.value("content"))
        assertEquals(1.0, (crumb.value("presence") as Number).toDouble())
    }

    @Test fun textPartArraysDecayAndRemainRetrievableAsExactJsonEvidence() = runTest {
        var now = 0L
        val contexts = BreadcrumbElement({ BreadcrumbContext({ now }, BreadcrumbRetention(1_000, 1_000)) })
        val host = BreadcrumbModelHost(mux(), contexts)
        val array = """[ {"type":"text","text":"first part"}, {"type":"text","text":"second part"} ]"""
        val input = confixDoc("""{"messages":[{"role":"tool","tool_call_id":"array-call","content":$array}]}""")
        val first = contexts.transact("arrays") { BreadcrumbModelHost.project(input, it) }
        assertEquals(array, confixDoc(first.value("messages", 0, "content") as String).value("content"))
        now = 1_000
        val replay = contexts.transact("arrays") { BreadcrumbModelHost.project(input, it) }
        val crumb = confixDoc(replay.value("messages", 0, "content") as String)
        assertEquals(0.25, (crumb.value("presence") as Number).toDouble())
        val held = (crumb.value("content") as String).split(' ').filter { it != BREADCRUMB_GAP }
        assertTrue(held.isNotEmpty() && held.joinToString(" ").length <= kotlin.math.round(array.length * 0.25))
        var at = 0
        for (word in held) at = array.indexOf(word, at).also { assertTrue(it >= 0, "particle $word is not in order") } + word.length
        val retrieved = host.handle(request(
            """{"context":"arrays","id":"array-call","presence":1}""", "/v1/breadcrumbs"))!!
        assertEquals(200, retrieved.status)
        assertEquals(array, retrieved.document().value("breadcrumbs", 0, "content"))
        assertEquals(array, contexts.transact("arrays") { it.lookup(BreadcrumbId("array-call"))!!.content })
        val invalid = confixDoc("""{"messages":[{"role":"tool","tool_call_id":"invalid-call","content":[{"type":"image_url","image_url":{"url":"fixture"}}]}]}""")
        assertFailsWith<IllegalArgumentException> { contexts.transact("arrays") { BreadcrumbModelHost.project(invalid, it) } }
        assertEquals(1, contexts.transact("arrays") { it.snapshot().size })
    }

    @Test fun cancellationDuringProjectionReleasesAdmittedEmissionReservations() = runTest {
        var caller: Job? = null
        var cancelledDuringProjection = false
        val contexts = BreadcrumbElement({ BreadcrumbContext({
            caller?.let {
                cancelledDuringProjection = true
                it.cancel()
            }
            0L
        }) })
        val host = BreadcrumbModelHost(mux(), contexts)
        val input = """{"model":"fixture/local","breadcrumb_context":"cancelled","messages":[{"role":"assistant","content":null,"tool_calls":[{"id":"cancelled-retrieval","type":"function","function":{"name":"breadcrumb","arguments":"{\"id\":\"original\",\"presence\":1}"}}]},{"role":"tool","tool_call_id":"cancelled-retrieval","content":"retrieval output"}]}"""
        val completion = launch {
            caller = coroutineContext[Job]!!
            host.handle(request(input))
            fail("Cancellation must propagate from the admitted projection")
        }
        completion.join()
        caller = null
        assertTrue(cancelledDuringProjection)
        assertTrue(completion.isCancelled)
        assertTrue(completion.children.none { it.isActive })
        contexts.transact("cancelled", create = false) {
            val fresh = (0 until 1000).map { index -> BreadcrumbId("fresh-$index") }.toSet()
            it.reserveEmissions(fresh)
            it.completeEmissions(fresh, successful = false)
        }
    }

    @Test fun failedEmissionSaveReleasesReservationWithoutReportingSuccess() = runTest {
        val emission = BreadcrumbId("retrieved")
        val rows = mutableMapOf<String, String>()
        val failure = IllegalStateException("emission write failed")
        val store = object : BreadcrumbStore {
            override suspend fun load(): Series<Join<String, String>> = rows.entries.map { it.key j it.value }.toSeries()
            override suspend fun write(id: String, state: String?) {
                if (state != null && BreadcrumbContext.restore(state).emitted(emission)) throw failure
                if (state == null) rows.remove(id) else rows[id] = state
            }
        }
        val contexts = BreadcrumbElement(store = store)
        val host = BreadcrumbModelHost(mux(), contexts)
        val htx = openHtxElement(routeService = service { HtxResponse(200, ByteSeries(reply.encodeToByteArray())) })
        val input = """{"model":"fixture/local","breadcrumb_context":"session","messages":[{"role":"assistant","content":null,"tool_calls":[{"id":"retrieved","type":"function","function":{"name":"breadcrumb","arguments":"{\"id\":\"original\",\"presence\":1}"}}]},{"role":"tool","tool_call_id":"retrieved","content":"retrieval output"}]}"""
        try {
            val thrown = assertFailsWith<IllegalStateException> {
                withContext(htx) { host.completion("fixture/local", confixDoc(input)) }
            }
            assertTrue(generateSequence<Throwable>(thrown) { it.cause }.any { it === failure },
                "Coroutine recovery must retain the original persistence failure")
            contexts.transact("session", create = false) {
                assertFalse(it.emitted(emission))
                val fresh = (0 until 1000).map { index -> BreadcrumbId("fresh-$index") }.toSet()
                it.reserveEmissions(fresh)
                it.completeEmissions(fresh, successful = false)
            }
            assertFalse(BreadcrumbContext.restore(rows.getValue("session")).emitted(emission))
        } finally { htx.close() }
    }

    @Test fun unknownReadsAndFailedTransactionsDoNotAllocateContextCapacity() = runTest {
        val contexts = BreadcrumbElement({ BreadcrumbContext({ 0L }) }, capacity = 1)
        val host = BreadcrumbModelHost(mux(), contexts)
        repeat(3) { index ->
            val unknown = host.handle(request(path = "/v1/breadcrumbs?context=unknown-$index", method = HtxMethod.GET))!!
            assertEquals(400, unknown.status)
        }
        assertEquals(400, host.handle(request("""{"context":"unknown","id":"call-1","presence":1}""", "/v1/breadcrumbs"))!!.status)
        assertFailsWith<IllegalStateException> { contexts.transact("failed") { error("failed admission") } }
        contexts.transact("retained") { it.put("read", "evidence") }
        assertEquals(200, host.handle(request(path = "/v1/breadcrumbs?context=retained", method = HtxMethod.GET))!!.status)
        assertEquals(200, host.handle(request(path = "/v1/breadcrumbs?context=retained", method = HtxMethod.DELETE))!!.status)
        assertEquals(400, host.handle(request(path = "/v1/breadcrumbs?context=retained", method = HtxMethod.GET))!!.status)
        assertEquals(0, contexts.transact("replacement") { it.snapshot().size })
    }
}
