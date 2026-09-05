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
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
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

    @Test
    fun invocationBindingsAndEffectiveConstructionReachDurableReceipt(): Unit = runBlocking {
        val p = program("factory-args", listOf(
            LcncNode("input", "scope.in", mapOf("name" to "construction")),
            LcncNode("factory", "ccek.incarnate"),
            LcncNode("out", "scope.out", mapOf("name" to "instance")),
        ), listOf(LcncWire("input", "value", "factory", "args?"), LcncWire("factory", "node", "out", "value")))
        val routes = ModuleRouteRegistry()
        val server = JvmKanbanServer(moduleRoutes = routes)
        val ctx = newContext(routes, tempDir("factory-args"), CasStore.inMemory(), mapOf(p.name to p))
        ctx.lcncRunners.putAll(borg.trikeshed.lcnc.CcekNodes.registry(borg.trikeshed.lcnc.CcekSeams.live(ctx.scope)))
        val supervisor = ModuleSupervisor(ctx)
        supervisor.attach(KanbanModule())
        try {
            val request = """{"program":"factory-args","inputs":{"construction":{"title":"route-instance","maxConcurrency":3,"record":true}}}"""
            val response = post(server, "/api/lcnc/run", request)
            assertEquals(200, response.status, response.body)
            val result = json(response)
            val instance = (result["returns"] as Map<*, *>)["instance"] as Map<*, *>
            assertEquals(3, (instance["maxConcurrency"] as Number).toInt())
            val bindings = result["bindings"] as List<*>
            assertEquals("invocation", (bindings.single() as Map<*, *>)["source"])
            val receipt = ctx.blackboard.get("lcnc/run/${result["runId"]}") as Map<*, *>
            assertEquals(result["bindings"], JsonSupport.parse(JsonSupport.stringify(receipt["bindings"])))
            assertEquals(result["inputs"], receipt["inputs"])
            val conflict = post(server, "/api/lcnc/run", request.replace("Concurrency\":3", "Concurrency\":4"))
            assertEquals(400, conflict.status, conflict.body)
            assertTrue(json(conflict)["error"].toString().contains("incarnation_conflict"))
            val malformed = post(server, "/api/lcnc/run", """{"program":"factory-args","inputs":[]}""")
            assertEquals(400, malformed.status)
            assertEquals("inputs_must_be_object", json(malformed)["error"])
        } finally {
            supervisor.drainAll()
            ctx.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

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

    private suspend fun post(server: JvmKanbanServer, path: String, body: String): JvmKanbanServer.HttpResponse =
        server.routeHttp(
            "POST $path HTTP/1.1\r\nHost: t\r\nContent-Type: application/json\r\n\r\n$body"
                .toByteArray(StandardCharsets.UTF_8),
        )

    @Suppress("UNCHECKED_CAST")
    private fun json(resp: JvmKanbanServer.HttpResponse): Map<String, Any?> =
        JsonSupport.parse(resp.body) as Map<String, Any?>

    private fun program(name: String, nodes: List<LcncNode>, wires: List<LcncWire>) =
        LcncProgram(name, nodes.toSeries(), wires.toSeries())

    @Test
    fun shakeRouteConfirmsParentAndRefusesUnknownSelection(): Unit = runBlocking {
        val routes = ModuleRouteRegistry()
        val server = JvmKanbanServer(moduleRoutes = routes)
        val ctx = newContext(routes, tempDir("selected-shake"), CasStore.inMemory())
        val supervisor = ModuleSupervisor(ctx)
        supervisor.attach(KanbanModule())
        try {
            val document = borg.trikeshed.lcnc.LcncPresets.all().getValue("preset-scope")
            for (parent in listOf("r2", "missing", "n0")) {
                val response = post(server, "/api/lcnc/treeshake", """{"program":$document,"options":{"parentId":"$parent"}}""")
                assertEquals(if (parent == "r2") 200 else 400, response.status, response.body)
                if (parent == "r2") {
                    assertEquals("r2", json(response)["parentId"])
                    assertTrue((json(response)["made"] as List<*>).isEmpty())
                    assertTrue((json(response)["verdicts"] as List<*>).all { (it as Map<*, *>)["nodeId"] in setOf("b", "p") })
                } else assertEquals("bad_selection", json(response)["error"])
            }
        } finally { supervisor.detach("kanban") }
    }

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
        val receipt = ctx.blackboard.get("lcnc/run/${body["runId"]}") as Map<*, *>
        assertEquals("completed", receipt["status"])
        assertEquals("lcnc/program/outer", receipt["programKey"])
        assertTrue((receipt["sequence"] as Number).toLong() > 0)
        assertTrue((receipt["programVersions"] as Map<*, *>).keys.containsAll(listOf("outer", "inner-shout")))
        val bytes = ctx.casStore.get(borg.trikeshed.job.ContentId(receipt["programCid"].toString()))!!
        assertEquals(borg.trikeshed.job.ContentId.of(bytes).value, receipt["programCid"])

        val missing = post(server, "/api/lcnc/run", """{"program":"ghost"}""")
        assertEquals(404, missing.status, "an unknown program is a loud 404, not an empty run")
        supervisor.detach("kanban")
    }

    @Test
    fun runsAnInlineDocumentWithANestedRingAndSurfacesUnknownTypes(): Unit = runBlocking {
        // The panels canvas posts a scope's children as an inline document —
        // {name, document, inputs} — and the ring runs under the same
        // LcncScopeFrame nesting as a stored program: scope.in binds from
        // inputs through the frame chain (two rings deep), work runs, the
        // warm base comes back per direct-child id.
        val routes = ModuleRouteRegistry()
        val server = JvmKanbanServer(moduleRoutes = routes)
        val ctx = newContext(routes, tempDir("inline"), CasStore.inMemory())
        ctx.lcncRunners["test.upper"] = LcncNodeRunner { _, inputs -> mapOf("y" to inputs["x"]?.toString()?.uppercase()) }
        val supervisor = ModuleSupervisor(ctx)
        supervisor.attach(KanbanModule())

        val doc = """{"nodes":[
            {"id":"a","type":"scope.in","params":{"name":"text"}},
            {"id":"b","type":"test.upper"},
            {"id":"r","type":"scope","children":[
                {"id":"ri","type":"scope.in","params":{"name":"text"}},
                {"id":"ro","type":"scope.out","params":{"name":"result"}}]},
            {"id":"c","type":"scope.out","params":{"name":"result"}}],
          "wires":[{"from":["a","value"],"to":["b","x"]},
                   {"from":["ri","value"],"to":["ro","value"]},
                   {"from":["b","y"],"to":["c","value"]}]}"""
        val resp = post(server, "/api/lcnc/run", """{"name":"ring:n7","document":$doc,"inputs":{"text":"hi"}}""")
        assertEquals(200, resp.status, resp.body)
        val body = json(resp)
        assertEquals(true, body["ok"])
        assertEquals("ring:n7", body["program"], "the inline label rides back for the canvas to reconcile")
        assertEquals("HI", (body["returns"] as Map<*, *>)["result"], "the yield climbed out: $body")
        val outputs = body["outputs"] as Map<*, *>
        assertTrue("b" in outputs, "warm base: direct children paint by id: $outputs")
        assertTrue("r" in outputs, "the nested ring's gathered yield lands on its own id: $outputs")

        // Rings hold daemon-runnable types only: a client-only type FED inside
        // the ring is a loud error on the ring, never a silent flat sweep.
        val bad = post(server, "/api/lcnc/run",
            """{"document":{"nodes":[
                {"id":"a","type":"scope.in","params":{"name":"text","default":"x"}},
                {"id":"d","type":"display"}],
              "wires":[{"from":["a","value"],"to":["d","x"]}]},"inputs":{}}""")
        assertEquals(400, bad.status, bad.body)
        assertTrue("display" in bad.body, "LcncUnknownNodeType surfaces the offending type: ${bad.body}")

        val garbage = post(server, "/api/lcnc/run", """{"document":"not a program"}""")
        assertEquals(400, garbage.status, "a malformed document is a loud 400: ${garbage.body}")
        supervisor.detach("kanban")
    }

    @Test
    fun offeredScopePresetRunsThroughTheDefaultPresetLoader(): Unit = runBlocking {
        // The default ModuleContext.programLoader resolves presets only —
        // Map and named scope bindings need no registered runners.
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

    @Test fun inlineValidationCannotBorrowANamedProgramsCleanEntry() = runBlocking {
        val routes = ModuleRouteRegistry()
        val server = JvmKanbanServer(moduleRoutes = routes)
        val ctx = newContext(routes, tempDir("inline-validation"), CasStore.inMemory())
        val supervisor = ModuleSupervisor(ctx)
        supervisor.attach(KanbanModule())
        try {
            val named = ctx.blackboard.get("lcnc/program/preset-scope")
            val response = post(server, "/api/lcnc/run", """{"name":"preset-scope","document":{"nodes":[
                {"id":"a","type":"scope.in","params":{"name":"x","default":"hello"}},
                {"id":"b","type":"scope.out","params":{"name":"x"}}],
                "wires":[{"from":["a","missing"],"to":["b","value"]}]}}""")
            assertEquals(400, response.status, response.body)
            val body = json(response)
            assertEquals("refused", body["status"])
            assertEquals("validation", body["phase"])
            assertEquals(null, body["programKey"])
            assertEquals(named, ctx.blackboard.get("lcnc/program/preset-scope"))
        } finally { supervisor.detach("kanban") }
    }

    @Test fun timeoutWorkLimitAndCancellationHaveDistinctDurableOutcomes() = runBlocking {
        val routes = ModuleRouteRegistry()
        val server = JvmKanbanServer(moduleRoutes = routes)
        val ctx = newContext(routes, tempDir("budgets"), CasStore.inMemory())
        ctx.lcncRunners["test.slow"] = LcncNodeRunner { _, _ -> delay(10000); emptyMap() }
        val supervisor = ModuleSupervisor(ctx)
        supervisor.attach(KanbanModule())
        try {
            val timeout = post(server, "/api/lcnc/run", """{"document":{"nodes":[{"id":"n","type":"test.slow"}],"wires":[]},"timeoutMs":10}""")
            assertEquals(504, timeout.status, timeout.body)
            assertEquals("timed_out", json(timeout)["status"])
            val work = post(server, "/api/lcnc/run", """{"program":"preset-scope","maxNodes":1}""")
            assertEquals("failed", json(work)["status"])
            assertTrue(work.body.contains("work_limit"))
            val pending = async { post(server, "/api/lcnc/run", """{"name":"cancel-check","document":{"nodes":[{"id":"n","type":"test.slow"}],"wires":[]}}""") }
            val runId = withTimeout(5000) {
                var id: String? = null
                while (id == null) {
                    id = ctx.blackboard.snapshot().values.values.mapNotNull { it as? Map<*, *> }
                        .firstOrNull { it["program"] == "cancel-check" && it["status"] == "running" }?.get("runId")?.toString()
                    if (id == null) delay(10)
                }
                id
            }
            assertEquals(202, post(server, "/api/lcnc/run/cancel", """{"runId":"$runId"}""").status)
            val cancelled = withTimeout(5000) { pending.await() }
            assertEquals(499, cancelled.status, cancelled.body)
            val body = json(cancelled)
            assertEquals("cancelled", body["status"])
            val raw = JsonSupport.parse(ctx.casStore.get(borg.trikeshed.job.ContentId(body["receiptCid"].toString()))!!.decodeToString()) as Map<*, *>
            assertEquals("cancelled", (raw["lcncRun"] as Map<*, *>)["status"])
        } finally { supervisor.detach("kanban") }
    }

    @Test fun receiptsRebuildFromTheExistingWalAfterModuleRestart() = runBlocking {
        val dir = tempDir("restart")
        val cas = CasStore.inMemory()
        val routes = ModuleRouteRegistry()
        val ctx = newContext(routes, dir, cas)
        val supervisor = ModuleSupervisor(ctx)
        supervisor.attach(KanbanModule())
        val response = post(JvmKanbanServer(moduleRoutes = routes), "/api/lcnc/run", """{"program":"preset-scope"}""")
        assertEquals(200, response.status, response.body)
        val original = json(response)
        supervisor.detach("kanban")
        val restarted = newContext(ModuleRouteRegistry(), dir, cas)
        val next = ModuleSupervisor(restarted)
        next.attach(KanbanModule())
        try {
            val recovered = restarted.blackboard.get("lcnc/run/${original["runId"]}") as Map<*, *>
            assertEquals(original["programCid"], recovered["programCid"])
            assertEquals(original["receiptCid"], recovered["receiptCid"])
            assertEquals("completed", recovered["status"])
            assertEquals(original["returns"], recovered["returns"])
        } finally { next.detach("kanban") }
    }

    @Test fun immutableVersionsAndReceiptLineageUseTheExecutionStore() = runBlocking {
        val cas = CasStore.inMemory()
        val routes = ModuleRouteRegistry()
        val ctx = newContext(routes, tempDir("content"), cas)
        val supervisor = ModuleSupervisor(ctx)
        supervisor.attach(KanbanModule())
        val server = JvmKanbanServer(moduleRoutes = routes)
        suspend fun get(query: String) = server.routeHttp("GET /api/lcnc/content?$query HTTP/1.1\r\nHost: t\r\n\r\n".encodeToByteArray())
        try {
            val entry = ctx.blackboard.get("lcnc/program/preset-scope-inner") as Map<*, *>
            val candidate = get("cid=${entry["programCid"]}&key=lcnc/program/preset-scope-inner")
            assertEquals(200, candidate.status, candidate.body)
            assertEquals(entry["programCid"], borg.trikeshed.job.ContentId.of(candidate.body.encodeToByteArray()).value)
            assertEquals(409, get("cid=${borg.trikeshed.job.ContentId.of("different".encodeToByteArray()).value}&key=lcnc/program/preset-scope-inner").status)
            val run = post(server, "/api/lcnc/run", """{"program":"preset-scope"}""")
            assertEquals(200, run.status, run.body)
            val receipt = json(run)
            val cid = receipt["programCid"].toString()
            val content = get("cid=$cid")
            assertEquals(200, content.status)
            assertEquals(cid, borg.trikeshed.job.ContentId.of(content.body.encodeToByteArray()).value)
            val sheets = get("cid=$cid&view=sheet")
            assertEquals(200, sheets.status, sheets.body)
            val family = JsonSupport.parse(sheets.body) as List<*>
            assertEquals(cid, (family.first() as Map<*, *>)["id"])
            assertEquals("root-at-admission,subprogram-at-first-use", receipt["versionPolicy"])
            val stored = json(get("cid=${receipt["receiptCid"]}"))["lcncRun"] as Map<*, *>
            assertTrue("receiptCid" !in stored && "sequence" !in stored && "timelineRevision" !in stored)
            val previous = json(get("cid=${stored["previousReceiptCid"]}"))["lcncRun"] as Map<*, *>
            assertEquals("running", previous["status"])
            assertEquals(400, get("cid=invalid").status)
            assertEquals(404, get("cid=${borg.trikeshed.job.ContentId.of("missing".encodeToByteArray()).value}").status)
            assertEquals(400, get("cid=$cid&view=invalid").status)
            val large = cas.put("\"${"x".repeat(1_048_576)}\"".encodeToByteArray())
            assertEquals(413, get("cid=${large.value}").status)
        } finally { supervisor.detach("kanban") }
    }

    @Test fun interruptedRecoveryLinksTheLastDurableReceipt() = runBlocking {
        for (phase in listOf("validating", "running")) {
            val dir = tempDir("interrupted-$phase")
            val cas = CasStore.inMemory()
            val ctx = newContext(ModuleRouteRegistry(), dir, cas)
            val first = borg.trikeshed.kanban.BoardStoreElement(borg.trikeshed.kanban.JvmBoardWal(dir), cas)
            val jobId = "lcnc/run/recover-$phase"
            first.open()
            suspend fun record(op: String, revision: Long, status: String): borg.trikeshed.kanban.BoardApply.Committed {
                val reply = kotlinx.coroutines.CompletableDeferred<borg.trikeshed.kanban.BoardApply>()
                first.intake.send(borg.trikeshed.kanban.BoardIntake(mapOf(
                    "type" to op, "jobId" to jobId, "owner" to "lcnc-runner",
                    "expectedRevision" to revision, "idempotencyKey" to "$jobId-$status",
                    "lcncRun" to mapOf("runId" to "recover-$phase", "program" to "fixture", "status" to status),
                ), reply))
                return reply.await() as borg.trikeshed.kanban.BoardApply.Committed
            }
            var last = record("submit", 0, "validating")
            if (phase == "running") last = record("start", 1, "running")
            first.drain()
            val next = borg.trikeshed.kanban.BoardStoreElement(borg.trikeshed.kanban.JvmBoardWal(dir), cas)
            next.open()
            try {
                LcncRunService(ctx, next) { emptyMap() }.recover()
                val receipt = ctx.blackboard.get(jobId) as Map<*, *>
                assertEquals("interrupted", receipt["status"])
                assertEquals(last.cid.value, receipt["previousReceiptCid"])
                assertEquals(last.revision + 1, (receipt["timelineRevision"] as Number).toLong())
                assertEquals("runtime_restarted", receipt["error"])
            } finally { next.drain() }
        }
    }
}
