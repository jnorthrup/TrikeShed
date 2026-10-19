package borg.trikeshed.kanban.module

import borg.trikeshed.couch.*
import borg.trikeshed.collections.associative.*
import borg.trikeshed.context.nuid.*
import borg.trikeshed.dag.*
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.*
import borg.trikeshed.kanban.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lcnc.ccek.LcncRunQueueEntry
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.module.*
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.*
import kotlinx.coroutines.*
import java.nio.file.Path
import kotlin.test.*

class LcncRunPulseTest {
    /** Pure selected readers retain exact binding origins; effectful selected readers keep pre-effect refusal. */
    @Test fun pureWarmBindingOriginsSurviveCasWalReopenAndPinnedNamedEachAndCarryFrames() = runBlocking {
        for (mode in listOf("root", "each", "carry", "named")) {
            val label = "pure-reader-$mode"
            val reader = LcncProgram(label, s_[
                LcncNode("activation", "scope.in", mapOf("name" to "activation", "kind" to "trigger")),
                LcncNode("lateEdge", "scope.in", mapOf("name" to "edge", "kind" to "trigger"))], emptySeriesOf())
            val nodes = mutableListOf(LcncNode("source", "test.source"))
            val wires = mutableListOf<LcncWire>()
            val alias = LcncNode("alias", "scope.in", mapOf("name" to "edge"))
            val merge = LcncNode("merge", "merge")
            wires.add(LcncWire("alias", "value", "merge", "a"))
            val forwarded: String
            val port: String
            if (mode == "each" || mode == "carry") {
                if (mode == "each") {
                    nodes.add(LcncNode("items", "json.value", mapOf("value" to "[1,2]")))
                    wires.add(LcncWire("items", "value", "ring", "each"))
                }
                nodes.add(LcncNode("ring", "scope", if (mode == "carry") mapOf("carry" to "true") else emptyMap(),
                    children = s_[alias, merge,
                        LcncNode("edgeYield", "scope.out", mapOf("name" to "edge", "kind" to "json"))]))
                nodes.add(LcncNode("pick", "pick", mapOf("path" to if (mode == "each") "0.edge" else "edge")))
                wires.add(LcncWire("merge", "value", "edgeYield", "value"))
                wires.add(LcncWire("ring", "returns", "pick", "x"))
                forwarded = "pick"; port = "y"
            } else {
                nodes.add(alias); nodes.add(merge)
                forwarded = "merge"; port = "value"
            }
            nodes.add(LcncNode("ordinary", "scope.in", mapOf("name" to "ordinary", "kind" to "num")))
            nodes.add(LcncNode("first", "button"))
            nodes.add(if (mode == "named") LcncNode("gate", "scope", subprogram = label)
                else LcncNode("gate", "scope", children = reader.nodes))
            nodes.add(LcncNode("second", "button"))
            nodes.add(LcncNode("consumer", "scope", children = s_[
                LcncNode("edge", "scope.in", mapOf("name" to "edge")),
                LcncNode("press", "scope.in", mapOf("name" to "press", "kind" to "trigger")),
                LcncNode("effect", "test.effect")]))
            wires.addAll(listOf(LcncWire("first", "press", "gate", "activation"),
                LcncWire(forwarded, port, "consumer", "edge"),
                LcncWire("second", "press", "consumer", "press"),
                LcncWire("edge", "value", "effect", "edge"), LcncWire("press", "value", "effect", "press")))
            val document = LcncProgram("warm-provenance-$mode", nodes.toSeries(), wires.toSeries())
            assertTrue(LcncTypeCheck.check(document, strict = false).isEmpty(), mode)
            val args = mapOf("edge" to 1L, "ordinary" to 1L)
            var sources = 0
            var effects = 0
            var loads = 0
            lateinit var baseCid: String
            lateinit var typedCid: String
            suspend fun state(ctx: ModuleContext, receipt: Map<String, Any?>): Map<*, *> {
                assertEquals("cbor", receipt["rootStateFormat"])
                val bytes = assertNotNull(ctx.casStore.get(ContentId(receipt["rootStateCid"] as String)))
                assertEquals(ContentId(receipt["rootStateCid"] as String), ContentId.of(bytes))
                return Cbor.Reader(bytes, 0).readItem().toAny() as Map<*, *>
            }
            suspend fun execute(ctx: ModuleContext, runs: LcncRunService, base: String? = null, control: String = "first"): Map<String, Any?> {
                ctx.lcncRunners["test.source"] = LcncNodeRunner { _, _ -> sources++; mapOf("value" to "retained") }
                ctx.lcncRunners["test.effect"] = LcncNodeRunner { _, _ -> effects++; emptyMap() }
                val request = if (base == null) emptyMap<String, Any?>() else mapOf("baseReceiptCid" to base,
                    "pulse" to mapOf("nodeId" to control, "outputs" to mapOf("press" to 2L)))
                val response = runs.execute(document.name, document, false, args, request)
                assertEquals(200, response.status, "$mode: ${response.body}")
                return reifyMap(response.body)
            }
            fixture(loader = { requested -> loads++; assertEquals(label, requested); reader }) { ctx, runs ->
                val base = execute(ctx, runs)
                baseCid = base["receiptCid"] as String
                val snapshot = state(ctx, base)
                assertEquals(args, snapshot["bindings"])
                val outputs = snapshot["outputs"] as Map<*, *>
                assertEquals(mapOf(port to 1L), outputs[forwarded])
                assertFalse(outputs.containsKey("gate"))
                if (mode == "named") assertTrue(label in (base["programVersions"] as Map<*, *>))
                assertEquals(1, sources)
                assertEquals(0, effects)
            }
            val expectedLoads = if (mode == "named") 1 else 0
            assertEquals(expectedLoads, loads)
            fixture(loader = { error("Warm execution must use its retained CID-pinned reader, never the current declaration") }) { ctx, runs ->
                val typed = execute(ctx, runs, baseCid)
                typedCid = typed["receiptCid"] as String
                val actual = typed["outputs"] as Map<*, *>
                assertEquals(1L, ((actual[forwarded] as Map<*, *>)[port] as Number).toLong())
                assertEquals(mapOf("returns" to emptyMap<String, Any?>()), actual["gate"])
                val snapshot = state(ctx, typed)
                assertEquals(mapOf("ordinary" to 1L), snapshot["bindings"])
                val outputs = snapshot["outputs"] as Map<*, *>
                assertEquals(emptyMap<String, Any?>(), outputs[forwarded], "The saved next frame must already omit the alias: $mode")
                assertEquals(mapOf("value" to 1L), outputs["ordinary"])
                if (mode == "each" || mode == "carry") {
                    val ring = outputs["ring"] as Map<*, *>
                    assertFalse(ring.containsKey("edge"))
                    assertEquals(2L, (ring["count"] as Number).toLong())
                    assertEquals(if (mode == "each") listOf(emptyMap<String, Any?>(), emptyMap()) else emptyMap<String, Any?>(), ring["returns"])
                }
                assertEquals(1, sources, "Independent producers never rerun to reconstruct provenance")
                assertEquals(0, effects)
            }
            fixture(loader = { error("An inactive pinned body must not be loaded again") }) { ctx, runs ->
                val next = execute(ctx, runs, typedCid, "second")
                val outputs = next["outputs"] as Map<*, *>
                assertEquals(emptyMap<String, Any?>(), outputs[forwarded])
                assertEquals(mapOf("ordinary" to 1L), state(ctx, next)["bindings"])
                assertEquals(1, sources)
                assertEquals(0, effects, "A CAS/WAL roundtrip cannot restore the expired binding: $mode")
            }
            assertEquals(expectedLoads, loads)
        }
    }

