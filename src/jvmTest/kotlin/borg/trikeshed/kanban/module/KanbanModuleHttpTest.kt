package borg.trikeshed.kanban.module

import borg.trikeshed.couch.CouchDatabase
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.dag.ReteProductionRegistry
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.CasStore
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
        assertEquals(7, arr(empty["columns"]).size, "canonical seven columns on an empty store")
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
