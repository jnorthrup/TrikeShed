package borg.trikeshed.kanban.module

import borg.trikeshed.couch.CouchDatabase
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.dag.ReteProductionRegistry
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.CasStore
import borg.trikeshed.lcnc.LcncContracts
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lcnc.LcncProgram
import borg.trikeshed.lcnc.LcncWire
import borg.trikeshed.lib.toSeries
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

/**
 * Spec §7 route gate: `POST /api/lcnc/run {program}` executes a stored
 * two-level program (procedure → scope call → child) IN the daemon and
 * returns the outer `scope.out` values — imperative program execution as a
 * service. Also proves the offered scope presets run through the default
 * (presets-only) program loader with zero registered runners.
 */
class LcncRunProgramRouteTest {

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "lcnc-run-$name-${System.nanoTime()}").apply { mkdirs() }

    private fun newContext(
        routes: ModuleRouteRegistry,
        stateDir: File,
        cas: CasStore,
        programs: Map<String, LcncProgram> = emptyMap(),
    ): ModuleContext {
        val couchStore = CouchStoreFactory.casBacked(cas)
        return ModuleContext(
            couchDb = CouchDatabase("lcnc-run-test", couchStore, cas),
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
            // Mirrors production composition: explicit fixtures first, offered presets behind.
            programLoader = { name ->
                programs[name] ?: borg.trikeshed.lcnc.LcncPresets.all()[name]
                    ?.let { borg.trikeshed.lcnc.LcncProgramConfix.fromJson(name, it) }
            },
        )
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

    private fun program(name: String, nodes: List<LcncNode>, wires: List<LcncWire>) =
        LcncProgram(name, nodes.toSeries(), wires.toSeries())

    @Test
    fun runsAStoredTwoLevelProgramAndReturnsOuterScopeOuts(): Unit = runBlocking {
        val inner = program(
            "inner-shout",
            listOf(
                LcncNode("p1", LcncContracts.SCOPE_IN, params = mapOf("name" to "text")),
                LcncNode("p2", "test.upper"),
                LcncNode("p3", LcncContracts.SCOPE_OUT, params = mapOf("name" to "result")),
            ),
            listOf(
                LcncWire("p1", "value", "p2", "x"),
                LcncWire("p2", "y", "p3", "value"),
            ),
        )
        val outer = program(
            "outer",
            listOf(
                LcncNode("t1", LcncContracts.SCOPE_IN, params = mapOf("name" to "text")),
                LcncNode("s", LcncContracts.SCOPE, subprogram = "inner-shout"),
                LcncNode("r", LcncContracts.SCOPE_OUT, params = mapOf("name" to "result")),
            ),
            listOf(
                LcncWire("t1", "value", "s", "text"),
                LcncWire("s", "result", "r", "value"),
            ),
        )
        val routes = ModuleRouteRegistry()
        val server = JvmKanbanServer(moduleRoutes = routes)
        val ctx = newContext(routes, tempDir("two-level"), CasStore.inMemory(),
            programs = mapOf("outer" to outer, "inner-shout" to inner))
        ctx.lcncRunners["test.upper"] = LcncNodeRunner { _, inputs -> mapOf("y" to inputs["x"]?.toString()?.uppercase()) }
        val supervisor = ModuleSupervisor(ctx)
        supervisor.attach(KanbanModule())

        val resp = post(server, "/api/lcnc/run", """{"program":"outer","inputs":{"text":"hi"}}""")
        assertEquals(200, resp.status, resp.body)
        val body = json(resp)
        assertEquals(true, body["ok"])
        val returns = body["returns"] as Map<*, *>
        assertEquals("HI", returns["result"], "the outer scope.out value came back through the route: $body")

        val missing = post(server, "/api/lcnc/run", """{"program":"ghost"}""")
        assertEquals(404, missing.status, "an unknown program is a loud 404, not an empty run")
        supervisor.detach("kanban")
    }

    @Test
    fun offeredScopePresetRunsThroughTheDefaultPresetLoader(): Unit = runBlocking {
        // The default ModuleContext.programLoader resolves presets only —
        // preset-scope is the three-ring concentric document: the root
        // scope.in default rides one wire two rings deep and the yield climbs
        // out ring by ring; zero registered runners needed.
        val routes = ModuleRouteRegistry()
        val server = JvmKanbanServer(moduleRoutes = routes)
        val supervisor = ModuleSupervisor(newContext(routes, tempDir("preset"), CasStore.inMemory()))
        supervisor.attach(KanbanModule())

        val resp = post(server, "/api/lcnc/run", """{"program":"preset-scope"}""")
        assertEquals(200, resp.status, resp.body)
        val body = json(resp)
        val returns = body["returns"] as Map<*, *>
        assertEquals("hello", returns["result"],
            "the root default reached two rings deep and climbed all the way back out: $body")
        assertTrue((body["outputs"] as Map<*, *>).isNotEmpty(), "the per-node map is served beside the returns")
        supervisor.detach("kanban")
    }
}