    @Test fun aNewAmbientTriggerReadIsRefusedBeforeSelectedEffectsOrQueuePublication() = runBlocking {
        fixture { ctx, _ ->
            var sources = 0
            var effects = 0
            ctx.lcncRunners["test.source"] = LcncNodeRunner { _, _ -> sources++; mapOf("value" to "retained") }
            ctx.lcncRunners["http.post"] = LcncNodeRunner { _, _ -> effects++; mapOf("json" to emptyMap<String, Any?>()) }
            val document = LcncProgram("binding-admission", s_[
                LcncNode("source", "test.source"),
                LcncNode("alias", "scope.in", mapOf("name" to "edge")),
                LcncNode("merge", "merge"),
                LcncNode("ordinary", "scope.in", mapOf("name" to "ordinary", "kind" to "num")),
                LcncNode("button", "button"),
                LcncNode("gate", "scope", children = s_[
                    LcncNode("activation", "scope.in", mapOf("name" to "activation", "kind" to "trigger")),
                    LcncNode("body", "json.value", mapOf("value" to "{\"op\":\"selected\"}")),
                    LcncNode("effect", "http.post", mapOf("path" to "/fixture")),
                    LcncNode("lateEdge", "scope.in", mapOf("name" to "edge", "kind" to "trigger"))])], s_[
                LcncWire("alias", "value", "merge", "a"),
                LcncWire("button", "press", "gate", "activation"),
                LcncWire("body", "value", "effect", "body")])
            assertTrue(LcncTypeCheck.check(document, strict = false).isEmpty())
            val supervisor = ModuleSupervisor(ctx)
            supervisor.attach(KanbanModule())
            try {
                val server = JvmKanbanServer(moduleRoutes = ctx.routes)
                suspend fun post(body: Map<String, Any?>): JvmKanbanServer.HttpResponse {
                    val json = jsonOf(body)
                    return server.routeHttp(("POST /api/lcnc/run HTTP/1.1\r\nHost: t\r\n" +
                        "Content-Length: ${json.encodeToByteArray().size}\r\n\r\n$json").encodeToByteArray())
                }
                suspend fun board(): Map<String, Any?> {
                    val result = server.routeHttp("GET /api/board HTTP/1.1\r\nHost: t\r\n\r\n".encodeToByteArray())
                    assertEquals(200, result.status, result.body)
                    return reifyMap(result.body)
                }
                val request = mapOf("name" to document.name,
                    "document" to reify(LcncProgramConfix.toJson(document)),
                    "inputs" to mapOf("edge" to 1L, "ordinary" to 1L))
                val completed = post(request)
                assertEquals(200, completed.status, completed.body)
                val receipt = reifyMap(completed.body)
                val bytes = assertNotNull(ctx.casStore.get(ContentId(receipt["rootStateCid"] as String)))
                val state = Cbor.Reader(bytes, 0).readItem().toAny() as Map<*, *>
                val bindings = state["bindings"] as Map<*, *>
                assertEquals(setOf("edge", "ordinary"), bindings.keys)
                assertEquals(1L, (bindings["edge"] as Number).toLong())
                assertEquals(1L, (bindings["ordinary"] as Number).toLong())
                assertFalse((state["outputs"] as Map<*, *>).containsKey("gate"), "The typed read was initially skipped")
                assertEquals(1, sources)
                assertEquals(0, effects)
                val beforeBoard = board()
                val beforeReceipts = ctx.blackboard.keys().filter { it.startsWith("lcnc/run/") }.toSet()
                val beforeReceipt = ctx.blackboard.get("lcnc/run/${receipt["runId"]}")

                val refused = post(request + pulse(receipt["receiptCid"]))
                assertEquals(409, refused.status, refused.body)
                assertEquals("pulse_binding_origin_unavailable", reifyMap(refused.body)["error"])
                assertEquals(1, sources, "Admission cannot rerun the independent source")
                assertEquals(0, effects, "Even the selected effect authored before the typed read must not execute")
                assertEquals(beforeBoard, board(), "A refused pulse cannot publish an execution card or transition")
                assertEquals(beforeReceipts, ctx.blackboard.keys().filter { it.startsWith("lcnc/run/") }.toSet())
                assertEquals(beforeReceipt, ctx.blackboard.get("lcnc/run/${receipt["runId"]}"))
            } finally { supervisor.detach("kanban") }
        }
    }

