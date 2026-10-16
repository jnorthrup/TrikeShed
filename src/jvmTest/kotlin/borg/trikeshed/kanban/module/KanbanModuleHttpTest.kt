package borg.trikeshed.kanban.module

import borg.trikeshed.parse.reify
import borg.trikeshed.parse.jsonOf

import borg.trikeshed.couch.Couch
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.dag.ReteProductionRegistry
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.kanban.BoardCol
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.modelmux.ModelResponse
import borg.trikeshed.modelmux.ModelUsage
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.module.ModuleRouteRegistry
import borg.trikeshed.module.ModuleSupervisor
import borg.trikeshed.narsese.*
import borg.trikeshed.nlp.*
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KanbanModuleHttpTest {
    private class Cas : CasStore() {
        var puts = 0
        var missing: ContentId? = null
        override fun put(bytes: ByteArray): ContentId {
            puts++
            return super.put(bytes)
        }
        override fun get(cid: ContentId): ByteArray? = if (cid == missing) null else super.get(cid)
    }

    private fun tempDir(name: String): File =
        File(java.lang.System.getProperty("java.io.tmpdir"), "kanban-module-$name-${java.lang.System.nanoTime()}").apply { mkdirs() }

    private fun newContext(routes: ModuleRouteRegistry, stateDir: File, cas: CasStore): ModuleContext {
        val couchStore = CouchStoreFactory.casBacked(cas)
        return ModuleContext(
            couch = Couch("kanban-module-test", couchStore, cas),
            rete = ReteNetwork(),
            productions = ReteProductionRegistry(),
            beliefBag = null,
            turnReview = null,
            blackboard = ConfixBlackboard.empty(),
            casStore = cas,
            attachments = CouchAttachmentGateway(couchStore, cas),
            routes = routes,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            clock = { 1234L },
            stateDir = stateDir,
        )
    }

    private fun get(server: JvmKanbanServer, path: String): JvmKanbanServer.HttpResponse = runBlocking {
        server.routeHttp("GET $path HTTP/1.1\r\nHost: t\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
    }

    private fun post(server: JvmKanbanServer, path: String, body: String): JvmKanbanServer.HttpResponse = runBlocking {
        server.routeHttp(
            "POST $path HTTP/1.1\r\nHost: t\r\nContent-Type: application/json\r\n\r\n$body"
                .toByteArray(StandardCharsets.UTF_8),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun json(resp: JvmKanbanServer.HttpResponse): Map<String, Any?> =
        reify(resp.body) as Map<String, Any?>

    /** reify backends return JSON arrays as Array or List — normalize for assertions. */
    private fun arr(v: Any?): List<*> = when (v) {
        is List<*> -> v
        is Array<*> -> v.toList()
        else -> error("not an array: $v")
    }

    private fun retained(cas: CasStore): ContentId {
        val text = "Alex uses Java."
        val source = DocumentSource(cas.put("Original attachment bytes".encodeToByteArray()),
            cas.put(text.encodeToByteArray()), text, "review.txt", "text/plain", "review",
            mapOf("pages" to listOf("1", "2")))
        return cas.put(DocumentCuratorCodec.encode(DocumentCurationRecord(source,
            NlpDocument(text, s_[NlpSentence(0, 0, text.length,
                s_[NlpToken(1, 0, text.length, text, text, "NN", "O")], s_[NlpDependency(0, 1, "root")])]),
            ModelResponse("synthetic", ModelUsage(0, 0, 0), "fixture", "fixture"), "fixture",
            s_[DocumentProposal("synthetic", "Alex", "use", "Kotlin", 0.9, "Alex uses Kotlin.",
                0, 17, true, "asserted", s_["quote does not match UTF-16 span"])], emptySeriesOf(),
            instructions = "Retain exact quotations.\nPreserve the author's uncertainty.")))
    }

    private suspend fun documentTask(ctx: ModuleContext, receipt: ContentId): Map<*, *> {
        val output = ctx.lcncRunners.getValue(DocumentCurationTasks.TASKS).run(
            LcncNode("tasks", DocumentCurationTasks.TASKS), mapOf("receiptCid" to receipt.value))
        return arr(output["tasks"]).single() as Map<*, *>
    }

    private fun documentTaskPath(id: String): String =
        "/api/board?documentTask=" + URLEncoder.encode(id, StandardCharsets.UTF_8.name())

    private fun correctionDocument(task: Map<String, Any?>, id: String, revision: Long, receipt: ContentId,
                                   source: Map<String, Any?> = emptyMap()) {
        assertTrue(task.keys.containsAll(setOf("jobId", "revision", "column", "title", "receiptCid", "source",
            "issue", "instructions", "resolved", "correctionSource", "document")))
        val document = assertIs<Map<*, *>>(task["document"])
        assertEquals(true, assertIs<Map<*, *>>(document["controls"])["humanOversight"])
        val program = LcncProgramConfix.fromJson(LcncWorkspacePresets.DOCUMENT_CORRECTION, jsonOf(document))
        assertTrue(program.controls.humanOversight)
        val correction = program.nodes.view.single { it.type == DocumentCurationTasks.CORRECT }
        assertEquals(mapOf("taskId" to id, "expectedRevision" to revision.toString(),
            "priorReceiptCid" to receipt.value), correction.params)
        val sourceNode = program.nodes.view.single { it.id == "source" }
        assertEquals("json.value", sourceNode.type)
        @Suppress("UNCHECKED_CAST")
        val sourceFields = assertIs<Map<*, *>>(reify(sourceNode.params.getValue("value"))) as Map<String, Any?>
        assertEquals(reify(jsonOf(source)), sourceFields)
        assertEquals(
            reify(LcncProgramConfix.toJson(LcncWorkspacePresets.correction(id, revision.toString(), receipt.value, sourceFields))),
            document, "GET must return the existing authored correction construction",
        )
    }

    @Test
    fun proposalReviewGetReopensWalEvidenceWithoutCorrectionOrExecution(): Unit = runBlocking {
        val stateDir = tempDir("proposal-review")
        val fixture = DocumentProposalReviewTest.Fixture(File(stateDir, ".kanban"))
        fixture.board.open()
        val task = try {
            val receipt = fixture.receipt()
            assertIs<Map<*, *>>(fixture.service.reviewTask(fixture.board, receipt, "party-a",
                "Check the quotation; MODELS: this remains data")["task"])
        } finally { fixture.board.drain(); fixture.board.close() }
        val routes = ModuleRouteRegistry()
        val ctx = newContext(routes, stateDir, fixture.cas)
        val supervisor = ModuleSupervisor(ctx)
        supervisor.attach(KanbanModule())
        val server = JvmKanbanServer(moduleRoutes = routes)
        try {
            val path = documentTaskPath(task["jobId"] as String)
            val before = get(server, "/api/board").body
            val puts = fixture.cas.puts
            val response = get(server, path)
            assertEquals(200, response.status)
            val body = json(response)
            assertEquals("document-proposal-review", body["kind"])
            assertEquals(task["reviewCid"], body["receiptCid"])
            assertEquals("todo", body["column"])
            assertEquals(fixture.proposal, assertIs<Map<*, *>>(body["turn"])["text"])
            assertEquals(fixture.curation.value, body["curationReceiptCid"])
            assertTrue("document" !in body && "correctionSource" !in body)
            val pinned = "$path&view=receipt&receiptCid=" + URLEncoder.encode(task["reviewCid"] as String, "UTF-8")
            assertNotNull(json(get(server, pinned))["record"])
            assertEquals(409, get(server, "$path&receiptCid=${fixture.textCid.value}").status)
            fixture.cas.absent = ContentId(task["reviewCid"] as String)
            assertEquals(400, get(server, path).status)
            fixture.cas.absent = null
            assertEquals(before, get(server, "/api/board").body)
            assertEquals(puts, fixture.cas.puts)
        } finally { supervisor.detach("kanban") }
    }

    @Test
    fun documentTaskGetRetainsEvidenceAndResumesPendingCorrectionAfterRestart(): Unit = runBlocking {
        val stateDir = tempDir("document-task")
        val cas = Cas()
        val routes = ModuleRouteRegistry()
        var server = JvmKanbanServer(moduleRoutes = routes)
        var ctx = newContext(routes, stateDir, cas)
        var supervisor = ModuleSupervisor(ctx)
        supervisor.attach(KanbanModule())
        var curatorCalls = 0
        val curator = LcncNodeRunner { _, _ ->
            curatorCalls++
            error("GET must not execute the parser/model curation node")
        }
        ctx.lcncRunners[DocumentCurationLegos.CURATE] = curator
        try {
            val receipt = retained(cas)
            val record = DocumentCuratorCodec.decode(assertNotNull(cas.get(receipt)))
            val evidence = listOf(record.source.originalCid, record.source.extractedTextCid, receipt)
                .associateWith { assertNotNull(cas.get(it)) }
            val created = documentTask(ctx, receipt)
            val id = created["jobId"] as String
            val revision = (created["revision"] as Number).toLong()
            val path = documentTaskPath(id)
            assertTrue("%2F" in path, "document task identifiers must cross the encoded query boundary")
            val board = get(server, "/api/board").body
            val puts = cas.puts
            val response = get(server, path)
            assertEquals(200, response.status, response.body)
            val inspected = json(response)
            assertEquals(id, inspected["jobId"])
            assertEquals(revision, (inspected["revision"] as Number).toLong())
            assertEquals("todo", inspected["column"])
            assertEquals(created["title"], inspected["title"])
            assertEquals(receipt.value, inspected["receiptCid"])
            assertEquals(record.instructions, inspected["instructions"])
            assertEquals(false, inspected["resolved"])
            assertEquals(emptyMap<String, Any?>(), inspected["correctionSource"])
            val source = assertIs<Map<*, *>>(inspected["source"])
            assertEquals(record.source.originalCid.value, source["originalCid"])
            assertEquals(record.source.extractedTextCid.value, source["extractedTextCid"])
            assertEquals(record.source.name, source["name"])
            assertEquals(record.source.mediaType, source["mediaType"])
            assertEquals(record.source.correlation, source["correlation"])
            assertEquals(listOf("1", "2"), arr(assertIs<Map<*, *>>(source["metadata"])["pages"]))
            assertTrue("text" !in source)
            val issue = assertIs<Map<*, *>>(inspected["issue"])
            assertEquals("quotation", issue["kind"])
            assertEquals(listOf("Alex uses Kotlin."), arr(issue["quotes"]))
            assertEquals(listOf("quote does not match UTF-16 span"), arr(issue["reasons"]))
            correctionDocument(inspected, id, revision, receipt)
            assertEquals(response.body, get(server, path).body)
            assertTrue("record" !in inspected)
            val pin = "&receiptCid=" + URLEncoder.encode(receipt.value, StandardCharsets.UTF_8.name())
            val receiptResponse = get(server, "$path&view=receipt$pin")
            assertEquals(200, receiptResponse.status, receiptResponse.body)
            val receiptView = json(receiptResponse)
            assertEquals(inspected - "document", receiptView - "record")
            assertTrue("document" !in receiptView)
            assertEquals(reify(jsonOf(DocumentCuratorCodec.record(record))), receiptView["record"])
            val receiptSource = assertIs<Map<*, *>>(assertIs<Map<*, *>>(receiptView["record"])["source"])
            assertEquals(record.source.text, receiptSource["text"])
            assertEquals(record.source.originalCid.value, receiptSource["originalCid"])
            assertEquals(record.source.extractedTextCid.value, receiptSource["extractedTextCid"])
            assertEquals(receiptResponse.body, get(server, "$path&view=receipt").body)
            val pinned = get(server, path + pin)
            assertEquals(200, pinned.status, pinned.body)
            assertEquals(response.body, pinned.body)
            val wrongPin = "&receiptCid=" + URLEncoder.encode(record.source.originalCid.value, StandardCharsets.UTF_8.name())
            for (view in listOf("", "&view=receipt")) {
                val conflict = get(server, path + view + wrongPin)
                assertEquals(409, conflict.status, conflict.body)
                assertTrue("document" !in json(conflict) && "record" !in json(conflict))
            }
            val invalidView = get(server, "$path&view=unknown")
            assertEquals(400, invalidView.status, invalidView.body)
            assertTrue("document" !in json(invalidView) && "record" !in json(invalidView))
            assertEquals(board, get(server, "/api/board").body)
            assertEquals(puts, cas.puts)
            assertEquals(0, curatorCalls)

            val corrected = ctx.lcncRunners.getValue(DocumentCurationTasks.CORRECT).run(
                LcncNode("correction", DocumentCurationTasks.CORRECT, mapOf("taskId" to id,
                    "expectedRevision" to revision.toString(), "priorReceiptCid" to receipt.value)),
                mapOf("source" to mapOf("text" to "Alex uses Kotlin.")))
            val correctedSource = assertIs<Map<*, *>>(corrected["source"])
            val pending = mapOf("originalCid" to record.source.originalCid.value,
                "extractedTextCid" to correctedSource["extractedTextCid"], "name" to record.source.name,
                "mediaType" to record.source.mediaType, "correlation" to record.source.correlation,
                "metadata" to record.source.metadata)
            val correctedRevision = (corrected["revision"] as Number).toLong()
            val pendingBoard = get(server, "/api/board").body
            supervisor.detach("kanban")
            val replayRoutes = ModuleRouteRegistry()
            server = JvmKanbanServer(moduleRoutes = replayRoutes)
            ctx = newContext(replayRoutes, stateDir, cas)
            supervisor = ModuleSupervisor(ctx)
            supervisor.attach(KanbanModule())
            ctx.lcncRunners[DocumentCurationLegos.CURATE] = curator

            val replayPuts = cas.puts
            val resumedResponse = get(server, path)
            assertEquals(200, resumedResponse.status, resumedResponse.body)
            val resumed = json(resumedResponse)
            assertEquals(correctedRevision, (resumed["revision"] as Number).toLong())
            assertEquals(receipt.value, resumed["receiptCid"])
            assertEquals(record.instructions, resumed["instructions"])
            assertEquals("todo", resumed["column"])
            assertEquals(false, resumed["resolved"])
            assertEquals(record.source.extractedTextCid.value, assertIs<Map<*, *>>(resumed["source"])["extractedTextCid"])
            val pendingSource = assertIs<Map<*, *>>(resumed["correctionSource"])
            assertEquals(pending["extractedTextCid"], pendingSource["extractedTextCid"])
            assertEquals("Alex uses Kotlin.", assertNotNull(cas.get(ContentId(pendingSource["extractedTextCid"] as String))).decodeToString())
            assertTrue("text" !in pendingSource)
            correctionDocument(resumed, id, correctedRevision, receipt, pending)
            assertEquals(resumedResponse.body, get(server, path).body)
            val resumedReceiptResponse = get(server, "$path&view=receipt$pin")
            assertEquals(200, resumedReceiptResponse.status, resumedReceiptResponse.body)
            val resumedReceipt = json(resumedReceiptResponse)
            assertEquals(resumed - "document", resumedReceipt - "record")
            assertEquals(reify(jsonOf(DocumentCuratorCodec.record(record))), resumedReceipt["record"],
                "a pending correction must not rewrite the pinned prior receipt")
            assertEquals(pendingBoard, get(server, "/api/board").body)
            assertEquals(replayPuts, cas.puts)
            assertEquals(0, curatorCalls)
            for ((cid, bytes) in evidence) assertContentEquals(bytes, cas.get(cid))
        } finally { supervisor.detach("kanban") }
    }

    @Test
    fun documentTaskGetRefusesUnknownTasksOrdinaryCardsAndOtherMethods(): Unit = runBlocking {
        val cas = Cas()
        val routes = ModuleRouteRegistry()
        val server = JvmKanbanServer(moduleRoutes = routes)
        val supervisor = ModuleSupervisor(newContext(routes, tempDir("document-task-errors"), cas))
        supervisor.attach(KanbanModule())
        try {
            val id = "ordinary/task +&?#"
            val submitted = post(server, "/api/invoke", jsonOf(mapOf("commands" to listOf(
                mapOf("type" to "submit", "jobId" to id, "idempotencyKey" to "ordinary-task", "title" to "Ordinary card")))))
            assertEquals(202, submitted.status)
            assertEquals(1, (json(submitted)["accepted"] as Number).toInt())
            val board = get(server, "/api/board").body
            val puts = cas.puts
            val missing = get(server, documentTaskPath("absent/task +&?#"))
            assertEquals(404, missing.status, missing.body)
            assertTrue("document" !in json(missing))
            val ordinary = get(server, documentTaskPath(id))
            assertEquals(400, ordinary.status, ordinary.body)
            assertTrue("document" !in json(ordinary))
            for (method in listOf("POST", "PUT", "PATCH", "DELETE", "HEAD")) {
                val response = server.routeHttp("$method ${documentTaskPath(id)} HTTP/1.1\r\nHost: t\r\n\r\n"
                    .toByteArray(StandardCharsets.UTF_8))
                assertEquals(405, response.status, "$method: ${response.body}")
            }
            assertEquals(board, get(server, "/api/board").body)
            assertEquals(puts, cas.puts)
        } finally { supervisor.detach("kanban") }
    }

    @Test
    fun documentTaskGetRefusesMissingAndCorruptRetainedEvidenceWithoutMutation(): Unit = runBlocking {
        val cas = Cas()
        val routes = ModuleRouteRegistry()
        val server = JvmKanbanServer(moduleRoutes = routes)
        val ctx = newContext(routes, tempDir("document-task-content"), cas)
        val supervisor = ModuleSupervisor(ctx)
        supervisor.attach(KanbanModule())
        try {
            val receipt = retained(cas)
            val record = DocumentCuratorCodec.decode(assertNotNull(cas.get(receipt)))
            val task = documentTask(ctx, receipt)
            val path = documentTaskPath(task["jobId"] as String)
            assertEquals(200, get(server, path).status)
            val board = get(server, "/api/board").body
            for (cid in listOf(receipt, record.source.originalCid, record.source.extractedTextCid)) {
                val bytes = assertNotNull(cas.get(cid))
                for (corrupt in listOf(false, true)) {
                    val puts = cas.puts
                    if (corrupt) cas.corrupt(cid) else cas.missing = cid
                    try {
                        val response = get(server, path)
                        assertEquals(400, response.status, "${cid.value} (corrupt=$corrupt): ${response.body}")
                        assertTrue("document" !in json(response))
                        assertEquals(board, get(server, "/api/board").body)
                        assertEquals(puts, cas.puts)
                    } finally {
                        cas.missing = null
                        if (corrupt) assertEquals(cid, cas.put(bytes))
                    }
                }
            }
        } finally { supervisor.detach("kanban") }
    }

    @Test
    fun boardLifecycleEndToEnd_survivingRestart(): Unit = runBlocking {
        val stateDir = tempDir("e2e")
        val cas = CasStore.inMemory()

        // ── boot 1: attach, empty board, submit + move, import ──
        val routes1 = ModuleRouteRegistry()
        val server1 = JvmKanbanServer(moduleRoutes = routes1)
        val supervisor1 = ModuleSupervisor(newContext(routes1, stateDir, cas))
        supervisor1.attach(KanbanModule())

        val empty = json(get(server1, "/api/board"))
        assertEquals(BoardCol.entries.size, arr(empty["columns"]).size, "the closed vocabulary, every column, on an empty store")
        assertEquals(
            BoardCol.rendered.map { it.wire },
            arr(empty["columns"]).map { (it as Map<*, *>)["id"] },
            "/api/board lists columns in render order (review before done)",
        )
        assertEquals(0, arr(empty["items"]).size)

        val invoke = post(
            server1, "/api/invoke",
            jsonOf(
                mapOf(
                    "userId" to "jim",
                    "commands" to listOf(
                        mapOf("type" to "submit", "jobId" to "a", "idempotencyKey" to "k1", "title" to "First card"),
                        mapOf("type" to "move", "jobId" to "a", "idempotencyKey" to "k2", "expectedRevision" to 1, "toColumn" to "ready"),
                        mapOf("type" to "submit", "jobId" to "a", "idempotencyKey" to "k1"), // dup → rejected, visible
                    ),
                ),
            ),
        )
        assertEquals(202, invoke.status)
        val inv = json(invoke)
        assertEquals(2, (inv["accepted"] as Number).toInt())
        assertEquals(1, (inv["rejected"] as Number).toInt())
        val results = arr(inv["results"])
        assertTrue(((results[2] as Map<*, *>)["reason"] as String).contains("duplicate"))

        val board = json(get(server1, "/api/board"))
        val items = arr(board["items"])
        assertEquals(1, items.size)
        val card = items[0] as Map<*, *>
        assertEquals("ready", card["status"])
        assertEquals("First card", card["title"])
        assertEquals(2, (card["revision"] as Number).toInt())

        // section-less plan doc: partial import, never a 500
        val imp = post(server1, "/api/board/import", "# Plan without work packages\n\n- do the thing\n- do the other thing\nprose line\n")
        assertEquals(200, imp.status)
        assertEquals(2, (json(imp)["imported"] as Number).toInt())
        assertEquals(3, arr(json(get(server1, "/api/board"))["items"]).size)
        val boardJson1 = get(server1, "/api/board").body

        // ── detach: /api/board falls to the ForgeRoutes fallback — degraded 200, NEVER 500 ──
        supervisor1.detach("kanban")
        val degraded = get(server1, "/api/board")
        assertEquals(200, degraded.status, "the 500 is dead: fallback must degrade, got ${degraded.status}: ${degraded.body.take(120)}")

        // ── boot 2 (same stateDir): the WAL is the state — byte-identical board ──
        val routes2 = ModuleRouteRegistry()
        val server2 = JvmKanbanServer(moduleRoutes = routes2)
        val supervisor2 = ModuleSupervisor(newContext(routes2, stateDir, cas))
        supervisor2.attach(KanbanModule())
        assertEquals(boardJson1, get(server2, "/api/board").body, "restart must rebuild the identical board from the WAL")
        supervisor2.detach("kanban")
    }

    /**
     * KMFSM-006: MCP is mounted in the daemon's own lifecycle, reached over the
     * real HTTP route rather than by calling the handler directly — and the
     * board an MCP client writes is still there, through the same lens, after a
     * restart. That last clause is the whole durability claim: an agent's card
     * is not a session artifact.
     */
    @Test
    fun mcpIsMountedOnTheDaemonAndItsBoardSurvivesRestart(): Unit = runBlocking {
        val stateDir = tempDir("mcp")
        val cas = CasStore.inMemory()
        var rpcId = 0
        fun rpc(server: JvmKanbanServer, method: String, params: Map<String, Any?>? = null): Map<String, Any?> {
            val doc = buildMap<String, Any?> {
                put("jsonrpc", "2.0"); put("id", ++rpcId); put("method", method)
                params?.let { put("params", it) }
            }
            val resp = post(server, "/api/mcp", jsonOf(doc))
            assertEquals(200, resp.status, resp.body.take(200))
            val parsed = json(resp)
            assertTrue(parsed["error"] == null, "$method failed: ${parsed["error"]}")
            @Suppress("UNCHECKED_CAST")
            return parsed["result"] as Map<String, Any?>
        }

        fun readResource(server: JvmKanbanServer, uri: String): Map<*, *> {
            val contents = arr(rpc(server, "resources/read", mapOf("uri" to uri))["contents"])
            return reify((contents.first() as Map<*, *>)["text"] as String) as Map<*, *>
        }

        // ── boot 1: an MCP client discovers the board and puts a card on it ──
        val routes1 = ModuleRouteRegistry()
        val server1 = JvmKanbanServer(moduleRoutes = routes1)
        val supervisor1 = ModuleSupervisor(newContext(routes1, stateDir, cas))
        supervisor1.attach(KanbanModule())

        // The unauthenticated server card: what a human gets for typing the URL.
        val card = json(get(server1, "/api/mcp"))
        assertEquals("oroboros-lcnc-kanban", card["server"])
        assertTrue(arr(card["tools"]).contains("kanban.submit"))

        val handshake = rpc(server1, "initialize", mapOf("protocolVersion" to "2025-06-18"))
        assertEquals("2025-06-18", handshake["protocolVersion"])
        assertEquals(202, post(server1, "/api/mcp", """{"jsonrpc":"2.0","method":"notifications/initialized"}""").status)

        val tools = arr(rpc(server1, "tools/list")["tools"]).map { (it as Map<*, *>)["name"] }
        assertEquals(listOf("kanban.submit", "kanban.move"), tools)

        val submitted = rpc(
            server1, "tools/call",
            mapOf(
                "name" to "kanban.submit",
                "arguments" to mapOf("title" to "Written by an agent", "tags" to listOf("mcp"), "owner" to "agent"),
            ),
        )
        assertEquals(false, submitted["isError"])
        @Suppress("UNCHECKED_CAST")
        val verdict = submitted["structuredContent"] as Map<String, Any?>
        val jobId = verdict["jobId"] as String
        assertTrue((verdict["cid"] as String).isNotBlank(), "a committed write must carry its CAS receipt")

        // The very same card is visible on the ordinary board route — one board,
        // two lenses, not an MCP-private sidecar.
        val onBoard = arr(json(get(server1, "/api/board"))["items"]).map { it as Map<*, *> }
        assertEquals(listOf(jobId), onBoard.map { it["id"] })
        assertEquals("Written by an agent", onBoard.single()["title"])

        val moved = rpc(
            server1, "tools/call",
            mapOf(
                "name" to "kanban.move",
                "arguments" to mapOf(
                    "jobId" to jobId, "toColumn" to "running",
                    "expectedRevision" to (verdict["revision"] as Number).toLong(),
                ),
            ),
        )
        assertEquals(false, moved["isError"])
        supervisor1.detach("kanban")

        // ── boot 2, same state dir: the WAL is the state ──
        val routes2 = ModuleRouteRegistry()
        val server2 = JvmKanbanServer(moduleRoutes = routes2)
        val supervisor2 = ModuleSupervisor(newContext(routes2, stateDir, cas))
        supervisor2.attach(KanbanModule())

        val afterRestart = readResource(server2, "oroboros://lcnc/kanban/cards/$jobId")
        assertEquals("running", afterRestart["status"], "the agent's card must survive the restart")
        assertEquals(listOf("mcp"), arr(afterRestart["tags"]))
        assertEquals("agent", afterRestart["owner"])

        // And its receipt reference resolves after replay — rebuilt from the
        // board, and honest that the committing command's cid is not indexed.
        val receipt = readResource(server2, afterRestart["receiptResource"] as String)
        assertEquals(jobId, receipt["jobId"])
        assertEquals("replay", receipt["source"])
        supervisor2.detach("kanban")
    }

    /**
     * The streamable-HTTP transport detail that decides whether `claude mcp add
     * --transport http …` actually works, rather than merely looking like it does.
     *
     * A client may GET the endpoint to open a server-initiated SSE stream. This
     * server offers none — which is what `resources.subscribe: false` advertises
     * — and the transport requires a server without that stream to answer 405.
     * Handing back the human-readable server card instead gives a client
     * `application/json` where it expects `text/event-stream`: a hang or a parse
     * error rather than a clean refusal. So the route negotiates on Accept, and
     * both halves of that are pinned here.
     */
    @Test
    fun theMcpGetRefusesAnEventStreamButStillGreetsCurl(): Unit = runBlocking {
        val routes = ModuleRouteRegistry()
        val server = JvmKanbanServer(moduleRoutes = routes)
        val supervisor = ModuleSupervisor(newContext(routes, tempDir("sse"), CasStore.inMemory()))
        supervisor.attach(KanbanModule())

        suspend fun getWith(accept: String?): JvmKanbanServer.HttpResponse {
            val head = if (accept == null) "" else "Accept: $accept\r\n"
            return server.routeHttp(
                "GET /api/mcp HTTP/1.1\r\nHost: t\r\n$head\r\n".toByteArray(StandardCharsets.UTF_8),
            )
        }

        assertEquals(405, getWith("text/event-stream").status, "a client opening an SSE stream must get a clean 405")
        assertEquals(
            405,
            getWith("application/json, text/event-stream").status,
            "a compound Accept that includes text/event-stream is still a stream request",
        )
        // A human with curl, and a client that only wants a document, still get the card.
        assertEquals(200, getWith(null).status)
        assertEquals(200, getWith("application/json").status)
        assertEquals("oroboros-lcnc-kanban", json(getWith(null))["server"])
        supervisor.detach("kanban")
    }

    /** Phase 6: the EXACT shapes the updated script.js emits (+New prompt → submit; card click → move). */
    @Test
    fun updatedPwaPayloadsRoundTrip(): Unit = runBlocking {
        val routes = ModuleRouteRegistry()
        val server = JvmKanbanServer(moduleRoutes = routes)
        val supervisor = ModuleSupervisor(newContext(routes, tempDir("pwa"), CasStore.inMemory()))
        supervisor.attach(KanbanModule())

        // + New in column: queueBoardCommand({type:'submit', jobId, title}) + '#ui#' key
        val submit = post(
            server, "/api/invoke",
            """{"userId":"jim","commands":[{"type":"submit","jobId":"card-abc123","title":"Ship the module","idempotencyKey":"card-abc123#ui#1724600000000"}]}""",
        )
        assertEquals(202, submit.status)
        assertEquals(1, (json(submit)["accepted"] as Number).toInt())

        // card click: queueBoardCommand({type:'move', jobId, expectedRevision, toColumn})
        val move = post(
            server, "/api/invoke",
            """{"userId":"jim","commands":[{"type":"move","jobId":"card-abc123","expectedRevision":1,"toColumn":"running","idempotencyKey":"card-abc123#ui#1724600001000"}]}""",
        )
        assertEquals(1, (json(move)["accepted"] as Number).toInt())

        val board = json(get(server, "/api/board"))
        val card = arr(board["items"]).map { it as Map<*, *> }.first { it["id"] == "card-abc123" }
        assertEquals("running", card["status"])
        assertEquals("Ship the module", card["title"])
        assertEquals(2, (card["revision"] as Number).toInt())
        // the hydrate watermark the script polls on
        assertTrue((board["sequence"] as Number).toLong() >= 2L)
        supervisor.detach("kanban")
    }
}
