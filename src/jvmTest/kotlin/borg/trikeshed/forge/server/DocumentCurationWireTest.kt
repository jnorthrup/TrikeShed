package borg.trikeshed.forge.server

import borg.trikeshed.couch.*
import borg.trikeshed.dag.*
import borg.trikeshed.docs.DocumentSurface
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.graal.subvm.harness.DocumentModelFixture
import borg.trikeshed.hermes.HermesConfigDefault
import borg.trikeshed.htx.openHtxElement
import borg.trikeshed.job.*
import borg.trikeshed.kanban.module.KanbanModule
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.memory.*
import borg.trikeshed.modelmux.*
import borg.trikeshed.module.*
import borg.trikeshed.narsese.*
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.userspace.nio.channels.spi.EgressAllowlist
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import borg.trikeshed.userspace.reactor.MuxReactorElement
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.agent.keymux.KeyMux
import kotlinx.coroutines.*
import borg.trikeshed.agent.modelmux.ModelMux
import borg.trikeshed.agent.modelmux.MuxActivity
import borg.trikeshed.agent.modelmux.MuxCallContext
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.*

class DocumentCurationWireTest {
    @org.junit.jupiter.api.io.TempDir
    lateinit var directory: Path

    class Rig(scope: CoroutineScope, root: java.io.File, enabled: Boolean,
              mux: ModelMux? = null, muxContext: CoroutineContext = EmptyCoroutineContext,
              providedBrain: BrainClient? = null) {
        var writes = 0
        val cas = object : CasStore() {
            override fun put(bytes: ByteArray): ContentId { writes++; return super.put(bytes) }
        }
        val store = CouchStoreFactory.casBacked(cas)
        val attachments = CouchAttachmentGateway(store, cas)
        val files = JvmFileOperations()
        val bag = if (enabled) BeliefBagElement(cas = cas, parentJob = scope.coroutineContext[Job]) else null
        val ctx = ModuleContext(Couch("documents", store, cas), ReteNetwork(), ReteProductionRegistry(),
            bag, null, ConfixBlackboard.empty(), cas, attachments, ModuleRouteRegistry(),
            CoroutineScope(scope.coroutineContext + files + muxContext), { 123L }, root,
            muxContext = muxContext, programLoader = { name ->
                panels.route("GET", "/api/panels/$name", "", null)?.takeIf { it.status == 200 }
                    ?.let { LcncProgramConfix.fromJson(name, it.body) }
            })
        val keys = mux?.keyMux ?: KeyMux {}
        val brain = providedBrain ?: object : BrainClient(apiKey = "fixture", keyMux = keys, modelMux = mux ?: ModelMux(keys) {}) {
            override fun endpointSummaries() = emptyList<EndpointSpec>()
            override fun lastModel(): String? = if (mux == null) null else "fixture"
        }
        val scopes = ProjectScopes(files, attachments,
            CouchIndexBridge(attachments, MemoryIndexLayer(MemoryStore(cas, store))), cas, bag)
        val panels = PatchWire(brain, scopes, attachments = attachments)

        suspend fun assertUnavailable(reason: String) {
            val before = writes
            val board = ctx.blackboard.snapshot()
            assertNull(DocumentCurationWire.open(ctx, brain, panels))
            val read = assertNotNull(ctx.routes.match("/api/documents"), "An absent prerequisite must not erase the document API")
            val response = assertNotNull(read.route("GET", "/api/documents", "", null))
            assertEquals(200, response.status)
            val state = reifyMap(response.body)
            assertFalse(DocumentSurface.curationAvailable(state))
            assertContains(assertNotNull(DocumentSurface.curationError(state)), reason)
            assertFalse(state.containsKey("records"), "Unavailable storage is not an empty collection")
            assertNull(DocumentSurface.curationDestination(state))
            val html = DocumentSurface.curationHtml(false, null, emptyList(), "draft", "keep source",
                null, DocumentSurface.curationError(state), false, null, null)
            assertContains(html, reason)
            assertContains(html, "data-curate-submit=\"1\" disabled")
            assertContains(html, "keep source")
            for ((method, path, expected) in listOf(
                Triple("GET", "/api/documents?cid=sha256:missing", 400),
                Triple("POST", "/api/documents/curate", 503),
                Triple("DELETE", "/api/documents", 405),
            )) {
                val refusal = assertNotNull(ctx.routes.match(path)!!.route(method, path, "not even JSON", null))
                assertEquals(expected, refusal.status)
            }
            assertFalse(ctx.lcncRunners.containsKey(DocumentCurationLegos.CURATE))
            assertFalse(ctx.lcncRunners.containsKey(DocumentCurationLegos.SHEET))
            val after = ctx.blackboard.snapshot()
            val readiness = "lcnc/readiness/${DocumentCurationLegos.CURATE}"
            assertEquals(board.values, after.values - readiness)
            assertEquals(board.provenance, after.provenance - readiness)
            val status = assertIs<Map<*, *>>(after.values[readiness])
            assertEquals(false, status["available"])
            assertContains(assertIs<String>(status["reason"]), reason)
            assertEquals(listOf("belief bag", "CoreNLP guest module", "configured reachable chat model"), status["prerequisites"])
            assertEquals(before, writes, "Readiness and refusals must not retain a source or run anything")
            assertFalse(java.io.File(ctx.stateDir, "documents").exists())
        }
    }