    @Test fun aLocallyCommittedLegacyExecutionProjectionIsRefusedBeforeEffects() = runBlocking {
        for (retention in listOf("execution", "execution-v2", "execution-v3")) {
            val document = program()
            val programBytes = LcncProgramConfix.toJson(document).encodeToByteArray()
            val programCid = ContentId.of(programBytes)
            val stateBytes = CanonicalCbor.encodeMap(mapOf(
                "kind" to "lcnc-root-state", "programCid" to programCid.value,
                "retention" to retention, "bindings" to emptyMap<String, Any?>(),
                "outputs" to mapOf("independent" to mapOf("value" to "retained"),
                    "button" to emptyMap<String, Any?>(), "consume" to emptyMap<String, Any?>(),
                    "yield" to emptyMap<String, Any?>())))
            val stateCid = ContentId.of(stateBytes)
            val runId = "legacy-projection-$retention"
            val jobId = "lcnc/run/$runId"
            lateinit var store: BoardStoreElement
            lateinit var baseCid: String
            fixture(observeStore = { store = it }) { ctx, _ ->
                assertEquals(programCid, ctx.casStore.put(programBytes))
                assertEquals(stateCid, ctx.casStore.put(stateBytes))
                for ((revision, operation) in listOf("submit", "start", "complete").withIndex()) {
                    val terminal = operation == "complete"
                    val receipt = mapOf("runId" to runId, "program" to document.name,
                        "programCid" to programCid.value, "inputs" to emptyMap<String, Any?>(),
                        "returns" to emptyMap<String, Any?>(), "programVersions" to emptyMap<String, String>(),
                        "executionFamily" to jobId, "parentRunId" to null,
                        "status" to if (terminal) "completed" else "running", "ok" to terminal,
                        "rootStateCid" to stateCid.value, "rootStateFormat" to "cbor")
                    val reply = CompletableDeferred<BoardApply>()
                    store.intake.send(BoardIntake(mapOf("type" to operation, "jobId" to jobId,
                        "owner" to "lcnc-runner", "expectedRevision" to revision,
                        "idempotencyKey" to "$runId/$operation", "lcncRun" to receipt), reply))
                    val committed = assertIs<BoardApply.Committed>(reply.await())
                    baseCid = committed.cid.value
                }
                assertEquals(ContentId(baseCid), store.card(jobId)?.commandCid)
            }
            fixture(observeStore = { store = it }) { ctx, runs ->
                assertEquals(ContentId(baseCid), store.card(jobId)?.commandCid,
                    "The completed legacy receipt is the current locally replayed board head")
                assertContentEquals(stateBytes, assertNotNull(ctx.casStore.get(stateCid)))
                val sourceBytes = assertNotNull(ctx.casStore.get(ContentId(baseCid)))
                assertEquals(ContentId(baseCid), ContentId.of(sourceBytes))
                val source = reifyMap(sourceBytes.decodeToString())
                assertEquals("complete", source["type"])
                assertEquals("lcnc-runner", source["owner"])
                assertEquals(jobId, source["jobId"])
                assertEquals(stateCid.value, (source["lcncRun"] as Map<*, *>)["rootStateCid"])
                var effects = 0
                ctx.lcncRunners["test.effect"] = LcncNodeRunner { _, _ -> effects++; mapOf("value" to "unexpected") }
                ctx.lcncRunners["test.consume"] = LcncNodeRunner { _, input -> effects++; mapOf("value" to input["press"]) }
                val result = runs.execute(document.name, document, false, emptyMap(), pulse(baseCid))
                assertEquals(409, result.status, result.body)
                assertEquals("pulse_state_unavailable", reifyMap(result.body)["error"])
                assertEquals(0, effects, "A valid older projection cannot authorize any fresh pulse execution")
            }
        }
    }

