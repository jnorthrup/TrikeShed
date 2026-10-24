package borg.trikeshed.agent.modelmux

import borg.trikeshed.htx.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.agent.keymux.KeyMux
import borg.trikeshed.agent.keymux.TestKeySource
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Semaphore
import kotlin.test.*

class ModelMuxSwarmTest {
    private fun mux() = ModelMux(KeyMux { bind("llm.fixture.key", TestKeySource(value = "fixture-key")) }) {
        model("fixture", setOf("chat", "tools"), provider = "fixture", baseUrl = "https://fixture.invalid/v1")
    }

    private fun input(content: String) = confixDoc(jsonOf(mapOf(
        "model" to "fixture", "max_tokens" to 73, "temperature" to 0.7,
        "messages" to listOf(
            mapOf("role" to "user", "content" to "Use my context"),
            mapOf("role" to "assistant", "content" to null,
                "tool_calls" to listOf(mapOf("id" to "evidence", "type" to "function",
                    "function" to mapOf("name" to "lookup", "arguments" to "{}")))),
            mapOf("role" to "tool", "tool_call_id" to "evidence", "content" to content),
        ),
    )))

    private fun response(body: String) = HtxResponse(200, ByteSeries(body.encodeToByteArray()))
    private val retrieval = """{"choices":[{"message":{"role":"assistant","content":null,"reasoning_content":"retained envelope","tool_calls":[{"id":"retrieve","type":"function","function":{"name":"breadcrumb","arguments":"{\"id\":\"evidence\",\"presence\":1}"}}]}}],"usage":{"prompt_tokens":3,"completion_tokens":2}}"""
    private val answer = """{"choices":[{"message":{"role":"assistant","content":"done"}}],"usage":{"prompt_tokens":3,"completion_tokens":2}}"""

    @Test fun seatsKeepInputsControlsMemoryAndReceiptsThroughToolFanIn() = runTest {
        val sent = mutableListOf<ConfixDoc>()
        val htx = openHtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                val doc = confixDoc(request.body.toArray().decodeToString())
                sent.add(doc)
                assertEquals(73, (doc.value("max_tokens") as Number).toInt())
                assertEquals(0.7, doc.value("temperature"))
                assertEquals("Use my context", doc.value("messages", 1, "content"))
                val secondTurn = doc.getAt("messages")!!.kids.size == 6
                if (secondTurn) assertEquals("retained envelope", doc.value("messages", 4, "reasoning_content"))
                return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED,
                    request = request, response = response(if (secondTurn) answer else retrieval)))
            }
        })
        val memory = BreadcrumbElement()
        val receipts = mutableListOf<MuxCallRecord>()
        try {
            val results = withContext(htx) {
                mux().swarm(s_[
                    MuxAgentRequest("seat-a", input("alpha evidence"), MuxCallContext(1, 9, onFinished = { receipts.add(it) })),
                    MuxAgentRequest("seat-b", input("beta evidence"), MuxCallContext(2, 9, onFinished = { receipts.add(it) })),
                ], memory, parallelism = 2, capacity = 1)
            }
            assertEquals(2, results.size)
            assertTrue(results.view.all { it.isSuccess })
            assertEquals(4, sent.size)
            assertEquals(2, receipts.count { it.conversationId == 1L && it.turnId == 9L })
            assertEquals(2, receipts.count { it.conversationId == 2L && it.turnId == 9L })
            assertEquals("alpha evidence", memory.transact("seat-a") { it.lookup(BreadcrumbId("evidence"))!!.content })
            assertEquals("beta evidence", memory.transact("seat-b") { it.lookup(BreadcrumbId("evidence"))!!.content })
            for (doc in sent.filter { it.getAt("messages")!!.kids.size == 6 }) {
                val original = doc.value("messages", 3, "content") as String
                val retrieved = doc.value("messages", 5, "content") as String
                assertEquals(original.contains("alpha evidence"), retrieved.contains("alpha evidence"))
                assertEquals(original.contains("beta evidence"), retrieved.contains("beta evidence"))
            }
        } finally { htx.close() }
    }

    @Test fun cancellationDrainsTheAdmittedCallAndStopsFurtherTurns() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        var collected: MuxAgentResult? = null
        val htx = openHtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                calls++
                started.complete(Unit)
                release.await()
                return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED,
                    request = request, response = response(retrieval)))
            }
        })
        try {
            val job = launch(htx) {
                mux().swarm(s_[MuxAgentRequest("seat", input("retained"))], BreadcrumbElement(),
                    onResult = { _, result -> collected = result })
            }
            started.await()
            job.cancel()
            runCurrent()
            assertFalse(job.isCompleted, "The admitted provider call still belongs to the swarm")
            release.complete(Unit)
            job.join()
            assertEquals(1, calls)
            assertIs<CancellationException>(collected?.exceptionOrNull())
        } finally { htx.close() }
    }

    @Test fun callbackFailureIsCollectedWithoutDroppingOtherSeats() = runTest {
        val htx = openHtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest) =
                HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, request = request, response = response(answer)))
        })
        try {
            val results = withContext(htx) {
                mux().swarm(s_[MuxAgentRequest("a", input("a")), MuxAgentRequest("b", input("b")), MuxAgentRequest("c", input("c"))],
                    BreadcrumbElement(), parallelism = 1, capacity = 1,
                    onStart = {
                        if (it == 0) throw IllegalArgumentException("seat refused")
                        if (it == 1) throw CancellationException("seat cancelled")
                    })
            }
            assertIs<IllegalArgumentException>(results[0].exceptionOrNull())
            assertIs<CancellationException>(results[1].exceptionOrNull())
            assertTrue(results[2].isSuccess)
        } finally { htx.close() }
    }

    @Test fun cancellationReleasesASeatWaitingForAdmission() = runTest {
        val permits = Semaphore(1, acquiredPermits = 1)
        var collected: MuxAgentResult? = null
        val job = launch {
            mux().swarm(s_[MuxAgentRequest("waiting", input("context"))], BreadcrumbElement(), permits = permits,
                onStart = { error("No permit was available") }, onResult = { _, result -> collected = result })
        }
        runCurrent()
        job.cancelAndJoin()
        assertIs<CancellationException>(collected?.exceptionOrNull())
        assertEquals(0, permits.availablePermits, "The swarm must not release another caller's permit")
    }

    @Test fun activeEnvironmentFitsItsBudgetWithoutTruncatingRetainedOriginals() = runTest {
        val memory = BreadcrumbElement()
        val content = "large retained evidence ".repeat(2000)
        memory.transact("seat") { it.put(BreadcrumbId("large"), "previous:user", content) }
        val htx = openHtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                val doc = confixDoc(request.body.toArray().decodeToString())
                val environment = (doc.value("messages", 0, "content") as String).substringAfter('\n')
                assertTrue(environment.length <= 512)
                val records = confixDoc(environment)
                assertEquals("large", records.value(0, "id"))
                assertTrue((records.value(0, "content") as String).length < content.length)
                return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED,
                    request = request, response = response(answer)))
            }
        })
        try {
            val results = withContext(htx) {
                mux().swarm(s_[MuxAgentRequest("seat", input("new input"), contextChars = 512)], memory)
            }
            assertTrue(results[0].isSuccess)
            assertEquals(content, memory.transact("seat") { it.lookup(BreadcrumbId("large"))!!.content })
        } finally { htx.close() }
    }
}