    @Test fun disabledBeliefBagReportsTheStartupPrerequisiteWithoutBindingFakeRunners(): Unit = runBlocking {
        val rig = Rig(this, directory.toFile(), false)
        rig.assertUnavailable("--belief-bag")
    }

    @Test fun absentModelReportsConfigurationWithoutOpeningDocumentStorage(): Unit = runBlocking {
        val owner = SupervisorJob(coroutineContext[Job])
        val rig = Rig(CoroutineScope(coroutineContext + owner), directory.toFile(), true)
        try { rig.assertUnavailable("model") } finally { owner.cancelAndJoin() }
    }

    @Test fun reducedHeadhunterStartupReleasesDocumentStatusOnDrain(): Unit = runBlocking {
        val owner = SupervisorJob(coroutineContext[Job])
        val rig = Rig(CoroutineScope(coroutineContext + owner), directory.toFile(), false)
        var headhunter: HeadhunterWire? = null
        try {
            headhunter = HeadhunterWire.open(rig.ctx, rig.brain, rig.panels)
            assertNotNull(rig.ctx.routes.match("/api/documents"))
            assertNotNull(rig.ctx.routes.match("/api/documents/curate"))
            headhunter.drain()
            assertNull(rig.ctx.routes.match("/api/documents"))
            assertNull(rig.ctx.routes.match("/api/documents/curate"))
        } finally {
            try { headhunter?.drain(); headhunter?.drainCuration(); headhunter?.close() }
            finally { owner.cancelAndJoin() }
        }
    }