    @Test fun committedV4OriginsAreValidatedBeforeEffectsOrQueuePublication() = runBlocking {
        val valid = mapOf("bindings" to listOf("edge"), "terms" to listOf(listOf(0, 0)),
            "origins" to listOf(listOf(2, "independent", "value", 0)))
        val cases = listOf<Join<String, Any?>>(
            "valid" j valid,
            "missing" j null,
            "not-map" j emptyList<Any?>(),
            "unknown-owner" j (valid + ("bindings" to listOf("other"))),
            "fractional-term" j (valid + ("origins" to listOf(listOf(2.5, "independent", "value", 0)))),
            "forward-term" j (valid + ("terms" to listOf(listOf(1, 2)))),
            "false-guard" j (valid + ("origins" to listOf(listOf(1, "independent", "value", 0)))),
            "root-path" j (valid + ("origins" to listOf(listOf(2)))),
            "node-path" j (valid + ("origins" to listOf(listOf(2, "independent")))),
            "wrong-map-index" j (valid + ("origins" to listOf(listOf(2, "independent", 0)))),
            "wrong-list-key" j (valid + ("origins" to listOf(listOf(2, "independent", "value", "0")))),
            "out-of-range" j (valid + ("origins" to listOf(listOf(2, "independent", "value", 1)))),
        )
        for ((name, origins) in cases) {
            val document = program()
            val inputs = mapOf("edge" to 1L)
            val programBytes = LcncProgramConfix.toJson(document).encodeToByteArray()
            val programCid = ContentId.of(programBytes)
            val state = linkedMapOf<String, Any?>("kind" to "lcnc-root-state", "programCid" to programCid.value,
                "retention" to "execution-v4", "bindings" to inputs,
                "outputs" to mapOf("independent" to mapOf("value" to listOf(1L)),
                    "button" to emptyMap<String, Any?>(), "consume" to emptyMap<String, Any?>(),
                    "yield" to emptyMap<String, Any?>()))
            if (origins != null) state["origins"] = origins
            val stateBytes = CanonicalCbor.encodeMap(state)
            val stateCid = ContentId.of(stateBytes)
            val runId = "v4-origin-$name"
            val jobId = "lcnc/run/$runId"
            lateinit var store: BoardStoreElement
            lateinit var baseCid: String
            fixture(observeStore = { store = it }) { ctx, _ ->
                assertEquals(programCid, ctx.casStore.put(programBytes))
                assertEquals(stateCid, ctx.casStore.put(stateBytes))
                for ((revision, operation) in listOf("submit", "start", "complete").withIndex()) {
                    val terminal = operation == "complete"
                    val receipt = mapOf("runId" to runId, "program" to document.name,
                        "programCid" to programCid.value, "inputs" to inputs,
                        "returns" to emptyMap<String, Any?>(), "programVersions" to emptyMap<String, String>(),
                        "executionFamily" to jobId, "parentRunId" to null,
                        "status" to if (terminal) "completed" else "running", "ok" to terminal,
                        "rootStateCid" to stateCid.value, "rootStateFormat" to "cbor")
                    val reply = CompletableDeferred<BoardApply>()
                    store.intake.send(BoardIntake(mapOf("type" to operation, "jobId" to jobId,
                        "owner" to "lcnc-runner", "expectedRevision" to revision,
                        "idempotencyKey" to "$runId/$operation", "lcncRun" to receipt), reply))
                    baseCid = assertIs<BoardApply.Committed>(reply.await()).cid.value
                }
            }
            fixture(observeStore = { store = it }) { ctx, runs ->
                assertEquals(ContentId(baseCid), store.card(jobId)?.commandCid, name)
                val persisted = assertNotNull(ctx.casStore.get(stateCid))
                assertContentEquals(stateBytes, persisted)
                assertEquals(stateCid, ContentId.of(persisted))
                val source = assertNotNull(ctx.casStore.get(ContentId(baseCid)))
                assertEquals(ContentId(baseCid), ContentId.of(source))
                assertEquals(stateCid.value, (reifyMap(source.decodeToString())["lcncRun"] as Map<*, *>)["rootStateCid"])
                var independentEffects = 0
                var selectedEffects = 0
                ctx.lcncRunners["test.effect"] = LcncNodeRunner { _, _ -> independentEffects++; mapOf("value" to "unexpected") }
                ctx.lcncRunners["test.consume"] = LcncNodeRunner { _, input -> selectedEffects++; mapOf("value" to input["press"]) }
                val before = store.cards().toList()
                val result = runs.execute(document.name, document, false, inputs, pulse(baseCid))
                if (name == "valid") {
                    assertEquals(200, result.status, result.body)
                    assertEquals(1, selectedEffects)
                    assertEquals(0, independentEffects)
                    assertNotNull(reifyMap(result.body)["rootStateCid"], "A valid imported frame must remain reloadable")
                } else {
                    assertEquals(409, result.status, "$name: ${result.body}")
                    assertEquals("pulse_state_unavailable", reifyMap(result.body)["error"], name)
                    val queued = runs.enqueue(document.name, document, false, inputs, pulse(baseCid))
                    assertNull(queued.b, name)
                    assertEquals(409, assertNotNull(queued.a).status, name)
                    assertEquals(0, independentEffects, name)
                    assertEquals(0, selectedEffects, name)
                    assertEquals(before, store.cards().toList(), "Invalid $name provenance cannot publish a run card")
                }
                assertEquals(ContentId(baseCid), store.card(jobId)?.commandCid, name)
            }
        }
    }

    @Test fun executionProjectionSurvivesCasReopenAndNamedBodyPins() = runBlocking {
        val body = LcncProgram("named", s_[LcncNode("timer", "timer"),
            LcncNode("text", "text.value", mapOf("value" to "retained")),
            LcncNode("event", "scope.out", mapOf("name" to "edge", "kind" to "trigger")),
            LcncNode("data", "scope.out", mapOf("name" to "data", "kind" to "text"))], s_[
            LcncWire("timer", "tick", "event", "value"), LcncWire("text", "value", "data", "value")])
        val document = LcncProgram("origin", s_[
            LcncNode("rootEdge", "scope.in", mapOf("name" to "ambient", "kind" to "trigger")),
            LcncNode("ring", "scope", subprogram = "named"), LcncNode("merge", "merge"),
            LcncNode("button", "button"), LcncNode("consumer", "scope", children = s_[
                LcncNode("edge", "scope.in", mapOf("name" to "edge", "kind" to "trigger")),
                LcncNode("press", "scope.in", mapOf("name" to "press", "kind" to "trigger")),
                LcncNode("effect", "test.effect")])], s_[
            LcncWire("ring", "returns", "merge", "a"), LcncWire("merge", "value", "consumer", "args"),
            LcncWire("button", "press", "consumer", "press"),
            LcncWire("edge", "value", "effect", "edge"), LcncWire("press", "value", "effect", "press")])
        val args = mapOf("ambient" to 9L, "ordinary" to "bound")
        var effects = 0
        lateinit var baseCid: String
        fixture(loader = { if (it == "named") body else null }) { ctx, runs ->
            ctx.lcncRunners["test.effect"] = LcncNodeRunner { _, _ -> effects++; emptyMap() }
            val response = runs.execute("origin", document, false, args, emptyMap<String, Any?>())
            assertEquals(200, response.status, response.body)
            val receipt = reifyMap(response.body)
            baseCid = receipt["receiptCid"] as String
            val rawRing = (receipt["outputs"] as Map<*, *>)["ring"] as Map<*, *>
            assertEquals(1L, (rawRing["edge"] as Number).toLong(), "receipt preserves what actually ran")
            val bytes = assertNotNull(ctx.casStore.get(ContentId(receipt["rootStateCid"] as String)))
            val state = borg.trikeshed.collections.associative.Cbor.Reader(bytes, 0).readItem().toAny() as Map<*, *>
            assertEquals("execution-v4", state["retention"])
            assertIs<Map<*, *>>(state["origins"])
            assertEquals(mapOf("ordinary" to "bound"), state["bindings"])
            val retainedMerge = (state["outputs"] as Map<*, *>)["merge"]
            assertEquals(mapOf("value" to mapOf("data" to "retained")), retainedMerge)
        }
        fixture(loader = { error("pulse must use the retained named-body pin, not a changed live loader") }) { ctx, runs ->
            ctx.lcncRunners["test.effect"] = LcncNodeRunner { _, _ -> effects++; emptyMap() }
            val response = runs.execute("origin", document, false, args, pulse(baseCid))
            assertEquals(200, response.status, response.body)
            val outputs = reifyMap(response.body)["outputs"] as Map<*, *>
            assertEquals(mapOf("value" to mapOf("data" to "retained")), outputs["merge"])
            assertEquals(emptyMap<String, Any?>(), outputs["rootEdge"])
            assertEquals(0, effects, "a reloaded snapshot cannot restore a prior transport's event")
        }
    }

