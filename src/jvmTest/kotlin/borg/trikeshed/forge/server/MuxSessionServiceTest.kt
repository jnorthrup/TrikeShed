package borg.trikeshed.forge.server

import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.htx.*
import borg.trikeshed.job.CasStore
import borg.trikeshed.jules.BrainClient
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.toArray
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import kotlinx.coroutines.*
import keymux.FixedKeySource
import keymux.KeyMux
import modelmux.ModelMux
import kotlin.test.*

class MuxSessionServiceTest {
    private suspend fun fixture(scope: CoroutineScope, handler: suspend (HtxRequest) -> HtxResponse): Triple<MuxSessionService, BrainClient, CouchAttachmentGateway> {
        val htx = openHtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult =
                HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, request = request, response = handler(request)))
        })
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { withContext(NonCancellable) { htx.close() } }
        }
        val keys = KeyMux { bind("llm.test-a.key", FixedKeySource("private-test-key")); bind("llm.test-b.key", FixedKeySource("private-test-key")) }
        val mux = ModelMux(keys) { model("test-a", caps = setOf("chat", "conflict-resolve")); model("test-b", caps = setOf("chat", "conflict-resolve")) }
        val brain = object : BrainClient(apiKey = "private-test-key", model = "test-a", keyMux = keys, modelMux = mux) {
            override fun endpointSummaries() = listOf(EndpointSpec("a", "TEST_A_KEY", "https://test.invalid", "test-a"), EndpointSpec("b", "TEST_B_KEY", "https://test.invalid", "test-b"))
        }
        val cas = CasStore.inMemory()
        val gateway = CouchAttachmentGateway(CouchStoreFactory.casBacked(cas), cas)
        return Triple(MuxSessionService(brain, gateway, scope, htx), brain, gateway)
    }

    private suspend fun post(service: MuxSessionService, path: String, body: Map<String, Any?>) =
        service.route("POST", "/api/mux/sessions$path", JsonSupport.stringify(body))!!
    private suspend fun create(service: MuxSessionService): Long =
        (JsonSupport.parseMap(post(service, "", mapOf("title" to "Test")).body)["id"] as Number).toLong()
    private suspend fun get(service: MuxSessionService, id: Long) =
        JsonSupport.parseMap(service.route("GET", "/api/mux/sessions?id=$id", "")!!.body)
    private suspend fun finished(service: MuxSessionService, id: Long): Map<String, Any?> = withTimeout(5000) {
        var value = get(service, id)
        while (value["status"] in setOf("queued", "running", "joining") || (value["calls"] as List<*>).none { (it as Map<*, *>)["turnId"] == value["turnId"] }) { delay(10); value = get(service, id) }
        value
    }
    private fun answer(text: String = "answer") = HtxResponse(200, ByteSeries("""{"choices":[{"message":{"content":"$text"}}],"usage":{"prompt_tokens":10,"completion_tokens":5}}""".encodeToByteArray()))

    @Test fun conversationHistoryPersistsAndForkDoesNotDoubleCharge(): Unit = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val requests = mutableListOf<String>()
        try {
            val (service, brain, gateway) = fixture(scope) { requests.add(it.body.toArray().decodeToString()); answer() }
            val id = create(service)
            assertEquals(202, post(service, "/run", mapOf("id" to id, "prompt" to "first", "models" to listOf("test-a"))).status)
            finished(service, id)
            assertEquals(202, post(service, "/run", mapOf("id" to id, "prompt" to "second", "models" to listOf("test-a"))).status)
            val result = finished(service, id)
            assertEquals(4, (result["messages"] as List<*>).size)
            assertTrue(requests.last().contains("first") && requests.last().contains("answer") && requests.last().contains("second"))
            val restored = MuxSessionService(brain, gateway, scope, kotlin.coroutines.EmptyCoroutineContext)
            assertEquals(result, get(restored, id))
            val fork = JsonSupport.parseMap(post(service, "/fork", mapOf("id" to id)).body)
            assertEquals(4, (fork["messages"] as List<*>).size)
            assertEquals(emptyList<Any>(), fork["calls"])
            val activity = service.route("GET", "/api/mux/activity", "")!!.body
            assertFalse(activity.contains("private-test-key"))
            assertEquals(2, (JsonSupport.parseMap(activity)["calls"] as List<*>).size)
        } finally { scope.coroutineContext[Job]?.cancelAndJoin() }
    }

    @Test fun fanoutIsConcurrentAndEachBranchOwnsItsReceipt(): Unit = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val bothStarted = CompletableDeferred<Unit>()
        var inFlight = 0
        try {
            val (service, _, _) = fixture(scope) {
                if (++inFlight == 2) bothStarted.complete(Unit)
                withTimeout(3000) { bothStarted.await() }
                answer()
            }
            val id = create(service)
            val started = post(service, "/run", mapOf("id" to id, "prompt" to "compare", "models" to listOf("test-a", "test-b"), "mode" to "synthesize"))
            assertEquals(202, started.status)
            val done = finished(service, id)
            assertEquals("completed", done["status"])
            val childIds = (done["children"] as List<*>).map { (it as Number).toLong() }
            assertEquals(2, childIds.size)
            for (child in childIds) {
                val detail = get(service, child)
                val receipt = (detail["calls"] as List<*>).single() as Map<*, *>
                assertEquals(child, (receipt["conversationId"] as Number).toLong())
                assertEquals(5, (receipt["outputTokens"] as Number).toInt())
            }
            assertEquals(1, (done["calls"] as List<*>).size, "parent owns only synthesis receipt")
        } finally { scope.coroutineContext[Job]?.cancelAndJoin() }
    }

    @Test fun cancellationStopsBranchesAndDuplicateRunsAreRejected(): Unit = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val started = CompletableDeferred<Unit>()
        try {
            val (service, _, _) = fixture(scope) { started.complete(Unit); awaitCancellation() }
            val id = create(service)
            val run = mapOf("id" to id, "prompt" to "cancel", "models" to listOf("test-a"))
            assertEquals(202, post(service, "/run", run).status)
            started.await()
            assertEquals(409, post(service, "/run", run).status)
            assertEquals(202, post(service, "/cancel", mapOf("id" to id)).status)
            val result = finished(service, id)
            assertEquals("cancelled", result["status"])
            assertEquals("cancelled", ((result["calls"] as List<*>).single() as Map<*, *>)["status"])
        } finally { scope.coroutineContext[Job]?.cancelAndJoin() }
    }

    @Test fun invalidConfigurationNeverStartsCalls(): Unit = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val (service, brain, _) = fixture(scope) { error("Must not dispatch") }
            val id = create(service)
            for (extra in listOf(mapOf("models" to listOf("unknown")), mapOf("maxTokens" to -1), mapOf("mode" to "synthesize", "models" to listOf("test-a")))) {
                assertEquals(400, post(service, "/run", mapOf("id" to id, "prompt" to "test") + extra).status)
            }
            assertTrue(brain.modelMux().activity.snapshot().isEmpty())
        } finally { scope.coroutineContext[Job]?.cancelAndJoin() }
    }

    @Test fun operatorCatalogRemainsAvailableBesidePinnedChat(): Unit = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val (_, catalogBrain, gateway) = fixture(scope) { error("Catalog inspection must not dispatch") }
            val pinned = BrainClient(apiKey = "private-test-key", model = "pinned-default")
            val service = MuxSessionService(pinned, gateway, scope, kotlin.coroutines.EmptyCoroutineContext, catalogProvider = { catalogBrain.modelMux() })
            val response = service.route("GET", "/api/mux/catalog", "")!!
            assertEquals(200, response.status)
            val rows = JsonSupport.parseMap(response.body)["models"] as List<*>
            assertEquals(setOf("test-a", "test-b"), rows.map { (it as Map<*, *>)["model"] }.toSet())
            assertTrue(rows.all { (it as Map<*, *>)["keyPresent"] == true })
            assertFalse(response.body.contains("private-test-key"))
            assertEquals("pinned-default", pinned.endpointSummaries().single().model)
        } finally { scope.coroutineContext[Job]?.cancelAndJoin() }
    }

    @Test fun catalogReportsConfiguredModelWithoutCallingProvider(): Unit = runBlocking {
        val keys = KeyMux { bind("llm.custom.key", FixedKeySource("private-test-key")) }
        val mux = ModelMux(keys) {
            model("configured", caps = setOf("chat"), provider = "custom")
            defaultModel("configured")
        }
        val brain = BrainClient(apiKey = "private-test-key", model = "previous")
        val service = MuxSessionService(brain, null, null, kotlin.coroutines.EmptyCoroutineContext, catalogProvider = { mux })
        val response = service.route("GET", "/api/mux/catalog", "")!!
        assertEquals(200, response.status)
        val body = JsonSupport.parseMap(response.body)
        assertEquals("configured", body["defaultModel"])
        val card = (body["models"] as List<*>).single() as Map<*, *>
        assertEquals("custom", card["name"])
        assertEquals(true, card["keyPresent"])
        assertEquals("llm.custom.key", card["envVar"])
        assertFalse(response.body.contains("private-test-key"))
        assertTrue(mux.activity.snapshot().isEmpty())
    }

    @Test fun diskCheckpointRestoresWithAFreshAttachmentGateway(): Unit = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val directory = withContext(Dispatchers.IO) { java.nio.file.Files.createTempDirectory("mux-checkpoint-").toFile() }
        try {
            val (_, brain, gateway) = fixture(scope) { error("Restoring must not dispatch") }
            val snapshot = java.io.File(directory, "sessions.json")
            val service = MuxSessionService(brain, gateway, scope, kotlin.coroutines.EmptyCoroutineContext, snapshotFile = snapshot)
            val id = create(service)
            assertEquals(200, post(service, "/update", mapOf("id" to id, "archived" to true)).status)
            val freshCas = CasStore.inMemory()
            val freshGateway = CouchAttachmentGateway(CouchStoreFactory.casBacked(freshCas), freshCas)
            val fresh = MuxSessionService(brain, freshGateway, scope, kotlin.coroutines.EmptyCoroutineContext, snapshotFile = snapshot)
            assertEquals(get(service, id), get(fresh, id))
            val interrupted = get(fresh, id) + mapOf("status" to "running", "archived" to false)
            withContext(Dispatchers.IO) {
                borg.trikeshed.userspace.nio.file.spi.JvmFileOperations().writeAtomically(snapshot.absolutePath, JsonSupport.stringify(listOf(interrupted)).encodeToByteArray())
            }
            val recovered = MuxSessionService(brain, freshGateway, scope, kotlin.coroutines.EmptyCoroutineContext, snapshotFile = snapshot)
            assertEquals("interrupted", get(recovered, id)["status"])
            assertTrue(brain.modelMux().activity.snapshot().isEmpty())
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            withContext(Dispatchers.IO) { directory.deleteRecursively() }
        }
    }
}