    /** Default runs remain deterministic. Live verification is explicit and cannot fail over to another destination. */
    suspend fun withCurationModel(text: String, body: suspend (BrainClient, CoroutineContext) -> Unit) {
        val profile = SystemOperations.default.getenv("TRIKESHED_CURATION_PROFILE")
        if (profile == null) {
            val session = DocumentModelFixture.open { prompt ->
                assertEquals(text, (reifyMap(prompt.messages[1].content)["source"] as Map<*, *>)["text"])
                ModelResponse("""{"format":"TRIPLET_JSON","triplets":[]}""", ModelUsage(3, 2, 5), "fixture", "fixture")
            }
            try {
                val mux = assertNotNull(session.reactor.modelMux())
                body(BrainClient(apiKey = "fixture", model = DocumentModelFixture.MODEL_ID,
                    keyMux = mux.keyMux, modelMux = mux), session.htx + session.reactor)
            } finally { session.close() }
            return
        }
        val expectedBase = requireNotNull(SystemOperations.default.getenv("TRIKESHED_CURATION_BASE")) {
            "Set TRIKESHED_CURATION_BASE explicitly before live verification"
        }
        val expectedModel = requireNotNull(SystemOperations.default.getenv("TRIKESHED_CURATION_MODEL")) {
            "Set TRIKESHED_CURATION_MODEL explicitly before live verification"
        }
        val keys = borg.trikeshed.agent.keymux.operatorKeyMux(JvmFileOperations(), hermesHome = profile)
        val resolved = HermesConfigDefault.resolve(profile, keyLane = { provider, field ->
            keys.get("llm.$provider.$field")
        })
        // Never render Launch (contains a key), or an unavailable config diagnostic that may quote a raw scalar.
        val launch = (resolved as? HermesConfigDefault.Outcome.Pinned)?.launch
            ?: error("The explicit Hermes profile does not resolve a usable model, endpoint and credential")
        assertEquals("chat_completions", launch.apiMode)
        assertEquals(expectedBase, launch.baseUrl, "The configured destination changed; no request was sent")
        assertEquals(expectedModel, launch.model, "The configured model changed; no request was sent")
        val brain = BrainClient(apiKey = launch.apiKey, base = launch.baseUrl, model = launch.model)
        val mux = brain.modelMux()
        EgressAllowlist.allowUrl(launch.baseUrl)
        val htx = openHtxElement(parentJob = currentCoroutineContext()[Job])
        val reactor = MuxReactorElement(parentJob = currentCoroutineContext()[Job],
            keyMux = mux.keyMux, modelMuxProvider = { mux })
        try {
            reactor.open()
            body(brain, htx + reactor)
        } finally {
            try { reactor.close() } finally { htx.close() }
        }
    }

