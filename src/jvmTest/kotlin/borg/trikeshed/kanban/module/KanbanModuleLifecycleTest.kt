package borg.trikeshed.kanban.module

import borg.trikeshed.couch.*
import borg.trikeshed.dag.*
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.*
import borg.trikeshed.kanban.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.module.*
import borg.trikeshed.parse.*
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlin.test.*

/** Real module/Rete/store paths, with only the external AGENT boundary held by a gate. */
class KanbanModuleLifecycleTest {
    private class HiddenCas : CasStore() {
        var hidden: ContentId? = null
        override fun get(cid: ContentId): ByteArray? = if (cid == hidden) null else super.get(cid)
    }

    private class FailingSyncCas : CasStore() {
        val failure = IllegalStateException("module CAS sync failed")
        @Volatile var failing = false
        override fun sync() {
            if (failing) throw failure
            super.sync()
        }
    }

    private class HeldAgent(private val failure: Throwable? = null) : AgentRuns {
        val entered = CompletableDeferred<AgentRunRequest>()
        val release = CompletableDeferred<Unit>()
        override suspend fun roster() = listOf(AgentCliInfo("held", "fixture", "1", enabled = true))
        override suspend fun run(request: AgentRunRequest): AgentRunResult {
            entered.complete(request)
            release.await()
            failure?.let { throw it }
            return AgentRunResult(request.runId, request.jobId, request.agent,
                exit = 0, summary = "VERDICT: NEEDS-HUMAN\nACTION: A person must review the collected evidence.")
        }
    }

    private class Rig(val ctx: ModuleContext, val directory: File) {
        val supervisor = ModuleSupervisor(ctx)
        val server = JvmKanbanServer(moduleRoutes = ctx.routes)
        suspend fun dispose() {
            try { runCatching { supervisor.detach("kanban") } }
            finally {
                ctx.scope.coroutineContext.job.cancelAndJoin()
                directory.deleteRecursively()
            }
        }
    }

    private fun fixture(cas: CasStore = CasStore.inMemory(), agent: AgentRuns? = null): Rig {
        val directory = Files.createTempDirectory("kanban-lifecycle-").toFile()
        val couchStore = CouchStoreFactory.casBacked(cas)
        val ctx = ModuleContext(
            couch = Couch("kanban-lifecycle", couchStore, cas),
            rete = ReteNetwork(), productions = ReteProductionRegistry(),
            beliefBag = null, turnReview = null, blackboard = ConfixBlackboard.empty(),
            casStore = cas, attachments = CouchAttachmentGateway(couchStore, cas),
            routes = ModuleRouteRegistry(), scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            clock = { 1234L }, stateDir = directory, agentRuns = agent,
        )
        ctx.lcncRunners["mux.models"] = LcncNodeRunner { _, _ -> mapOf("models" to emptyList<Any>()) }
        return Rig(ctx, directory)
    }

    private suspend fun submit(rig: Rig, jobId: String) {
        val response = rig.server.routeHttp((
            "POST /api/invoke HTTP/1.1\r\nHost: t\r\nContent-Type: application/json\r\n\r\n" +
                jsonOf(mapOf("commands" to listOf(
                    mapOf("type" to "submit", "jobId" to jobId, "idempotencyKey" to "$jobId-submit",
                        "title" to "Review the held evidence", "tags" to listOf("human-review"),
                        "spec" to "GOAL: collect evidence\nMUST: a person reviews the evidence\nAGENT: held"),
                    mapOf("type" to "move", "jobId" to jobId, "idempotencyKey" to "$jobId-ready",
                        "expectedRevision" to 1L, "toColumn" to "ready"),
                )))).encodeToByteArray())
        assertEquals(202, response.status, response.body)
        val results = assertIs<List<*>>(reifyMap(response.body)["results"])
        assertEquals(2, results.size)
        assertTrue(results.all { (it as? Map<*, *>)?.get("verdict") == "committed" }, response.body)
    }

