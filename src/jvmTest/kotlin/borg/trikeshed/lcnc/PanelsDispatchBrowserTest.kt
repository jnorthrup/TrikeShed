package borg.trikeshed.lcnc

import borg.trikeshed.lcnc.ccek.*
import borg.trikeshed.lib.*
import borg.trikeshed.vm.*
import borg.trikeshed.htx.*
import borg.trikeshed.modelmux.BrainClient
import borg.trikeshed.narsese.BeliefBagElement
import borg.trikeshed.narsese.ingestKg
import borg.trikeshed.parse.reify
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import keymux.*
import kotlinx.coroutines.*
import kotlin.test.*

/** Production Panels and module routes over an isolated real VM host; no running daemon is touched. */
class PanelsDispatchBrowserTest {
    @Test
    @org.junit.jupiter.api.Timeout(value = 12, unit = java.util.concurrent.TimeUnit.MINUTES)
    fun domFormsCommitOnceAndKeepStaleEditsOpen() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(SystemOperations.default.getenv("TRIKESHED_PANELS_FORM_UI") == "1")
        val host = HypervisorVmHost()
        val posts = java.util.concurrent.CopyOnWriteArrayList<Map<*, *>>()
        val program = LcncProgramConfix.fromJson("preset-kanban", LcncPresets.all().getValue("preset-kanban"))
        val binding = ccekReactorBinding(coroutineContext)
        try {
            binding.reactor.open()
            withContext(VmHostKey(host)) {
                val context = vmRuntimeTestContext(this)
                context.lcncRunners.putAll(PureNodes.registry { 1L })
                SubVmLegos.register(context)
                retainedSimulationBrowser(context, null, binding, program,
                    requestBodies = { method, path, body ->
                        if (method == "POST" && path == "/api/lcnc/run") posts += reify(body) as Map<*, *>
                    }, observations = { mapOf("posts" to posts.toList()) },
                    configure = { bound ->
                        // Same in-process module route as the daemon; no synthetic verdicts.
                        bound.lcncRunners.putAll(SurfaceNodes.registry { method, path, body ->
                            val request = "$method $path HTTP/1.1\r\nContent-Type: application/json\r\n\r\n" +
                                borg.trikeshed.parse.jsonOf(body)
                            val response = requireNotNull(bound.routes.match(path)?.route(method, path, request, null))
                            reify(response.body)
                        })
                    })
            }
            assertEquals(5, posts.size, "two initial Runs, one create, one edit, one stale edit; no-op/double-click add no admission")
            assertTrue(posts.all { "document" in it && "type" !in it })
            val events = posts.mapNotNull { post -> (post["pulse"] as? Map<*, *>)?.also {
                assertEquals("events", it["nodeId"])
            }?.get("outputs") as? Map<*, *> }.map { it["event"] as Map<*, *> }
            assertEquals(listOf("create", "edit", "edit"), events.map { it["kind"] })
            assertEquals(3, events.map { it["id"] }.toSet().size)
            assertEquals(listOf(1.0, 1.0), events.drop(1).map { ((it["item"] as Map<*, *>)["revision"] as Number).toDouble() })
        } finally {
            try { host.close() } finally { withContext(NonCancellable) { binding.reactor.drain() } }
        }
    }


    @Test
    @org.junit.jupiter.api.Timeout(value = 12, unit = java.util.concurrent.TimeUnit.MINUTES)
    fun aHeldCommittedFormReceiptCannotMutateTheClosedPage() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(SystemOperations.default.getenv("TRIKESHED_PANELS_FORM_CLOSE_UI") == "1")
        data class Proof(val body: Map<*, *>, val held: Int, val released: Int, val posts: Int,
                         val nanoTime: Long = java.lang.System.nanoTime())
        val posts = java.util.concurrent.CopyOnWriteArrayList<Map<*, *>>()
        val proofs = java.util.concurrent.ConcurrentHashMap<String, Proof>()
        val failures = java.util.concurrent.CopyOnWriteArrayList<Map<*, *>>()
        val armed = java.util.concurrent.atomic.AtomicBoolean(false)
        val produced = java.util.concurrent.atomic.AtomicInteger()
        val held = java.util.concurrent.atomic.AtomicInteger()
        val released = java.util.concurrent.atomic.AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val committed = java.util.concurrent.atomic.AtomicReference<Map<*, *>?>()
        val boardAtCommit = java.util.concurrent.atomic.AtomicReference<Map<*, *>?>()
        lateinit var bound: borg.trikeshed.module.ModuleContext
        fun fields(text: String) = assertIs<Map<*, *>>(reify(text))
        fun event(request: Map<*, *>): Map<*, *>? =
            ((request["pulse"] as? Map<*, *>)?.get("outputs") as? Map<*, *>)?.get("event") as? Map<*, *>
        fun proof(name: String) = assertNotNull(proofs[name], "Missing native form observation: $name")
        fun snapshot(proof: Proof) = assertIs<Map<*, *>>(proof.body["snapshot"])
        val host = HypervisorVmHost()
        val program = LcncProgramConfix.fromJson("preset-kanban", LcncPresets.all().getValue("preset-kanban"))
        val binding = ccekReactorBinding(coroutineContext)
        try {
            binding.reactor.open()
            withContext(VmHostKey(host)) {
                val context = vmRuntimeTestContext(this)
                context.lcncRunners.putAll(PureNodes.registry { 1L })
                SubVmLegos.register(context)
                retainedSimulationBrowser(context, null, binding, program,
                    executorThreads = 8,
                    pageSuffix = formCloseControls(),
                    opened = { bound = it },
                    configure = { actual ->
                        actual.lcncRunners.putAll(SurfaceNodes.registry { method, path, value ->
                            val request = "$method $path HTTP/1.1\r\nContent-Type: application/json\r\n\r\n" +
                                borg.trikeshed.parse.jsonOf(value)
                            reify(requireNotNull(actual.routes.match(path)?.route(method, path, request, null)).body)
                        })
                    },
                    testRoute = { method, path, body ->
                        fun response() = borg.trikeshed.litebike.JvmKanbanServer.HttpResponse(200, "{}")
                        when {
                            method == "POST" && path == "/_test/form/arm" -> {
                                check(posts.size == 2 && event(posts.last())?.get("kind") == "create")
                                check(armed.compareAndSet(false, true))
                                response()
                            }
                            method == "POST" && path == "/_test/form/release" -> { gate.complete(Unit); response() }
                            method == "POST" && path.startsWith("/_test/form/report/") -> {
                                val name = path.substringAfterLast('/')
                                check(proofs.putIfAbsent(name, Proof(fields(body), held.get(), released.get(), posts.size)) == null)
                                response()
                            }
                            method == "POST" && path == "/_test/failure" -> { failures += fields(body); response() }
                            method == "POST" && path == "/api/lcnc/run" -> {
                                val request = fields(body)
                                posts += request
                                // Forward exactly once, retaining the real committed response before holding transport.
                                val actual = requireNotNull(bound.routes.match(path)?.route(method, path, body, null))
                                if (event(request)?.get("kind") == "edit") {
                                    check(armed.compareAndSet(true, false)) { "Only the explicitly armed edit can be held" }
                                    val run = fields(actual.body)
                                    check(actual.status == 200 && run["ok"] == true && run["status"] == "completed") { actual.body }
                                    val outputs = assertIs<Map<*, *>>(run["outputs"])
                                    val verdict = assertIs<List<*>>(assertIs<Map<*, *>>(outputs["n7"])["result"])
                                        .map { assertIs<Map<*, *>>(it) }.single { it["idempotencyKey"] == event(request)?.get("id") }
                                    check(verdict["verdict"] == "committed") { actual.body }
                                    committed.set(run)
                                    boardAtCommit.set(fields(requireNotNull(bound.routes.match("/api/board")
                                        ?.route("GET", "/api/board", "", null)).body))
                                    produced.incrementAndGet()
                                    held.incrementAndGet()
                                    try { gate.await() } finally { held.decrementAndGet(); released.incrementAndGet() }
                                }
                                actual
                            }
                            else -> null
                        }
                    },
                    observations = { mapOf("posts" to posts.toList(), "produced" to produced.get(),
                        "held" to held.get(), "released" to released.get(), "committed" to committed.get(),
                        "boardAtCommit" to boardAtCommit.get()) },
                    cleanup = { gate.complete(Unit) })
            }
            assertTrue(failures.isEmpty(), "Native positive control or protocol failed: $failures")
            assertEquals(3, posts.size, "One Run, one actual create and one actual edit; closed gestures cannot dispatch")
            assertTrue(posts.all { "document" in it && "type" !in it })
            val events = posts.mapNotNull(::event)
            assertEquals(listOf("create", "edit"), events.map { it["kind"] })
            assertEquals(2, events.map { it["id"] }.distinct().size)
            val edit = events.last()
            val item = assertIs<Map<*, *>>(edit["item"])
            val title = assertIs<Map<*, *>>(edit["fields"])["title"]
            val card = assertIs<List<*>>(assertNotNull(boardAtCommit.get())["items"])
                .map { assertIs<Map<*, *>>(it) }.single { it["id"] == item["id"] }
            assertEquals(title, card["title"], "The real metadata update commits before the response gate")
            assertEquals((item["revision"] as Number).toLong() + 1, (card["revision"] as Number).toLong())
            val run = assertNotNull(committed.get())
            val cid = borg.trikeshed.job.ContentId(assertIs<String>(run["receiptCid"]))
            val retained = assertNotNull(bound.casStore.get(cid))
            assertEquals(cid, borg.trikeshed.job.ContentId.of(retained))
            assertEquals(run["runId"], assertIs<Map<*, *>>(fields(retained.decodeToString())["lcncRun"])["runId"])
            assertEquals(1, produced.get())
            assertEquals(1, released.get())
            val control = proof("control")
            assertEquals(true, control.body["createClosed"])
            assertEquals(true, control.body["createdCardVisible"])
            assertEquals(true, control.body["editMounted"])
            val closing = proof("closing")
            val preCloseForm = assertIs<String>(closing.body["preCloseFormHtml"])
            assertContains(preCloseForm, "data-board-form")
            assertContains(preCloseForm, "Awaiting a committed or rejected verdict")
            val pending = proof("pending")
            for (observation in arrayOf(closing, pending)) {
                assertEquals(true, observation.body["closePending"])
                assertEquals(1, observation.held)
                assertEquals(0, observation.released)
                assertEquals(3, observation.posts)
            }
            assertTrue(pending.nanoTime - closing.nanoTime >= java.util.concurrent.TimeUnit.SECONDS.toNanos(1))
            val closed = proof("closed")
            assertEquals(true, closed.body["closed"])
            assertEquals(0, closed.held)
            assertEquals(1, closed.released)
            assertEquals(3, closed.posts)
            for (observation in arrayOf(closing, pending, closed, proof("after-create"), proof("quiet"))) {
                val dom = snapshot(observation)
                assertEquals(setOf("forms", "formHtml", "retainedFormHtml", "boardHtml"), dom.keys)
                assertIs<Number>(dom["forms"])
                if (dom["formHtml"] != null) assertIs<String>(dom["formHtml"])
                assertContains(assertIs<String>(dom["retainedFormHtml"]), "data-board-form")
                assertIs<String>(dom["boardHtml"])
            }
            // First missing lifecycle effect: transport is owned, but the old form is not released at close.
            assertEquals(0.0, (snapshot(closing)["forms"] as Number).toDouble(),
                "Close must release the mounted board form before its committed response arrives")
            assertEquals(snapshot(closing), snapshot(pending))
            assertEquals(snapshot(closing), snapshot(closed), "The late real receipt cannot change closed form presentation")
            val attempted = proof("after-create")
            assertEquals(true, attempted.body["nativeCreateAttempted"])
            assertEquals(snapshot(closed), snapshot(attempted), "A closed board's native Create control cannot mount a form")
            assertEquals(3, attempted.posts)
            val quiet = proof("quiet")
            assertTrue(quiet.nanoTime - attempted.nanoTime >= java.util.concurrent.TimeUnit.SECONDS.toNanos(1))
            assertEquals(snapshot(closed), snapshot(quiet))
            assertEquals(3, quiet.posts)
            println("PANELS_FORM_CLOSE_PROOF=" + borg.trikeshed.parse.jsonOf(mapOf(
                "posts" to posts.toList(), "committed" to run, "boardAtCommit" to boardAtCommit.get(),
                "proofs" to proofs.mapValues { (_, value) -> mapOf("body" to value.body,
                    "held" to value.held, "released" to value.released, "posts" to value.posts) })))
        } finally {
            withContext(NonCancellable) {
                gate.complete(Unit)
                try { host.close() } finally { binding.reactor.drain() }
            }
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 12, unit = java.util.concurrent.TimeUnit.MINUTES)
    fun livePulsesRetainIndependentGuestsAndUseOneAdmissionPerGesture() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(SystemOperations.default.getenv("TRIKESHED_PANELS_PULSE_UI") == "1")
        val real = HypervisorVmHost()
        val specs = java.util.concurrent.CopyOnWriteArrayList<VmSpec>()
        val posts = java.util.concurrent.CopyOnWriteArrayList<Map<*, *>>()
        val host = object : VmHost by real {
            override fun spawn(spec: VmSpec): VmHandle { specs += spec; return real.spawn(spec) }
        }
        val id = "panels-pulse-${java.util.UUID.randomUUID()}"
        val program = LcncProgram("panels-pulse", s_[
            LcncNode("spawn", "vm.spawn", mapOf("id" to id, "facet" to "js", "trust" to "OWN",
                "statements" to "10000", "calls" to "8", "wallMillis" to "5000"), 0.0, 0.0),
            LcncNode("eval", "vm.eval", mapOf("source" to "6 * 7"), 300.0, 0.0),
            LcncNode("independent", "display", x = 600.0, y = 0.0),
            LcncNode("pulse", "button", mapOf("label" to "pulse projection"), 0.0, 350.0),
            LcncNode("projection", "kanban.activeSheets", x = 300.0, y = 350.0),
            LcncNode("expression", "js", mapOf("expr" to "x.sequence * 2"), 600.0, 350.0),
            LcncNode("result", "display", x = 900.0, y = 350.0),
        ], s_[
            LcncWire("spawn", "vmId", "eval", "vmId"), LcncWire("eval", "value", "independent", "x"),
            LcncWire("pulse", "press", "projection", "trigger?"),
            LcncWire("projection", "boardView", "expression", "x"), LcncWire("expression", "y", "result", "x"),
        ], controls = LcncConfixControls(humanOversight = true))
        val binding = ccekReactorBinding(coroutineContext)
        try {
            binding.reactor.open()
            withContext(VmHostKey(host)) {
                val context = vmRuntimeTestContext(this)
                context.lcncRunners.putAll(PureNodes.registry { 1L })
                SubVmLegos.register(context)
                retainedSimulationBrowser(context, null, binding, program,
                    requestBodies = { method, path, body -> if (method == "POST" && path == "/api/lcnc/run") posts += reify(body) as Map<*, *> },
                    observations = { mapOf("posts" to posts.toList(),
                        "specs" to specs.map { it.id }, "independentEvals" to real.get(id)?.stats()?.evals,
                        "liveGuests" to real.ids().filter { real.get(it)?.isAlive == true }) })
            }
            assertEquals(3, posts.size, "one daemon admission per gesture, never one POST per node")
            assertTrue(posts.all { "document" in it && "type" !in it })
            assertTrue("pulse" !in posts[0])
            for (post in posts.drop(1)) {
                assertTrue(post["baseReceiptCid"] is String)
                assertEquals("pulse", (post["pulse"] as Map<*, *>)["nodeId"])
            }
            assertEquals(1, specs.count { it.id == id })
            // KanbanModule owns the real board projection; observe its downstream guest,
            // not a mock registry entry that the module legitimately replaces on open.
            assertEquals(3, specs.count { it.id != id }, "one expression per Live/pulse; the changed definition cannot replay")
            assertEquals(1L, real.get(id)?.stats()?.evals)
            assertEquals(listOf(id), real.ids().filter { real.get(it)?.isAlive == true }, "temporary expression guests must be released")
        } finally {
            try { host.close() } finally { withContext(NonCancellable) { binding.reactor.drain() } }
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 12, unit = java.util.concurrent.TimeUnit.MINUTES)
    fun kgAndVerdictUseDistinctSharedRunnersInRunAndLive() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(SystemOperations.default.getenv("TRIKESHED_PANELS_KG_UI") == "1")
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val reports = java.util.concurrent.CopyOnWriteArrayList<Map<String, Any?>>()
        val recorded = java.util.concurrent.CopyOnWriteArrayList<String>()
        val bag = BeliefBagElement(capacity = 64)
        val binding = ccekReactorBinding(coroutineContext)
        val program = LcncProgram("panels-kg-contract", s_[
            LcncNode("source", "text.value", mapOf("value" to "(subclass Sparrow Bird)"), 0.0, 0.0),
            LcncNode("kg", "kg.ingest", mapOf("kg" to "fallback"), 300.0, 0.0),
            LcncNode("belief-report", "display", x = 650.0, y = 0.0),
            LcncNode("record", "tribunal.record", mapOf("text" to "Synthetic verdict: retain this text, not a KG assertion."), 300.0, 320.0),
            LcncNode("verdict-report", "display", x = 650.0, y = 320.0),
        ], s_[
            LcncWire("source", "value", "kg", "text?"),
            LcncWire("kg", "report", "belief-report", "x"),
            LcncWire("record", "report", "verdict-report", "x"),
        ])
        try {
            bag.open()
            binding.reactor.open()
            val context = vmRuntimeTestContext(this)
            context.lcncRunners.putAll(PureNodes.registry { 0L })
            context.lcncRunners.putAll(KgNodes.registry { text -> bag.ingestKg(text).also { reports += it } })
            context.lcncRunners.putAll(TribunalNodes.registry(
                TribunalDialog { _, _, _ -> error("No model request belongs to this fixture") },
                tribunalRecord(context.casStore) { cid -> recorded += cid.value; cid.value },
            ))
            retainedSimulationBrowser(context, null, binding, program,
                requests = { method, path -> if (path.startsWith("/api/")) requests += "$method $path" },
                observations = { mapOf("requests" to requests.toList(), "reports" to reports.toList(),
                    "recorded" to recorded.toList(), "beliefs" to bag.size) })
            assertEquals(2, reports.size, "Run and Live process KG once each; blank input refuses")
            assertEquals(reports[0], reports[1])
            assertEquals("KIF", reports[0]["format"])
            assertEquals(1, reports[0]["minted"])
            assertEquals(1, bag.size, "prose recording must not mint a second belief")
            // Run, Live, then a second full Live with blank KG input. The independent
            // verdict root still executes; a failed KG branch is not a graph rollback.
            assertEquals(3, recorded.size)
            assertTrue(recorded.all { it == recorded[0] }, "every verdict invocation retains the same bytes")
            assertTrue(requests.none { it == "POST /api/beliefs/kg" }, "Live must use the shared LCNC registry")
        } finally {
            withContext(NonCancellable) { try { binding.reactor.drain() } finally { bag.drain() } }
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 12, unit = java.util.concurrent.TimeUnit.MINUTES)
    fun chatUsesSharedOptionsInRunAndLive() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(SystemOperations.default.getenv("TRIKESHED_PANELS_CHAT_UI") == "1")
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val calls = java.util.concurrent.CopyOnWriteArrayList<Map<*, *>>()
        val htx = openHtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                calls += reify(request.body.toArray().decodeToString()) as Map<*, *>
                val response = HtxResponse(200, body = ByteSeries(
                    """{"choices":[{"message":{"content":"synthetic chat answer"}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}""".encodeToByteArray(),
                ))
                return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, request = request, response = response))
            }
        })
        val keys = KeyMux { bind("llm.default.key", FixedKeySource("fixture-key")) }
        val brain = BrainClient(apiKey = "fixture-key", model = "fixture-model", base = "https://fixture.invalid/v1", keyMux = keys)
        val program = LcncProgram("panels-chat-options", s_[
            LcncNode("rules", "text.value", mapOf("value" to "synthetic rules"), 0.0, 0.0),
            LcncNode("question", "text.value", mapOf("value" to "synthetic question"), 0.0, 300.0),
            LcncNode("seat", "mux.chat", mapOf("prompt" to "fallback prompt", "system" to "fallback system",
                "maxTokens" to "73", "temperature" to "0.4",
                "models" to """[{"key":"override","model":"fixture-model"}]"""), 300.0, 100.0),
            LcncNode("answer", "display", x = 650.0, y = 0.0),
            LcncNode("model", "display", x = 650.0, y = 300.0),
        ], s_[
            LcncWire("rules", "value", "seat", "system?"),
            LcncWire("question", "value", "seat", "prompt?"),
            LcncWire("seat", "content", "answer", "x"),
            LcncWire("seat", "model", "model", "x"),
        ])
        val binding = ccekReactorBinding(coroutineContext)
        try {
            binding.reactor.open()
            val context = vmRuntimeTestContext(this)
            context.lcncRunners.putAll(PureNodes.registry { 0L })
            context.lcncRunners.putAll(TribunalNodes.registry(modelMuxDialog(brain, htx)))
            retainedSimulationBrowser(context, null, binding, program,
                requests = { method, path -> if (path.startsWith("/api/")) requests += "$method $path" },
                observations = { mapOf("requests" to requests.toList(), "calls" to calls.toList()) })
            assertEquals(2, calls.size, "Run and Live each dispatch once; invalid Live options must not dispatch")
            for (call in calls) {
                assertEquals(73, (call["max_tokens"] as Number).toInt())
                assertEquals(0.4, (call["temperature"] as Number).toDouble())
                assertEquals("fixture-model", call["model"])
                assertEquals(reify("""[{"role":"system","content":"synthetic rules"},{"role":"user","content":"synthetic question"}]"""), call["messages"])
            }
            assertTrue(requests.none { it == "POST /api/mux/chat" }, "The page must use the shared LCNC runner")
            assertTrue(requests.count { it == "POST /api/lcnc/run" } >= 7)
        } finally {
            try { withContext(NonCancellable) { binding.reactor.drain() } } finally { htx.close() }
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 12, unit = java.util.concurrent.TimeUnit.MINUTES)
    fun liveVmAndDataNodesUseDaemonBindings() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(SystemOperations.default.getenv("TRIKESHED_PANELS_DISPATCH_UI") == "1")
        val real = HypervisorVmHost()
        val specs = java.util.concurrent.CopyOnWriteArrayList<VmSpec>()
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val host = object : VmHost by real {
            override fun spawn(spec: VmSpec): VmHandle {
                specs += spec
                return real.spawn(spec)
            }
        }
        val id = "panels-dispatch-${java.util.UUID.randomUUID()}"
        val program = LcncProgram("panels-dispatch", s_[
            LcncNode("clock", "timer", mapOf("seconds" to "3600"), 0.0, 0.0),
            LcncNode("clock-view", "display", x = 300.0, y = 0.0),
            LcncNode("spawn", "vm.spawn", mapOf("id" to id, "facet" to "js", "trust" to "OWN",
                "statements" to "10000", "calls" to "8", "wallMillis" to "5000"), 0.0, 220.0),
            LcncNode("eval", "vm.eval", mapOf("source" to "6 * 7"), 300.0, 220.0),
            LcncNode("value-view", "display", x = 600.0, y = 220.0),
            LcncNode("literal", "json.value", mapOf("value" to "[{\"value\":\"synthetic evidence\"}]"), 0.0, 480.0),
            LcncNode("pick", "pick", mapOf("path" to "0.value"), 300.0, 480.0),
            LcncNode("data-view", "display", x = 600.0, y = 480.0),
        ], s_[
            LcncWire("clock", "tick", "clock-view", "x"),
            LcncWire("spawn", "vmId", "eval", "vmId"),
            LcncWire("eval", "value", "value-view", "x"),
            LcncWire("literal", "value", "pick", "x"),
            LcncWire("pick", "y", "data-view", "x"),
        ])
        val binding = ccekReactorBinding(coroutineContext)
        try {
            binding.reactor.open()
            withContext(LcncClockKey { 4242L }) {
                val context = vmRuntimeTestContext(this)
                context.lcncRunners.putAll(PureNodes.registry { 1L })
                context.lcncRunners.putAll(VmRuntimeNodes.runners(host))
                retainedSimulationBrowser(context, null, binding, program,
                    requests = { method, path -> if (path.startsWith("/api/")) requests += "$method $path" },
                    observations = { mapOf(
                        "requests" to requests.toList(),
                        "specs" to specs.map { mapOf("id" to it.id, "trust" to it.trust.name,
                            "statements" to it.budget.statements, "calls" to it.budget.calls, "wallMillis" to it.budget.wallMillis) },
                        "evals" to real.get(id)?.stats()?.evals,
                    ) })
            }
            assertEquals(1, specs.size, "A repeated Live invocation must refuse an existing VM, not silently reuse it")
            assertEquals(VmTrust.OWN, specs.single().trust)
            assertEquals(VmBudget(statements = 10000, calls = 8, wallMillis = 5000), specs.single().budget)
            assertEquals(1L, real.get(id)?.stats()?.evals)
            assertTrue(requests.none { it.contains("/api/vm/") }, "The page must not bypass commonMain VM runners")
            assertTrue(requests.count { it == "POST /api/lcnc/run" } >= 8,
                "VM and data nodes must dispatch through the production registry")
        } finally {
            try { host.close() } finally { withContext(NonCancellable) { binding.reactor.drain() } }
        }
    }

    /** Fixture controls observe production DOM and close its actual owner; they do not drive form gestures. */
    private fun formCloseControls(): String = """
        <aside id="formCloseControls" style="position:fixed;right:8px;bottom:8px;z-index:100000;background:#151923;color:#fff;padding:10px;max-width:320px;border:2px solid #9ce;max-height:45vh;overflow:auto">
          <strong>Board form close fixture</strong><div id="formCloseButtons"></div><pre id="formCloseState" style="white-space:pre-wrap"></pre>
        </aside>
        <script>
        (function () {
          const state = window.PANELS_FORM_CLOSE = {closed:false, closeStarted:false, nativeCreateAttempts:0, snapshots:{}};
          const panel = document.getElementById('formCloseControls');
          const display = document.getElementById('formCloseState');
          let closePromise = null, pendingTimer = 0, quietTimer = 0, created = null;
          const snapshot = () => ({forms:document.querySelectorAll('[data-board-form]').length,
            formHtml:document.querySelector('[data-board-form]')?.outerHTML || null,
            retainedFormHtml:state.form?.outerHTML || null,
            boardHtml:document.querySelector('.kboard')?.outerHTML || ''});
          async function post(path, value) {
            const response = await fetch(path, {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(value || {})});
            if (!response.ok) throw new Error(path + ': ' + await response.text());
            return response.json();
          }
          async function observe() {
            const response = await fetch('/_test/observations');
            if (!response.ok) throw new Error('Fixture observations unavailable');
            return response.json();
          }
          function show(message) { display.textContent = message; }
          async function report(name, extra) {
            const value = Object.assign({snapshot:snapshot()}, extra || {});
            state.snapshots[name] = value;
            await post('/_test/form/report/' + name, value);
            return value;
          }
          async function fail(error) {
            state.failure = String(error.stack || error); show('Fixture error: ' + state.failure);
            try { await post('/_test/failure', {detail:state.failure}); }
            finally { await post('/_test/form/release'); }
          }
          function button(label, action) {
            const element = document.createElement('button'); element.textContent = label;
            element.style.cssText = 'display:block;margin:5px 0';
            element.addEventListener('click', () => { Promise.resolve().then(action).catch(fail); });
            document.getElementById('formCloseButtons').appendChild(element); return element;
          }
          const attempted = event => {
            const target = event.target?.closest?.('.kboard-actions button');
            if (state.closed && event.isTrusted && target?.textContent.trim() === 'Create item') state.nativeCreateAttempts++;
          };
          document.addEventListener('click', attempted, true);
          button('Record ordinary create', async () => {
            const actual = await observe();
            const visible = Array.from(document.querySelectorAll('.kcard')).some(card => card.textContent.includes('Form close original'));
            if (document.querySelector('[data-board-form]') || !visible || actual.posts.length !== 2) throw new Error('Complete the ordinary Run/Create positive control first');
            created = {createClosed:true, createdCardVisible:visible};
            show('Ordinary create completed and its card is visible. Open its native Edit form, change Title, then arm.');
          });
          button('Arm committed edit response', async () => {
            const form = document.querySelector('[data-board-form]');
            if (!created || !form || form.getAttribute('aria-label') !== 'Edit item') throw new Error('Open the actual edited card first');
            if (form.querySelector('[data-field="title"]')?.value !== 'Form close committed') throw new Error('Set Title to Form close committed using the native field');
            await post('/_test/form/arm');
            await report('control', Object.assign({editMounted:true}, created));
            show('Armed. Press the production form Apply button, then Close Panels.');
          });
          button('Close Panels', async () => {
            if (state.closeStarted) throw new Error('Close was already started');
            const actual = await observe();
            if (actual.produced !== 1 || actual.held !== 1 || actual.released !== 0 || !actual.committed?.receiptCid) throw new Error('Wait for the actual committed edit response to enter its gate');
            state.form = document.querySelector('[data-board-form]');
            if (!state.form) throw new Error('The actual busy Edit form must be mounted before close');
            state.preCloseFormHtml = state.form.outerHTML;
            state.closeStarted = true;
            closePromise = window.closePanels();
            closePromise.then(() => {state.closed = true;}, error => {state.closeError=String(error);});
            await report('closing', {closePending:!state.closed, preCloseFormHtml:state.preCloseFormHtml});
            show('Actual close started; committed response remains held. Waiting to record pending drain.');
            pendingTimer = setTimeout(() => { report('pending', {closePending:!state.closed}).then(() => {
              state.pendingRecorded = true; show('Pending drain recorded with the real response held. Release the response.');
            }).catch(fail); }, 1100);
          });
          button('Release committed response', async () => {
            if (!state.pendingRecorded) throw new Error('Record the held pending drain before release');
            await post('/_test/form/release');
            await closePromise;
            await report('closed', {closed:state.closed});
            show('Actual page drain resolved. Press the production Create item button once, then Record native Create attempt.');
          });
          button('Record native Create attempt', async () => {
            if (!state.closed || state.nativeCreateAttempts !== 1) throw new Error('Press the actual closed board Create item control once');
            await report('after-create', {nativeCreateAttempted:true});
            quietTimer = setTimeout(() => { report('quiet').then(() => {
              state.quietRecorded = true; show('Quiet observation recorded. Finish browser check.');
            }).catch(fail); }, 1100);
          });
          button('Finish browser check', async () => {
            if (!state.quietRecorded && !state.failure) throw new Error('Complete the quiet observation before finishing');
            clearTimeout(pendingTimer); clearTimeout(quietTimer);
            document.removeEventListener('click', attempted, true);
            await post('/_test/form/release');
            try { await (closePromise || window.closePanels()); }
            finally { await post('/_test/finish'); }
            show('Fixture finished; close this test tab.');
          });
          show('Use ordinary Run, Create item, Title=Form close original and Create. Then Record ordinary create.');
        }());
        </script>
    """.trimIndent()
}