    @org.junit.jupiter.api.Timeout(value = 5, unit = java.util.concurrent.TimeUnit.MINUTES)
    @Test fun enabledStartupAndReadsAreInertUntilAnExplicitCanonicalCurationRequest(): Unit = runBlocking {
        val text = "Acme pays Beta. If Beta receives payment, Beta retains records."
        withCurationModel(text) { brain, transport ->
            val receipts = CopyOnWriteArrayList<ModelResponseReceipt>()
            val activity = MuxActivity()
            val muxContext = transport + MuxCallContext(activity = activity, onReceipt = { receipts.add(it) })
            val modelId = assertNotNull(brain.modelMux().defaultModel ?: brain.lastModel())
            val owner = SupervisorJob(coroutineContext[Job])
            val rig = Rig(CoroutineScope(coroutineContext + owner), directory.toFile(), true,
                brain.modelMux(), muxContext, brain)
            var documents: DocumentCurationWire? = null
            var module: ModuleHandle? = null
            try {
                rig.bag!!.open()
                module = KanbanModule().open(rig.ctx)
                documents = assertNotNull(DocumentCurationWire.open(rig.ctx, rig.brain, rig.panels))
                assertTrue(documents.available, "This integration uses the installed CoreNLP guest, not a parser fixture")
                suspend fun request(method: String, path: String, body: String = ""): Map<String, Any?> {
                    val response = assertNotNull(rig.ctx.routes.match(path)!!.route(method, path, body, null))
                    assertEquals(200, response.status, response.body)
                    return reifyMap(response.body)
                }
                val before = request("GET", "/api/documents")
                assertTrue(DocumentSurface.curationAvailable(before))
                assertNull(DocumentSurface.curationError(before))
                assertEquals(modelId, DocumentSurface.curationDestination(before)?.model)
                assertTrue(DocumentSurface.curationList(before).isEmpty())
                assertEquals(0, receipts.size, "Startup and inspection do not authorize a model call")
                assertTrue(activity.snapshot().isEmpty(), "No call may be pending at startup either")
                assertTrue(rig.ctx.lcncRunners.containsKey(DocumentCurationLegos.CURATE))
                val result = request("POST", "/api/documents/curate", jsonOf(mapOf("source" to mapOf(
                    "text" to text, "name" to "Synthetic startup contract", "correlation" to "startup-contract"))))
                assertEquals(1, receipts.size)
                val cid = ContentId(assertIs<String>(result["receiptCid"]))
                val record = assertNotNull(documents.curator.record(cid))
                assertEquals(text, record.source.text)
                assertEquals("edu.stanford.nlp.pipeline.StanfordCoreNLP", record.nlp?.metadata?.processor)
                val receipt = assertNotNull(record.receipt)
                println(jsonOf(mapOf("curationModelReceipt" to mapOf(
                    "mode" to if (SystemOperations.default.getenv("TRIKESHED_CURATION_PROFILE") == null) "fixture" else "live",
                    "model" to modelId, "destination" to DocumentSurface.curationDestination(before)?.url,
                    "receiptCid" to cid.value, "modelReceiptId" to receipt.receiptId,
                    "httpStatus" to receipt.httpStatus, "cachedHit" to receipt.cachedHit,
                    "latencyMs" to receipt.latencyMs, "errorClass" to receipt.errorClass,
                    "inputTokens" to receipt.inputTokens, "outputTokens" to receipt.outputTokens,
                ))))
                assertEquals(receipts.single(), receipt, "Retain this call's exact receipt, not shared lastReceipt")
                assertEquals(modelId, receipt.modelId)
                assertEquals("chat", receipt.action)
                assertEquals(200, receipt.httpStatus, "ModelMux failure: ${receipt.errorClass}")
                assertFalse(receipt.cachedHit)
                assertNull(receipt.errorClass)
                assertTrue(assertNotNull(record.model).content.isNotBlank())
                assertEquals(DocumentToolStatus.SUCCESS,
                    record.toolReceipts.view.single { it.stage == DocumentToolStage.MODEL }.status,
                    "A completed LCNC run alone does not prove a successful model stage")
                assertEquals(cid, ContentId.of(assertNotNull(rig.cas.get(cid))))
                assertEquals("completed", (result["run"] as Map<*, *>)["status"])
                assertTrue(DocumentCurationFacts.ruleCandidates(record).size > 0)
                assertTrue((result["tuples"] as List<*>).isNotEmpty())
                val writes = rig.writes
                val workspace = borg.trikeshed.kanban.module.WorkspaceService(rig.ctx)
                for (retained in listOf(cid) + record.toolReceipts.view.map { assertNotNull(it.receiptCid) }) {
                    val inspection = workspace.route("POST", jsonOf(mapOf("source" to mapOf(
                        "kind" to "cas", "cid" to retained.value, "syntax" to "CBOR"))))
                    assertEquals(200, inspection.status, inspection.body)
                    val facet = reifyMap(inspection.body)["confix"] as Map<*, *>
                    assertEquals(retained.value, facet["cid"])
                    assertEquals("CBOR", facet["syntax"])
                }
                assertEquals(cid.value, request("GET", "/api/documents?cid=${cid.value}")["receiptCid"])
                assertEquals(listOf(cid.value), DocumentSurface.curationList(request("GET", "/api/documents")).map { it.receiptCid })
                request("GET", DocumentCurationLegos.sheetHref(cid))
                assertEquals(1, receipts.size, "Retained record and cursor reads do not repeat curation")
                assertEquals(1, activity.snapshot().size, "Reads must not start an unfinished call")
                assertEquals(writes, rig.writes)
                println(jsonOf(mapOf("curationVerification" to mapOf(
                    "mode" to if (SystemOperations.default.getenv("TRIKESHED_CURATION_PROFILE") == null) "fixture" else "live",
                    "model" to modelId, "destination" to DocumentSurface.curationDestination(before)?.url,
                    "receiptCid" to cid.value, "modelReceiptId" to receipt.receiptId,
                    "httpStatus" to receipt.httpStatus, "cachedHit" to receipt.cachedHit,
                    "inputTokens" to receipt.inputTokens, "outputTokens" to receipt.outputTokens,
                    "ruleCandidates" to DocumentCurationFacts.ruleCandidates(record).size,
                    "tuples" to (result["tuples"] as List<*>).size, "calls" to receipts.size,
                    "readsAddedWrites" to (rig.writes - writes),
                ))))
            } finally {
                try {
                    documents?.stopAdmission()
                    module?.drain()
                    documents?.drain()
                    module?.close()
                    documents?.close()
                    rig.bag?.drain()
                    rig.bag?.close()
                } finally { owner.cancelAndJoin() }
            }
        }
    }

