package borg.trikeshed.module

import borg.trikeshed.couch.CouchDatabase
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.dag.ReteProductionRegistry
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.CasStore
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Minimal proxy-ctor module for the gate: claims one exact path, answers a marker. */
class EchoModule : ForgeModule {
    override val id: String = "echo"
    override suspend fun open(ctx: ModuleContext): ModuleHandle {
        ctx.routes.claim(id, "/api/echo") { _, _, _, _ ->
            JvmKanbanServer.HttpResponse(200, """{"echo":true,"module":"echo"}""")
        }
        return object : ModuleHandle {
            override val id: String = "echo"
            override fun describe(): Map<String, Any?> = mapOf("kind" to "test-echo")
            override suspend fun drain() {}
            override suspend fun close() {}
        }
    }
}

/** Shadows /api/board — the precedence proof (a claimed path beats the PORTABLE tier + built-ins). */
class BoardShadowModule : ForgeModule {
    override val id: String = "board-shadow"
    override suspend fun open(ctx: ModuleContext): ModuleHandle {
        ctx.routes.claim(id, "/api/board") { _, _, _, _ ->
            JvmKanbanServer.HttpResponse(200, """{"shadow":"board"}""")
        }
        return object : ModuleHandle {
            override val id: String = "board-shadow"
            override fun describe(): Map<String, Any?> = emptyMap()
            override suspend fun drain() {}
            override suspend fun close() {}
        }
    }
}

class ModuleSupervisorTest {

    private fun newContext(routes: ModuleRouteRegistry): ModuleContext {
        val cas = CasStore.inMemory()
        val couchStore = CouchStoreFactory.casBacked(cas)
        return ModuleContext(
            couchDb = CouchDatabase("module-test", couchStore, cas),
            rete = ReteNetwork(),
            productions = ReteProductionRegistry(),
            beliefBag = null,          // null-bag context MUST open (garnish, not load-bearing)
            turnReview = null,
            blackboard = ConfixBlackboard.empty(),
            casStore = cas,
            attachments = CouchAttachmentGateway(couchStore, cas),
            routes = routes,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            clock = { 0L },
            stateDir = File(System.getProperty("java.io.tmpdir"), "module-test"),
        )
    }

    private fun get(server: JvmKanbanServer, path: String): JvmKanbanServer.HttpResponse = runBlocking {
        server.routeHttp("GET $path HTTP/1.1\r\nHost: t\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
    }

    @Test
    fun attachByFqcnAnswersViaRouteHttp_andDetachUnclaims() = runBlocking {
        val routes = ModuleRouteRegistry()
        val server = JvmKanbanServer(moduleRoutes = routes)
        val supervisor = ModuleSupervisor(newContext(routes))

        val handle = supervisor.attach("borg.trikeshed.module.EchoModule")
        assertEquals("echo", handle.id)
        val resp = get(server, "/api/echo")
        assertEquals(200, resp.status)
        assertTrue(resp.body.contains("\"echo\":true"), "module route must answer: ${resp.body}")

        assertTrue(supervisor.detach("echo"))
        assertEquals(404, get(server, "/api/echo").status)
        assertTrue(routes.paths().isEmpty(), "detach must release every claim")
    }

    @Test
    fun doubleAttachRejected() = runBlocking {
        val routes = ModuleRouteRegistry()
        val supervisor = ModuleSupervisor(newContext(routes))
        supervisor.attach(EchoModule())
        assertFailsWith<IllegalArgumentException> { supervisor.attach(EchoModule()) }
        // the failed second attach must not have disturbed the first module's claim
        assertEquals(mapOf("/api/echo" to "echo"), routes.paths())
    }

    @Test
    fun unknownClassRefusedCleanly() = runBlocking {
        val routes = ModuleRouteRegistry()
        val supervisor = ModuleSupervisor(newContext(routes))
        assertFailsWith<IllegalArgumentException> { supervisor.attach("borg.trikeshed.module.NoSuchModule") }
        assertTrue(routes.paths().isEmpty())
    }

    @Test
    fun claimedBoardShadowsForgeRoutes_untouchedPathsUnaffected() = runBlocking {
        val routes = ModuleRouteRegistry()
        val server = JvmKanbanServer(moduleRoutes = routes)
        val supervisor = ModuleSupervisor(newContext(routes))

        // Regression baseline: health + static asset answer WITHOUT any module attached.
        assertEquals(200, get(server, "/api/health").status)
        val cssBefore = get(server, "/styles.css")

        supervisor.attach(BoardShadowModule())
        val board = get(server, "/api/board")
        assertEquals(200, board.status)
        assertTrue(board.body.contains("\"shadow\":\"board\""), "claimed /api/board must shadow the fossil parser: ${board.body}")

        // The shadow must not leak: health + static assets still served by the built-ins.
        val health = get(server, "/api/health")
        assertEquals(200, health.status)
        assertTrue(health.body.contains("\"server\":\"kanban\""))
        assertEquals(cssBefore.status, get(server, "/styles.css").status)

        supervisor.detach("board-shadow")
        // Board falls back to the pre-module behavior (whatever the fossil path answers — just not our marker).
        assertTrue(!get(server, "/api/board").body.contains("\"shadow\""))
    }

    @Test
    fun registryRefusesGreedyClaims() {
        val routes = ModuleRouteRegistry()
        assertFailsWith<IllegalArgumentException> { routes.claim("m", "/") { _, _, _, _ -> null } }
        assertFailsWith<IllegalArgumentException> { routes.claim("m", "/api/") { _, _, _, _ -> null } }
        assertFailsWith<IllegalArgumentException> { routes.claim("m", "/api/x/") { _, _, _, _ -> null } }
        assertFailsWith<IllegalArgumentException> { routes.claim("m", "/styles.css") { _, _, _, _ -> null } }
        routes.claim("m", "/api/x") { _, _, _, _ -> null }
        assertFailsWith<IllegalArgumentException> { routes.claim("other", "/api/x") { _, _, _, _ -> null } }
    }
}