    @org.junit.jupiter.api.io.TempDir lateinit var root: Path

    private suspend fun fixture(loader: suspend (String) -> LcncProgram? = { null },
                                casTransform: (CasStore) -> CasStore = { it },
                                observeStore: (BoardStoreElement) -> Unit = {},
                                body: suspend (ModuleContext, LcncRunService) -> Unit) {
        val owner = SupervisorJob(currentCoroutineContext()[Job])
        val scope = CoroutineScope(currentCoroutineContext() + owner)
        val cas = casTransform(FileCasStore(JvmFileOperations(), root.resolve("cas").toString()))
        val couchStore = CouchStoreFactory.casBacked(cas)
        val ctx = ModuleContext(Couch("pulse", couchStore, cas), ReteNetwork(), ReteProductionRegistry(),
            null, null, ConfixBlackboard.empty(), cas, CouchAttachmentGateway(couchStore, cas),
            ModuleRouteRegistry(), scope, { 1234L }, root.toFile(), programLoader = loader)
        val store = BoardStoreElement(JvmBoardWal(root.resolve("board").toFile()), cas, parentJob = owner)
        store.open()
        observeStore(store)
        val runs = LcncRunService(ctx, store) { LcncContracts.all().associateBy { it.type } }
        ctx.lcncRunners.putAll(PureNodes.registry { 1L })
        try { runs.recover(); body(ctx, runs) }
        finally { runs.drain(); store.drain(); store.close(); owner.cancelAndJoin() }
    }

    private fun program() = LcncProgram("pulse", s_[
        LcncNode("independent", "test.effect"),
        LcncNode("button", "button"),
        LcncNode("consume", "test.consume"),
        LcncNode("yield", "scope.out", mapOf("name" to "value")),
    ], s_[LcncWire("button", "press", "consume", "press"),
        LcncWire("consume", "value", "yield", "value")])

    private fun pulse(base: Any?) = mapOf("baseReceiptCid" to base,
        "pulse" to mapOf("nodeId" to "button", "outputs" to mapOf("press" to 21L)))

    @Test fun aGestureDoesNotReplayAnIndependentEffect() = runBlocking {
        fixture { ctx, runs ->
            var effects = 0
            var gestures = 0
            ctx.lcncRunners["test.effect"] = LcncNodeRunner { _, _ -> effects++; mapOf("value" to effects) }
            ctx.lcncRunners["test.consume"] = LcncNodeRunner { _, inputs -> gestures++; mapOf("value" to inputs.getValue("press")) }
            val first = runs.execute("pulse", program(), false, emptyMap(), emptyMap<String, Any?>())
            assertEquals(200, first.status, first.body)
            assertEquals(1, effects)
            assertEquals(0, gestures)
            val result = runs.execute("pulse", program(), false, emptyMap(), pulse(reifyMap(first.body)["receiptCid"]))
            assertEquals(200, result.status, result.body)
            assertEquals(1, effects, "a partial gesture must reuse, not execute, the independent root")
            assertEquals(1, gestures, "the admitted button edge reaches its branch exactly once")
            assertEquals(21L, ((reifyMap(result.body)["returns"] as Map<*, *>)["value"] as Number).toLong())
        }
    }

    @Test fun aPulseWithoutRetainedEvidenceIsRefusedBeforeEffects() = runBlocking {
        fixture { ctx, runs ->
            var effects = 0
            ctx.lcncRunners["test.effect"] = LcncNodeRunner { _, _ -> effects++; emptyMap() }
            val result = runs.execute("pulse", program(), false, emptyMap(), pulse(null))
            assertEquals(400, result.status, result.body)
            assertEquals("invalid_pulse", reifyMap(result.body)["error"])
            assertEquals(0, effects, "an invalid pulse cannot fall back to a full walk")
        }
    }

    @Test fun queuedSelectionSurvivesWalAndCasReopen() = runBlocking {
        var effects = 0
        var gestures = 0
        lateinit var baseCid: String
        fixture { ctx, runs ->
            ctx.lcncRunners["test.effect"] = LcncNodeRunner { _, _ -> effects++; mapOf("value" to effects) }
            ctx.lcncRunners["test.consume"] = LcncNodeRunner { _, inputs -> gestures++; mapOf("value" to inputs["press"]) }
            val first = runs.execute("pulse", program(), false, emptyMap(), emptyMap<String, Any?>())
            assertEquals(200, first.status, first.body)
            baseCid = reifyMap(first.body)["receiptCid"] as String
            val queued = runs.enqueue("pulse", program(), false, emptyMap(), pulse(baseCid))
            assertNull(queued.a)
            val receipt = runs.queuedReceipt(assertNotNull(queued.b))
            val request = receipt["queueRequest"] as Map<*, *>
            assertEquals(baseCid, request["baseReceiptCid"])
            assertEquals(baseCid, receipt["baseReceiptCid"])
            assertEquals(request["pulse"], receipt["pulse"])
            // JSON's numeric boundary is Double, not the caller's JVM Number subtype.
            assertEquals(mapOf("nodeId" to "button", "outputs" to mapOf("press" to 21.0)), request["pulse"])
        }
        fixture { ctx, runs ->
            ctx.lcncRunners["test.effect"] = LcncNodeRunner { _, _ -> error("independent effect replayed after recovery") }
            ctx.lcncRunners["test.consume"] = LcncNodeRunner { _, inputs -> gestures++; mapOf("value" to inputs["press"]) }
            val entry = runs.reserveQueued(runs.queuedRecovery().view.single())
            val group = Workgroup("pulse-test", Subnet.parse("local"), TraitSpace.EMPTY)
            val claim = NuidFanoutElement.Claim(1L, nuid(Capability.Custom("lcnc", "pulse-test"),
                Nonce.RandomBytes(), group.scope), entry)
            try {
                val response = runs.executeQueued(entry, group j claim)
                assertEquals(200, response.status, response.body)
                val result = reifyMap(response.body)
                assertEquals(baseCid, result["baseReceiptCid"])
                assertNotNull(result["rootStateCid"])
                assertEquals(21L, ((result["returns"] as Map<*, *>)["value"] as Number).toLong())
            } finally { runs.releaseQueued(entry) }
        }
        assertEquals(1, effects)
        assertEquals(1, gestures)
    }