    @org.junit.jupiter.api.Timeout(value = 5, unit = java.util.concurrent.TimeUnit.MINUTES)
    @Test fun failedModelStageRemainsVisibleThroughTheCanonicalRecordAndReadback(): Unit = runBlocking {
        val owner = SupervisorJob(coroutineContext[Job])
        var calls = 0
        val htx = openHtxElement(parentJob = owner, routeService = object : borg.trikeshed.htx.HtxRouteService {
            override suspend fun exchange(state: borg.trikeshed.htx.HtxExchangeState,
                                          request: borg.trikeshed.htx.HtxRequest): borg.trikeshed.htx.HtxExchangeResult {
                calls++
                return borg.trikeshed.htx.HtxExchangeResult(state.copy(
                    lifecycle = borg.trikeshed.htx.HtxExchangeLifecycle.RESPONDED, request = request,
                    response = borg.trikeshed.htx.HtxResponse(503, body = ByteSeries("{}".encodeToByteArray()))))
            }
        })
        val keys = KeyMux { bind("llm.fixture.key", borg.trikeshed.agent.keymux.TestKeySource()) }
        val mux = ModelMux(keys) { model("fixture", caps = setOf("chat")); defaultModel("fixture") }
        val reactor = MuxReactorElement(parentJob = owner, keyMux = keys, modelMuxProvider = { mux })
        val rig = Rig(CoroutineScope(coroutineContext + owner), directory.toFile(), true, mux, htx + reactor)
        var documents: DocumentCurationWire? = null
        var module: ModuleHandle? = null
        try {
            reactor.open()
            rig.bag!!.open()
            module = KanbanModule().open(rig.ctx)
            documents = assertNotNull(DocumentCurationWire.open(rig.ctx, rig.brain, rig.panels))
            assertTrue(documents.available)
            val response = assertNotNull(rig.ctx.routes.match("/api/documents/curate")!!.route("POST",
                "/api/documents/curate", jsonOf(mapOf("source" to mapOf("name" to "Synthetic model failure",
                    "text" to "Acme pays Beta."))), null))
            assertEquals(200, response.status, "The attempt is retained even when its model stage fails")
            val result = reifyMap(response.body)
            val cid = ContentId(result["receiptCid"] as String)
            val record = assertNotNull(documents.curator.record(cid))
            assertEquals("completed", (result["run"] as Map<*, *>)["status"])
            assertEquals(DocumentToolStatus.FAILURE, record.toolReceipts.view.single { it.stage == DocumentToolStage.MODEL }.status)
            assertEquals(503, assertNotNull(record.receipt).httpStatus)
            val rendered = DocumentSurface.curationResultHtml(assertNotNull(DocumentSurface.curationResult(result)))
            assertContains(rendered, "MODEL · failed")
            assertContains(rendered, "NLP · succeeded")
            assertContains(rendered, "GROUNDING · skipped")
            assertContains(rendered, "HTTP 503")
            assertContains(rendered, record.receipt.receiptId)
            for (receipt in record.toolReceipts.view) assertContains(rendered, assertNotNull(receipt.receiptCid).value)
            val writes = rig.writes
            val read = assertNotNull(rig.ctx.routes.match("/api/documents")!!.route("GET",
                "/api/documents?cid=${cid.value}", "", null))
            assertEquals(200, read.status)
            assertEquals(rendered, DocumentSurface.curationResultHtml(assertNotNull(DocumentSurface.curationResult(reifyMap(read.body)))))
            if (SystemOperations.default.getenv("TRIKESHED_CURATION_UI") == "1")
                retainedDocumentBrowser(rig.ctx, cid) { mapOf("modelCalls" to calls, "additionalWrites" to rig.writes - writes) }
            assertEquals(1, calls, "Rendering and readback must not retry the model")
            assertEquals(writes, rig.writes, "Readback must not alter the saved attempt")
        } finally {
            try {
                documents?.stopAdmission(); module?.drain(); documents?.drain()
                module?.close(); documents?.close(); rig.bag?.drain(); rig.bag?.close()
            } finally {
                try { reactor.close() } finally { try { htx.close() } finally { owner.cancelAndJoin() } }
            }
        }
    }
}
