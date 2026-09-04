package borg.trikeshed.kanban.module

import borg.trikeshed.couch.CouchDatabase
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.dag.ReteProductionRegistry
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.CasStore
import borg.trikeshed.kanban.BoardCol
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.module.ModuleRouteRegistry
import borg.trikeshed.module.ModuleSupervisor
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KanbanModuleHttpTest {

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "kanban-module-$name-${System.nanoTime()}").apply { mkdirs() }

    private fun newContext(routes: ModuleRouteRegistry, stateDir: File, cas: CasStore): ModuleContext {
        val couchStore = CouchStoreFactory.casBacked(cas)
        return ModuleContext(
            couchDb = CouchDatabase("kanban-module-test", couchStore, cas),
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
        JsonSupport.parse(resp.body) as Map<String, Any?>

    /** JsonSupport backends return JSON arrays as Array or List — normalize for assertions. */
    private fun arr(v: Any?): List<*> = when (v) {
        is List<*> -> v
        is Array<*> -> v.toList()
        else -> error("not an array: $v")
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
            JsonSupport.stringify(
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
            val resp = post(server, "/api/mcp", JsonSupport.stringify(doc))
            assertEquals(200, resp.status, resp.body.take(200))
            val parsed = json(resp)
            assertTrue(parsed["error"] == null, "$method failed: ${parsed["error"]}")
            @Suppress("UNCHECKED_CAST")
            return parsed["result"] as Map<String, Any?>
        }

        fun readResource(server: JvmKanbanServer, uri: String): Map<*, *> {
            val contents = arr(rpc(server, "resources/read", mapOf("uri" to uri))["contents"])
            return JsonSupport.parse((contents.first() as Map<*, *>)["text"] as String) as Map<*, *>
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