    private fun causes(failure: Throwable): List<Throwable> {
        val seen = mutableListOf<Throwable>()
        fun visit(current: Throwable) {
            if (seen.any { it === current }) return
            seen.add(current)
            current.cause?.let(::visit)
            current.suppressedExceptions.forEach(::visit)
        }
        visit(failure)
        return seen
    }

    private suspend fun assertReleased(rig: Rig) {
        assertTrue(rig.ctx.routes.paths().isEmpty())
        assertNull(rig.ctx.rete.productionSink)
        assertTrue(rig.ctx.rete.snapshot().view.none { it.factId.a == "board" })
        assertTrue(rig.ctx.scope.coroutineContext.job.children.none(),
            "Module owners must finish before the fixture's failure-guard cancellation")
    }

    @Test fun installedRunnersPreserveRetainedValuesThroughQueuedPulses(): Unit = runBlocking {
        for (type in listOf("merge", "coalesce", "pick")) {
            val rig = fixture()
            try {
                rig.ctx.lcncRunners.putAll(PureNodes.registry { 1L })
                rig.ctx.lcncRunners.putAll(CouncilNodes.registry(CouncilDialog {
                    error("A data projection cannot invoke a model")
                }))
                rig.supervisor.attach(KanbanModule())
                var sources = 0
                var effects = 0
                rig.ctx.lcncRunners["test.source"] = LcncNodeRunner { _, _ -> sources++; mapOf("value" to 1L) }
                rig.ctx.lcncRunners["test.effect"] = LcncNodeRunner { _, _ -> effects++; emptyMap() }
                val inputPort = when (type) { "pick" -> "x"; "coalesce" -> "b"; else -> "a" }
                val outputPort = if (type == "pick") "y" else "value"
                val program = LcncProgram("installed-$type", s_[
                    LcncNode("producer", "scope", children = s_[
                        LcncNode("source", "test.source"),
                        LcncNode("text", "text.value", mapOf("value" to "retained")),
                        LcncNode("edge", "scope.out", mapOf("name" to "edge", "kind" to "trigger")),
                        LcncNode("data", "scope.out", mapOf("name" to "data", "kind" to "text"))]),
                    LcncNode("select", type, mapOf("path" to "")), LcncNode("button", "button"),
                    LcncNode("consumer", "scope", children = s_[
                        LcncNode("inputEdge", "scope.in", mapOf("name" to "edge", "kind" to "trigger")),
                        LcncNode("inputPress", "scope.in", mapOf("name" to "press", "kind" to "trigger")),
                        LcncNode("effect", "test.effect")])], s_[
                    LcncWire("source", "value", "edge", "value"), LcncWire("text", "value", "data", "value"),
                    LcncWire("producer", "returns", "select", inputPort),
                    LcncWire("select", outputPort, "consumer", "args"),
                    LcncWire("button", "press", "consumer", "press"),
                    LcncWire("inputEdge", "value", "effect", "edge"),
                    LcncWire("inputPress", "value", "effect", "press")])
                assertTrue(LcncTypeCheck.check(program, strict = false).isEmpty())
                val document = reifyMap(LcncProgramConfix.toJson(program))
                suspend fun request(base: String? = null): Map<String, Any?> {
                    val body = linkedMapOf<String, Any?>("name" to program.name, "document" to document)
                    if (base != null) {
                        body["baseReceiptCid"] = base
                        body["pulse"] = mapOf("nodeId" to "button", "outputs" to mapOf("press" to 2L))
                    }
                    val response = withTimeout(10_000) { rig.server.routeHttp((
                        "POST /api/lcnc/run HTTP/1.1\r\nHost: t\r\nContent-Type: application/json\r\n\r\n" + jsonOf(body)
                    ).encodeToByteArray()) }
                    assertEquals(200, response.status, response.body)
                    return reifyMap(response.body)
                }
                var result = request()
                assertEquals(0, effects)
                val initial = assertIs<Map<*, *>>((result["outputs"] as Map<*, *>)["select"], "$type: ${jsonOf(result)}")
                assertEquals(1L, (assertIs<Map<*, *>>(initial[outputPort])["edge"] as Number).toLong())
                repeat(2) {
                    result = request(result["receiptCid"] as String)
                    assertEquals(0, effects, "$type must expire events through the installed registry and CAS state")
                    assertEquals(1, sources, "A queued pulse must retain the independent producer")
                    val output = assertIs<Map<*, *>>((result["outputs"] as Map<*, *>)["select"])
                    assertEquals(mapOf(outputPort to mapOf("data" to "retained")), output)
                }
                assertTrue(rig.supervisor.detach("kanban"))
                assertReleased(rig)
            } finally { rig.dispose() }
        }
    }