    @Test fun definitionAndArgumentChangesCannotReuseOldOutputs() = runBlocking {
        fixture { ctx, runs ->
            var effects = 0
            ctx.lcncRunners["test.effect"] = LcncNodeRunner { _, _ -> effects++; mapOf("value" to effects) }
            ctx.lcncRunners["test.consume"] = LcncNodeRunner { _, inputs -> mapOf("value" to inputs["press"]) }
            val original = program()
            val first = runs.execute("pulse", original, false, emptyMap(), emptyMap<String, Any?>())
            val request = pulse(reifyMap(first.body)["receiptCid"])
            val changed = original.copy(nodes = original.nodes α { it.copy(params = it.params + ("changed" to "true")) })
            val drift = runs.execute("pulse", changed, false, emptyMap(), request)
            assertEquals(409, drift.status, drift.body)
            assertEquals("pulse_definition_drift", reifyMap(drift.body)["error"])
            val arguments = runs.execute("pulse", original, false, mapOf("other" to 1), request)
            assertEquals(409, arguments.status, arguments.body)
            assertEquals("pulse_invocation_drift", reifyMap(arguments.body)["error"])
            val moved = original.copy(nodes = original.nodes α { it.copy(x = it.x + 10, collapsed = true) })
            val layout = runs.execute("pulse", moved, false, emptyMap(), request)
            assertEquals(200, layout.status, layout.body)
            assertEquals(1, effects, "presentation-only changes may reuse an otherwise identical frame")
        }
    }

    @Test fun rebuildingAPulsePreservesItsSelection() = runBlocking {
        fixture { ctx, _ ->
            var effects = 0
            var gestures = 0
            ctx.lcncRunners["test.effect"] = LcncNodeRunner { _, _ -> effects++; mapOf("value" to effects) }
            ctx.lcncRunners["test.consume"] = LcncNodeRunner { _, inputs -> gestures++; mapOf("value" to inputs["press"]) }
            val supervisor = ModuleSupervisor(ctx)
            supervisor.attach(KanbanModule())
            try {
                val server = JvmKanbanServer(moduleRoutes = ctx.routes)
                suspend fun post(path: String, body: Map<String, Any?>): Map<String, Any?> {
                    val json = jsonOf(body)
                    val result = server.routeHttp(("POST $path HTTP/1.1\r\nHost: t\r\n" +
                        "Content-Length: ${json.encodeToByteArray().size}\r\n\r\n$json").encodeToByteArray())
                    assertEquals(200, result.status, result.body)
                    return reifyMap(result.body)
                }
                val request = mapOf("name" to "pulse", "document" to reify(LcncProgramConfix.toJson(program())))
                val base = post("/api/lcnc/run", request)
                val event = post("/api/lcnc/run", request + pulse(base["receiptCid"]))
                val rebuilt = post("/api/lcnc/run/rebuild", mapOf("runId" to event["runId"]))
                assertEquals(1, effects, "rebuild must preserve selection, not widen a gesture into a full run")
                assertEquals(2, gestures, "explicit rebuild repeats only the selected gesture")
                assertEquals(base["receiptCid"], rebuilt["baseReceiptCid"])
                assertEquals(event["pulse"], rebuilt["pulse"])
                assertEquals(event["receiptCid"], rebuilt["rebuildOf"])
            } finally { supervisor.detach("kanban") }
        }
    }

    @Test fun anonymousRootNameCannotDiscardASubprogramPin() = runBlocking {
        var version = "v1"
        var calls = 0
        fixture(loader = { name -> LcncProgram(name,
            s_[LcncNode("child", "test.effect", mapOf("version" to version))], emptySeriesOf()) }) { ctx, runs ->
            ctx.lcncRunners["test.effect"] = LcncNodeRunner { node, _ -> calls++; mapOf("value" to node.params["version"]) }
            val document = LcncProgram("pulse", s_[LcncNode("button", "button"),
                LcncNode("call", "scope", subprogram = "pulse")], s_[LcncWire("button", "press", "call", "press")])
            val base = runs.execute("pulse", document, false, emptyMap(), emptyMap<String, Any?>())
            assertEquals(200, base.status, base.body)
            assertEquals(1, calls)
            version = "v2"
            val result = runs.execute("pulse", document, false, emptyMap(), pulse(reifyMap(base.body)["receiptCid"]))
            assertEquals(409, result.status, result.body)
            assertEquals("pulse_program_name_collision", reifyMap(result.body)["error"])
            assertEquals(1, calls, "ambiguous root/subprogram identity must be refused before effects")
        }
    }

    @Test fun retainedStateIsNotATruncatedReceiptPreview() = runBlocking {
        fixture { ctx, runs ->
            var effects = 0
            ctx.lcncRunners["test.effect"] = LcncNodeRunner { _, _ -> effects++; mapOf("value" to "x".repeat(200000)) }
            ctx.lcncRunners["test.consume"] = LcncNodeRunner { _, input -> mapOf("value" to (input["text"] as String).length) }
            val document = program().copy(wires = (program().wires.view +
                LcncWire("independent", "value", "consume", "text")).toSeries())
            val base = runs.execute("pulse", document, false, emptyMap(), emptyMap<String, Any?>())
            assertEquals(200, base.status, base.body)
            val body = reifyMap(base.body)
            assertNotNull(((body["outputs"] as Map<*, *>)["independent"] as Map<*, *>)["truncated"])
            assertNotNull(body["rootStateCid"])
            val result = runs.execute("pulse", document, false, emptyMap(), pulse(body["receiptCid"]))
            assertEquals(200, result.status, result.body)
            assertEquals(200000.0, (reifyMap(result.body)["returns"] as Map<*, *>)["value"])
            assertEquals(1, effects)
        }
    }

