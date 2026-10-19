package borg.trikeshed.module

import borg.trikeshed.couch.Couch
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse

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
    private fun ownedModule(moduleId: String, draining: suspend () -> Unit = {}, closing: suspend () -> Unit = {}): ForgeModule =
        object : ForgeModule {
            override val id = moduleId
            override suspend fun open(ctx: ModuleContext): ModuleHandle {
                ctx.routes.claim(id, "/api/$id") { _, _, _, _ -> JvmKanbanServer.HttpResponse(200, id) }
                return object : ModuleHandle {
                    override val id = moduleId
                    override fun describe(): Map<String, Any?> = emptyMap()
                    override suspend fun drain() = draining()
                    override suspend fun close() = closing()
                }
            }
        }

    @Test fun failedDrainStillClosesReleasesAndReportsFailureBeforeNameReuse() = runBlocking {
        val routes = ModuleRouteRegistry()
        val context = newContext(routes)
        val events = mutableListOf<String>()
        val supervisor = ModuleSupervisor(context, receipt = { event, _, _ -> events += event })
        try {
            supervisor.attach(ownedModule("failed", draining = { throw IllegalArgumentException("drain failure") },
                closing = { throw IllegalStateException("close failure") }))
            val failure = assertFailsWith<IllegalStateException> { supervisor.detach("failed") }
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any {
                it is IllegalArgumentException && it.message == "drain failure"
            })
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any {
                it.suppressedExceptions.any { other -> other.message == "close failure" }
            })
            assertTrue(routes.paths().isEmpty())
            assertEquals("module/detach-failed", events.last())
            assertFalse(events.contains("module/detached"))
            supervisor.attach(ownedModule("failed"))
            assertTrue(supervisor.detach("failed"))
        } finally { context.scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun retirementReservesTheNameAndDrainAllJoinsItDespiteWaiterCancellation() = runBlocking {
        val routes = ModuleRouteRegistry()
        val context = newContext(routes)
        val supervisor = ModuleSupervisor(context)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var closed = false
        supervisor.attach(ownedModule("held", draining = { entered.complete(Unit); release.await() }, closing = { closed = true }))
        val first = async(start = CoroutineStart.UNDISPATCHED) { supervisor.detach("held") }
        entered.await()
        val all = async(start = CoroutineStart.UNDISPATCHED) { supervisor.drainAll() }
        try {
            assertFalse(all.isCompleted)
            first.cancel()
            assertFailsWith<IllegalArgumentException> { supervisor.attach(ownedModule("held")) }
            assertEquals(mapOf("/api/held" to "held"), routes.paths())
            release.complete(Unit)
            withTimeout(1_000) { all.await(); first.join() }
            assertTrue(closed)
            assertTrue(routes.paths().isEmpty())
            supervisor.attach(ownedModule("held"))
            assertEquals(mapOf("/api/held" to "held"), routes.paths())
            assertTrue(supervisor.detach("held"))
        } finally { release.complete(Unit); first.cancelAndJoin(); all.cancelAndJoin(); context.scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun drainAllContinuesEveryModuleAndRetainsEachFailure() = runBlocking {
        val routes = ModuleRouteRegistry()
        val context = newContext(routes)
        val supervisor = ModuleSupervisor(context)
        val closed = mutableListOf<String>()
        try {
            for (id in listOf("one", "two", "three")) supervisor.attach(ownedModule(id,
                draining = { if (id != "three") error("$id drain") }, closing = { closed += id }))
            val failure = assertFailsWith<IllegalStateException> { supervisor.drainAll() }
            assertEquals(listOf("one", "two", "three"), closed)
            assertTrue(routes.paths().isEmpty())
            assertTrue(supervisor.describeAll().isEmpty())
            val messages = generateSequence<Throwable>(failure) { it.cause }.flatMap { cause ->
                sequenceOf(cause.message) + cause.suppressedExceptions.asSequence().flatMap { suppressed ->
                    generateSequence<Throwable>(suppressed) { it.cause }.map { it.message }
                }
            }.toList()
            assertTrue("one drain" in messages)
            assertTrue("two drain" in messages)
        } finally { context.scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun retirementCannotWaitOnItsOwnCompletion() = runBlocking {
        val routes = ModuleRouteRegistry()
        val context = newContext(routes)
        val supervisor = ModuleSupervisor(context)
        try {
            supervisor.attach(ownedModule("recursive", draining = {
                assertFailsWith<IllegalStateException> { supervisor.detach("recursive") }
            }))
            assertTrue(withTimeout(1_000) { supervisor.detach("recursive") })
            assertTrue(routes.paths().isEmpty())
        } finally { context.scope.coroutineContext.job.cancelAndJoin() }
    }

    private fun newContext(routes: ModuleRouteRegistry): ModuleContext {
        val cas = CasStore.inMemory()
        val couchStore = CouchStoreFactory.casBacked(cas)
        return ModuleContext(
            couch = Couch("module-test", couchStore, cas),
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