    @Test fun failedQueuedRecoveryReleasesEveryOpenedOwnerAndRestoresRunnerBindings() = runBlocking {
        val cas = HiddenCas()
        val rig = fixture(cas)
        val original = LcncNodeRunner { _, _ -> mapOf("original" to true) }
        rig.ctx.lcncRunners["board.get"] = original
        val before = rig.ctx.lcncRunners.toMap()
        val program = LcncProgram("missing-root", s_[LcncNode("clock", "timer")], emptySeriesOf())
        val programCid = ContentId.of(LcncProgramConfix.toJson(program).encodeToByteArray())
        try {
            val seed = BoardStoreElement(JvmBoardWal(File(rig.directory, ".kanban")), cas)
            seed.open()
            try {
                val service = LcncRunService(rig.ctx, seed) { LcncContracts.all().associateBy { it.type } }
                val queued = service.enqueue(program.name, program, false, emptyMap(), mapOf("subnet" to "local"))
                assertNull(queued.a)
                assertNotNull(queued.b)
                assertNotNull(cas.get(programCid))
            } finally { seed.drain() }
            cas.hidden = programCid

            val failure = assertFailsWith<Exception> {
                withTimeout(10_000) { rig.supervisor.attach(KanbanModule()) }
            }
            assertTrue(causes(failure).any {
                it is LcncRunService.QueueFailure && it.code == "queued_content_not_found"
            }, failure.stackTraceToString())
            assertEquals(before.keys, rig.ctx.lcncRunners.keys)
            before.forEach { (type, runner) -> assertSame(runner, rig.ctx.lcncRunners[type], type) }
            assertReleased(rig)
        } finally { rig.dispose() }
    }

    @Test fun detachJoinsARealAgentClaimThroughItsReviewCommitAndReceiptProjection(): Unit = runBlocking {
        val agent = HeldAgent()
        val rig = fixture(agent = agent)
        try {
            rig.supervisor.attach(KanbanModule())
            val captured = assertNotNull(rig.ctx.lcncRunners["board.get"])
            submit(rig, "held-review")
            val request = withTimeout(10_000) { agent.entered.await() }
            val detached = async(start = CoroutineStart.UNDISPATCHED) { rig.supervisor.detach("kanban") }
            assertFalse(detached.isCompleted, "The admitted claim still owns a held external result")
            val refused = rig.server.routeHttp("GET /api/board HTTP/1.1\r\nHost: t\r\n\r\n".encodeToByteArray())
            assertEquals(503, refused.status, refused.body)
            agent.release.complete(Unit)
            assertTrue(withTimeout(10_000) { detached.await() })

            val receipt = assertIs<Map<*, *>>(rig.ctx.blackboard.get("kanban/claim/held-review"))
            assertEquals(request.runId, receipt["runId"])
            assertEquals("held", receipt["agent"])
            assertEquals(true, receipt["ok"])
            assertEquals("NEEDS-HUMAN", receipt["verdict"])
            assertEquals("REVIEW", receipt["decision"])
            val trail = rig.ctx.blackboard.keys().filter { it.startsWith("kanban/committed/held-review/") }
                .map { assertIs<Map<*, *>>(rig.ctx.blackboard.get(it)) }
                .sortedBy { it["revision"].toString().toLong() }.map { it["col"] }
            assertEquals(listOf("todo", "ready", "running", "review"), trail)
            assertReleased(rig)
            assertFailsWith<BoardActionElement.AdmissionFailure> {
                captured.execute(LcncNode("captured", "board.get"), emptyMap())
            }

            val reopened = BoardStoreElement(JvmBoardWal(File(rig.directory, ".kanban")), rig.ctx.casStore)
            reopened.open()
            try {
                val card = assertNotNull(reopened.card("held-review"))
                assertEquals(BoardCol.REVIEW, card.col)
                assertEquals(4L, card.revision)
                assertNotNull(rig.ctx.casStore.get(assertNotNull(card.commandCid)))
            } finally { reopened.drain() }
        } finally { agent.release.complete(Unit); rig.dispose() }
    }