    @Test fun retainedIntegerIdentitySurvivesTheCodecBoundary() = runBlocking {
        fixture { ctx, runs ->
            val identity = 9007199254740993L
            var effects = 0
            ctx.lcncRunners["test.effect"] = LcncNodeRunner { _, _ -> effects++; mapOf("value" to identity) }
            ctx.lcncRunners["test.consume"] = LcncNodeRunner { _, input ->
                mapOf("value" to (input["identity"] as Number).toLong().toString())
            }
            val document = program().copy(wires = (program().wires.view +
                LcncWire("independent", "value", "consume", "identity")).toSeries())
            val base = runs.execute("pulse", document, false, emptyMap(), emptyMap<String, Any?>())
            assertEquals(200, base.status, base.body)
            val result = runs.execute("pulse", document, false, emptyMap(), pulse(reifyMap(base.body)["receiptCid"]))
            assertEquals(200, result.status, result.body)
            assertEquals(identity.toString(), (reifyMap(result.body)["returns"] as Map<*, *>)["value"])
            assertEquals("cbor", reifyMap(base.body)["rootStateFormat"])
            assertEquals(1, effects)
        }
    }

    @Test fun oversizedStateIsUnavailableRatherThanAPartialFrame() = runBlocking {
        fixture { ctx, runs ->
            var effects = 0
            ctx.lcncRunners["test.consume"] = LcncNodeRunner { _, _ -> error("unadmitted gesture executed") }
            ctx.lcncRunners["test.effect"] = LcncNodeRunner { _, _ -> effects++; mapOf("value" to "x".repeat(600000)) }
            val base = runs.execute("pulse", program(), false, emptyMap(), emptyMap<String, Any?>())
            assertEquals(200, base.status, base.body)
            val body = reifyMap(base.body)
            assertNull(body["rootStateCid"])
            assertEquals("payload_limit", body["rootStateUnavailable"])
            val result = runs.execute("pulse", program(), false, emptyMap(), pulse(body["receiptCid"]))
            assertEquals(409, result.status, result.body)
            assertEquals("pulse_state_unavailable", reifyMap(result.body)["error"])
            assertEquals(1, effects)
        }
    }

    @Test fun cyclicAndDeepOutputsCompleteWithoutExportingPartialPulseState(): Unit = runBlocking {
        for (cyclic in listOf(false, true)) fixture { ctx, runs ->
            var effects = 0
            val value: Any? = if (cyclic) mutableMapOf<String, Any?>().also { it["self"] = it }
                else {
                    var nested: Any? = "leaf"
                    repeat(256) { nested = listOf(nested) }
                    nested
                }
            ctx.lcncRunners["test.consume"] = LcncNodeRunner { _, _ -> error("unadmitted gesture executed") }
            ctx.lcncRunners["test.effect"] = LcncNodeRunner { _, _ -> effects++; mapOf("value" to value) }
            val base = runs.execute("pulse", program(), false, emptyMap(), emptyMap<String, Any?>())
            assertEquals(200, base.status, base.body)
            val body = reifyMap(base.body)
            assertEquals("completed", body["status"])
            assertNull(body["rootStateCid"])
            assertEquals("depth_limit", body["rootStateUnavailable"])
            val result = runs.execute("pulse", program(), false, emptyMap(), pulse(body["receiptCid"]))
            assertEquals(409, result.status, result.body)
            assertEquals("pulse_state_unavailable", reifyMap(result.body)["error"])
            assertEquals(1, effects)
        }
    }

    @Test fun missingQueuedStateLeavesATerminalPulseReceipt() = runBlocking {
        var hidden: ContentId? = null
        fixture(casTransform = { backing -> object : CasStore() {
            override val durability get() = backing.durability
            override fun put(bytes: ByteArray) = backing.put(bytes)
            override fun sync() = backing.sync()
            override fun get(cid: ContentId) = if (cid == hidden) null else backing.get(cid)
        } }) { ctx, runs ->
            var effects = 0
            ctx.lcncRunners["test.consume"] = LcncNodeRunner { _, _ -> error("unadmitted gesture executed") }
            ctx.lcncRunners["test.effect"] = LcncNodeRunner { _, _ -> effects++; emptyMap() }
            val initial = runs.execute("pulse", program(), false, emptyMap(), emptyMap<String, Any?>())
            assertEquals(200, initial.status, initial.body)
            val base = reifyMap(initial.body)
            val queued = runs.enqueue("pulse", program(), false, emptyMap(), pulse(base["receiptCid"]))
            assertNull(queued.a)
            val entry = runs.reserveQueued(assertNotNull(queued.b))
            hidden = ContentId(base["rootStateCid"] as String)
            val group = Workgroup("pulse-test", Subnet.parse("local"), TraitSpace.EMPTY)
            val claim = NuidFanoutElement.Claim(1L, nuid(Capability.Custom("lcnc", "pulse-test"),
                Nonce.RandomBytes(), group.scope), entry)
            try {
                val result = runs.executeQueued(entry, group j claim)
                assertEquals(404, result.status, result.body)
                val receipt = reifyMap(result.body)
                assertEquals("refused", receipt["status"])
                assertEquals("pulse_content_not_found", receipt["error"])
                assertEquals(base["receiptCid"], receipt["baseReceiptCid"])
                assertNotNull(receipt["pulse"])
                assertEquals(1, effects)
                assertEquals(receipt["receiptCid"], (ctx.blackboard.get(entry.a.jobId) as Map<*, *>)["receiptCid"])
            } finally { runs.releaseQueued(entry) }
        }
    }