    @Test fun actionFailureStillReleasesObservationFactsRoutesAndStore() = runBlocking {
        val agent = HeldAgent(CancellationException("held agent failed independently"))
        val rig = fixture(agent = agent)
        try {
            rig.supervisor.attach(KanbanModule())
            submit(rig, "failed-action")
            withTimeout(10_000) { agent.entered.await() }
            supervisorScope {
                val detached = async(start = CoroutineStart.UNDISPATCHED) { rig.supervisor.detach("kanban") }
                assertFalse(detached.isCompleted)
                agent.release.complete(Unit)
                val failure = assertFailsWith<IllegalStateException> {
                    withTimeout(10_000) { detached.await() }
                }
                assertTrue(causes(failure).any {
                    it is CancellationException && it.message == "held agent failed independently"
                }, failure.stackTraceToString())
            }
            assertReleased(rig)
            val reopened = BoardStoreElement(JvmBoardWal(File(rig.directory, ".kanban")), rig.ctx.casStore)
            reopened.open()
            try {
                assertEquals(BoardCol.RUNNING, assertNotNull(reopened.card("failed-action")).col)
            } finally { reopened.drain() }
        } finally { agent.release.complete(Unit); rig.dispose() }
    }

    @Test fun invokeSyncFailureIsRetainedByDetachAfterEveryOwnerIsReleased() = runBlocking {
        val cas = FailingSyncCas()
        val rig = fixture(cas)
        val original = LcncNodeRunner { _, _ -> mapOf("original" to true) }
        rig.ctx.lcncRunners["board.get"] = original
        val before = rig.ctx.lcncRunners.toMap()
        try {
            rig.supervisor.attach(KanbanModule())
            cas.failing = true
            val requestFailure = assertFailsWith<IllegalStateException> {
                withTimeout(10_000) {
                    rig.server.routeHttp((
                        "POST /api/invoke HTTP/1.1\r\nHost: t\r\nContent-Type: application/json\r\n\r\n" +
                            jsonOf(mapOf("commands" to listOf(mapOf(
                                "type" to "submit", "jobId" to "failed-sync",
                                "idempotencyKey" to "failed-sync-submit", "title" to "Unacknowledged work",
                            ))))
                    ).encodeToByteArray())
                }
            }
            assertEquals("module CAS sync failed", requestFailure.message)
            val board = rig.server.routeHttp("GET /api/board HTTP/1.1\r\nHost: t\r\n\r\n".encodeToByteArray())
            assertEquals(200, board.status, board.body)
            assertTrue(assertIs<List<*>>(reifyMap(board.body)["items"]).isEmpty())
            assertTrue(rig.ctx.blackboard.keys().none { it.startsWith("kanban/committed/failed-sync/") })

            val detachFailure = assertFailsWith<IllegalStateException> {
                withTimeout(10_000) { rig.supervisor.detach("kanban") }
            }
            assertTrue(causes(detachFailure).any {
                it is IllegalStateException && it.message == "module CAS sync failed"
            }, detachFailure.stackTraceToString())
            assertReleased(rig)
            assertEquals(before.keys, rig.ctx.lcncRunners.keys)
            before.forEach { (type, runner) -> assertSame(runner, rig.ctx.lcncRunners[type], type) }
        } finally { rig.dispose() }
    }
}