    private suspend fun acknowledge(store: BoardStoreElement, receipt: Map<String, Any?>) {
        val reply = CompletableDeferred<BoardApply>()
        store.intake.send(BoardIntake(mapOf("type" to "acknowledge", "jobId" to receipt["jobId"],
            "owner" to "lcnc-runner", "expectedRevision" to receipt["timelineRevision"],
            "idempotencyKey" to "base-after-pulse-queue", "lcncRun" to receipt), reply))
        val committed = assertIs<BoardApply.Committed>(reply.await())
        assertNotEquals(receipt["receiptCid"], committed.cid.value)
        assertEquals(committed.cid, store.card(receipt["jobId"] as String)?.commandCid)
    }

    private suspend fun executeQueued(runs: LcncRunService, pending: LcncRunQueueEntry): JvmKanbanServer.HttpResponse {
        val entry = runs.reserveQueued(pending)
        val group = Workgroup("pulse-history", Subnet.parse("local"), TraitSpace.EMPTY)
        val claim = NuidFanoutElement.Claim(1L, nuid(Capability.Custom("lcnc", "pulse-history"),
            Nonce.RandomBytes(), group.scope), entry)
        return try { runs.executeQueued(entry, group j claim) }
        finally { runs.releaseQueued(entry) }
    }

    @Test fun anAcceptedPulseKeepsItsImmutableBaseAfterAcknowledgmentAndRecovery() = runBlocking {
        var effects = 0
        var gestures = 0
        lateinit var base: Map<String, Any?>
        lateinit var queuedCid: String
        lateinit var store: BoardStoreElement
        fixture(observeStore = { store = it }) { ctx, runs ->
            ctx.lcncRunners["test.effect"] = LcncNodeRunner { _, _ -> effects++; mapOf("value" to "retained") }
            ctx.lcncRunners["test.consume"] = LcncNodeRunner { _, input -> gestures++; mapOf("value" to input["press"]) }
            val initial = runs.execute("pulse", program(), false, emptyMap(), emptyMap<String, Any?>())
            assertEquals(200, initial.status, initial.body)
            base = reifyMap(initial.body)
            val pending = runs.enqueue("pulse", program(), false, emptyMap(), pulse(base["receiptCid"]))
            assertNull(pending.a, pending.a?.body)
            queuedCid = runs.queuedReceipt(assertNotNull(pending.b))["receiptCid"] as String
            acknowledge(store, base)
            val fresh = runs.enqueue("pulse", program(), false, emptyMap(), pulse(base["receiptCid"]) +
                mapOf("retainedBase" to true, "retainedPulseBase" to true, "currentHead" to false))
            assertNull(fresh.b, "A new public request cannot authorize an obsolete base head")
            assertEquals(409, assertNotNull(fresh.a).status)
            assertEquals("source_receipt_not_committed", reifyMap(assertNotNull(fresh.a).body)["error"])
            assertEquals(0, gestures)
        }
        fixture { ctx, runs ->
            ctx.lcncRunners["test.effect"] = LcncNodeRunner { _, _ -> error("The retained independent effect was replayed") }
            ctx.lcncRunners["test.consume"] = LcncNodeRunner { _, input -> gestures++; mapOf("value" to input["press"]) }
            val pending = runs.queuedRecovery().view.single()
            assertEquals(queuedCid, pending.a.cid.value)
            val response = executeQueued(runs, pending)
            assertEquals(200, response.status, response.body)
            val terminal = reifyMap(response.body)
            assertEquals("completed", terminal["status"])
            assertEquals(base["receiptCid"], terminal["baseReceiptCid"])
            assertEquals(21L, ((terminal["returns"] as Map<*, *>)["value"] as Number).toLong())
            assertEquals(listOf(mapOf("kind" to "lcnc-run", "id" to base["jobId"], "cid" to base["receiptCid"])), terminal["consumed"])
            assertEquals(LcncConsumedLedger().fingerprintOf(listOf(base["receiptCid"] as String)), terminal["inputFingerprint"])
            assertEquals(terminal["receiptCid"], (ctx.blackboard.get(pending.a.jobId) as Map<*, *>)["receiptCid"])
        }
        assertEquals(1, effects)
        assertEquals(1, gestures)
    }

    @Test fun anAuthenticatedPulseRebuildKeepsTheBaseItsCommittedPredecessorAlreadyAdmitted() = runBlocking {
        lateinit var store: BoardStoreElement
        fixture(observeStore = { store = it }) { ctx, runs ->
            var effects = 0
            var gestures = 0
            ctx.lcncRunners["test.effect"] = LcncNodeRunner { _, _ -> effects++; mapOf("value" to "retained") }
            ctx.lcncRunners["test.consume"] = LcncNodeRunner { _, input -> gestures++; mapOf("value" to input["press"]) }
            val initial = runs.execute("pulse", program(), false, emptyMap(), emptyMap<String, Any?>())
            assertEquals(200, initial.status, initial.body)
            val base = reifyMap(initial.body)
            val event = runs.execute("pulse", program(), false, emptyMap(), pulse(base["receiptCid"]))
            assertEquals(200, event.status, event.body)
            val original = reifyMap(event.body)
            acknowledge(store, base)
            val fresh = runs.execute("pulse", program(), false, emptyMap(), pulse(base["receiptCid"]))
            assertEquals(409, fresh.status, fresh.body)
            assertEquals("source_receipt_not_committed", reifyMap(fresh.body)["error"])
            val prepared = runs.enqueueRebuild(ContentId(original["receiptCid"] as String))
            assertNull(prepared.a, prepared.a?.body)
            val response = executeQueued(runs, assertNotNull(prepared.b))
            assertEquals(200, response.status, response.body)
            val rebuilt = reifyMap(response.body)
            assertEquals(original["receiptCid"], rebuilt["rebuildOf"])
            assertEquals(original["runId"], rebuilt["rebuildOfRunId"])
            assertEquals(base["receiptCid"], rebuilt["baseReceiptCid"])
            assertEquals(original["pulse"], rebuilt["pulse"])
            assertEquals(original["returns"], rebuilt["returns"])
            assertEquals(original["consumed"], rebuilt["consumed"])
            assertEquals(1, effects, "A rebuilt selection cannot rerun its independent source effect")
            assertEquals(2, gestures, "The original and explicit rebuild each execute the selected branch once")
        }
    }
}
