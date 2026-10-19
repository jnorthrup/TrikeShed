package borg.trikeshed.kanban.module

import borg.trikeshed.agent.*
import borg.trikeshed.context.nuid.*
import borg.trikeshed.couch.*
import borg.trikeshed.dag.*
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lcnc.ccek.*
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.module.*
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.channels.spi.JvmProcessProtocolPipes
import borg.trikeshed.userspace.nio.file.spi.*
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import borg.trikeshed.userspace.nio.process.*
import borg.trikeshed.userspace.reactor.MuxReactorElement
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class LcncSubnetRouteTest {
    class Host(val name: String) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Host>
    }

    data class Observation(
        val claim: Long,
        val programCid: String,
        val subnet: String,
        val host: Host,
        val clock: Long,
        val depth: Int,
    )

    class Rig(
        val ctx: ModuleContext,
        val supervisor: ModuleSupervisor,
        val handle: ModuleHandle,
        val reactor: MuxReactorElement,
    ) {
        val server = JvmKanbanServer(moduleRoutes = ctx.routes)
        suspend fun close() {
            try { supervisor.detach("kanban") }
            finally { try { reactor.drain() } finally { ctx.scope.coroutineContext.job.cancelAndJoin() } }
        }
    }

    private suspend fun fixture(
        directory: File = Files.createTempDirectory("lcnc-subnet-").toFile(),
        cas: CasStore = CasStore.inMemory(),
        programs: Map<String, LcncProgram> = emptyMap(),
        stored: List<LcncProgram> = emptyList(),
        moduleHost: Host = Host("module"),
        muxHost: Host = Host("mux"),
    ): Rig {
        val store = CouchStoreFactory.casBacked(cas)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + moduleHost + LcncClockKey { 2L })
        val reactor = MuxReactorElement(parentJob = scope.coroutineContext.job)
        reactor.open()
        val blackboard = ConfixBlackboard.empty()
        for (program in stored) blackboard.put(LcncBlackboard.programKey(program.name),
            LcncBlackboard.programEntry(program.name, program, LcncContracts.all().associateBy { it.type }), "test")
        val ctx = ModuleContext(
            couch = Couch("lcnc-subnet-test", store, cas), rete = ReteNetwork(), productions = ReteProductionRegistry(),
            beliefBag = null, turnReview = null, blackboard = blackboard, casStore = cas,
            attachments = CouchAttachmentGateway(store, cas), routes = ModuleRouteRegistry(), scope = scope,
            clock = { 1234L }, stateDir = directory, muxContext = muxHost + LcncClockKey { 3L },
            ccekBinding = ccekReactorBinding(scope.coroutineContext + reactor),
            programLoader = { name -> LcncBlackboard.programOf(blackboard.get(LcncBlackboard.programKey(name)))
                ?: programs[name] ?: LcncPresets.all()[name]?.let { LcncProgramConfix.fromJson(name, it) } },
        )
        ctx.lcncRunners.putAll(PureNodes.registry { 1L })
        val supervisor = ModuleSupervisor(ctx)
        return Rig(ctx, supervisor, supervisor.attach(KanbanModule()), reactor)
    }

    private suspend fun post(rig: Rig, request: Map<String, Any?>): JvmKanbanServer.HttpResponse =
        rig.server.routeHttp(("POST /api/lcnc/run HTTP/1.1\r\nHost: t\r\nContent-Type: application/json\r\n\r\n" + jsonOf(request)).encodeToByteArray())

    private fun document(program: LcncProgram): Any? = reify(LcncProgramConfix.toJson(program))
    private fun body(response: JvmKanbanServer.HttpResponse): Map<String, Any?> = reifyMap(response.body)
    private fun normalized(value: Any?): Map<String, Any?> = reifyMap(jsonOf(value))
    private suspend fun get(rig: Rig, path: String): JvmKanbanServer.HttpResponse =
        rig.server.routeHttp("GET $path HTTP/1.1\r\nHost: t\r\n\r\n".encodeToByteArray())

    private suspend fun publish(rig: Rig, program: LcncProgram) {
        rig.ctx.blackboard.put(LcncBlackboard.programKey(program.name),
            LcncBlackboard.programEntry(program.name, program, LcncContracts.all().associateBy { it.type }), "test")
    }

    private fun declared(name: String, subnet: String, output: String = "tick") = LcncProgram(name,
        s_[LcncNode("clock", "timer"), LcncNode("output", "scope.out", mapOf("name" to output, "kind" to "trigger"))],
        s_[LcncWire("clock", "tick", "output", "value")], controls = LcncConfixControls(subnet = subnet))

    private fun child(prefix: String = "") = LcncProgram("child", s_[
        LcncNode("input", "scope.in", mapOf("name" to "text", "kind" to "text")),
        LcncNode("clock", "timer"),
        LcncNode("probe", "test.probe", mapOf("prefix" to prefix)),
        LcncNode("output", "scope.out", mapOf("name" to "result", "kind" to "text")),
    ], s_[
        LcncWire("input", "value", "probe", "x"), LcncWire("clock", "tick", "probe", "tick"),
        LcncWire("probe", "y", "output", "value"),
    ])

    private fun parent(inline: Boolean): LcncProgram {
        val child = child()
        return LcncProgram(if (inline) "inline-route" else "stored", s_[
            LcncNode("text", "scope.in", mapOf("name" to "text", "kind" to "text")),
            if (inline) LcncNode("ring", "scope", children = child.nodes)
            else LcncNode("ring", "scope", subprogram = "child"),
            LcncNode("result", "scope.out", mapOf("name" to "result", "kind" to "text")),
        ], s_[
            LcncWire("text", "value", "ring", "text"), LcncWire("ring", "result", "result", "value"),
        ].let { if (inline) combine(s_[it, child.wires]) else it })
    }

    private fun probe(observed: MutableList<Observation>) = LcncNodeRunner { node, inputs ->
        val invocation = LcncWorkgroupKey.require()
        assertTrue(invocation.a.name.startsWith("lcnc/program/"))
        assertTrue(invocation.a.name.endsWith("@${invocation.b.b.value}"))
        assertEquals("local", invocation.a.scope.toString())
        val host = assertNotNull(currentCoroutineContext()[Host])
        val tick = LcncClockKey.require().invoke()
        assertEquals(tick, (inputs["tick"] as Number).toLong())
        observed += Observation(invocation.b.a.claimId, invocation.b.b.value, invocation.b.a.nuid.subnet.toString(),
            host, tick, assertNotNull(currentCoroutineContext()[LcncScopeFrame]).depth)
        mapOf("y" to node.params.getValue("prefix") + inputs["x"].toString().uppercase() + "@$tick")
    }

    private fun receipt(rig: Rig, response: JvmKanbanServer.HttpResponse, observation: Observation? = null): Map<String, Any?> {
        assertEquals(200, response.status, response.body)
        val run = body(response)
        assertEquals("completed", run["status"])
        assertEquals(true, run["ok"])
        assertEquals("lcnc/program/${run["program"]}@${run["programCid"]}", run["workgroup"])
        assertEquals("local", run["claimedAtSubnet"])
        assertTrue((run["claimId"] as Number).toLong() > 0)
        observation?.let {
            assertEquals(it.claim, (run["claimId"] as Number).toLong())
            assertEquals(it.programCid, run["programCid"])
            assertEquals(it.subnet, run["subnet"])
        }
        val source = assertNotNull(rig.ctx.casStore.get(ContentId(run["programCid"].toString())))
        assertEquals(run["programCid"], ContentId.of(source).value)
        val raw = assertNotNull(rig.ctx.casStore.get(ContentId(run["receiptCid"].toString())))
        assertEquals(run["receiptCid"], ContentId.of(raw).value)
        val command = reifyMap(raw)
        val retained = assertIs<Map<*, *>>(command["lcncRun"])
        for (key in listOf("runId", "status", "workgroup", "claimId", "subnet", "claimedAtSubnet", "programCid", "returns", "programVersions", "invocationsCid"))
            assertEquals(run[key], retained[key], "Durable command must retain $key")
        assertEquals(run, normalized(rig.ctx.blackboard.get("lcnc/run/${run["runId"]}")))
        return run
    }

    @Test fun declaredProgramsAppearAsLiveWorkgroupsAndTransferCommittedEvidence() = runBlocking {
        val producer = declared("producer", "local.review", "summary")
        val recipient = LcncProgram("recipient", s_[
            LcncNode("input", "scope.in", mapOf("name" to "evidence", "kind" to "json")),
            LcncNode("output", "scope.out", mapOf("name" to "evidence", "kind" to "json")),
        ], s_[LcncWire("input", "value", "output", "value")], controls = LcncConfixControls(subnet = "local.reuse"))
        val rig = fixture(stored = listOf(producer, recipient))
        var producerCalls = 0
        rig.ctx.lcncRunners["timer"] = LcncNodeRunner { _, _ ->
            producerCalls++
            val actual = NuidInvocation.require()
            assertEquals("lcnc/program/producer@${LcncBlackboard.cidOf(producer)}", actual.a.name)
            assertEquals(actual.a, LcncWorkgroupKey.require().a)
            mapOf("tick" to 42L)
        }
        try {
            val inventory = body(get(rig, "/api/lcnc/workgroups"))
            val live = assertIs<List<*>>(inventory["workgroups"]).map { assertIs<Map<*, *>>(it) }
            assertEquals(setOf("producer", "recipient"), live.map { it["program"] }.toSet())
            assertEquals(2, assertIs<List<*>>(inventory["inventory"]).size)
            val rings = assertIs<List<*>>(body(get(rig, "/api/lcnc/concentric"))["rings"])
            assertEquals(2, rings.size, "Offered examples must not masquerade as installed constructions")
            val spoof = NuidInvocation(Workgroup("spoof", Subnet.parse("lan"), traitSpaceOf(Capability.Custom("fake", "run"))) j
                NuidFanoutElement.Claim(-1, nuid(Capability.Custom("fake", "run"), Nonce.RandomBytes(), Subnet.parse("lan")), null))
            val sourceResponse = withContext(spoof) { post(rig, mapOf("program" to "producer")) }
            assertEquals(200, sourceResponse.status, sourceResponse.body)
            val source = body(sourceResponse)
            assertEquals("lcnc/program/producer@${LcncBlackboard.cidOf(producer)}", source["workgroup"])
            assertEquals("local.review", source["subnet"])
            assertEquals(LcncBlackboard.cidOf(producer), source["programCid"])
            val received = post(rig, mapOf("program" to "recipient", "subnet" to "local.reuse.worker",
                "sourceReceiptCid" to source["receiptCid"]))
            assertEquals(200, received.status, received.body)
            val run = body(received)
            assertEquals("lcnc/program/recipient@${LcncBlackboard.cidOf(recipient)}", run["workgroup"])
            assertEquals("local.reuse", run["claimedAtSubnet"])
            val evidence = assertIs<Map<*, *>>(assertIs<Map<*, *>>(run["returns"])["evidence"])
            assertEquals(source["returns"], evidence["returns"])
            assertEquals(source["receiptCid"], evidence["receiptCid"])
            assertEquals(source["receiptCid"], run["sourceReceiptCid"])
            assertEquals(1, producerCalls)
            val rebuilt = rig.server.routeHttp(("POST /api/lcnc/run/rebuild HTTP/1.1\r\nHost: t\r\n\r\n" +
                jsonOf(mapOf("runId" to run["runId"]))).encodeToByteArray())
            assertEquals(200, rebuilt.status, rebuilt.body)
            val copy = body(rebuilt)
            assertEquals(run["returns"], copy["returns"])
            assertEquals(run["consumed"], copy["consumed"])
            assertEquals(run["inputFingerprint"], copy["inputFingerprint"])
            assertEquals(source["receiptCid"], copy["sourceReceiptCid"])
            assertEquals(run["workgroup"], copy["workgroup"])
            assertNotEquals(run["claimId"], copy["claimId"])
            assertEquals(1, producerCalls)
        } finally { rig.close() }
    }

    @Test fun declaredReplacementPreservesAcceptedVersionThenWithdrawsRemovedOrInspectionOnlyPrograms() = runBlocking {
        val original = declared("review", "local.review")
        val rig = fixture(stored = listOf(original))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        rig.ctx.lcncRunners["timer"] = LcncNodeRunner { _, _ -> entered.complete(Unit); release.await(); mapOf("tick" to 1L) }
        val first = async { post(rig, mapOf("program" to "review")) }
        var updated: Deferred<JvmKanbanServer.HttpResponse>? = null
        try {
            withTimeout(5_000) { entered.await() }
            val replacement = declared("review", "local.review.worker", "replacement")
            publish(rig, replacement)
            val pending = async(start = CoroutineStart.UNDISPATCHED) { get(rig, "/api/lcnc/workgroups") }
            updated = pending
            val inventory = body(withTimeout(5_000) { pending.await() })
            assertFalse(first.isCompleted, "The accepted version remains owned while the latest declaration changes")
            val live = assertIs<Map<*, *>>(assertIs<List<*>>(inventory["workgroups"]).single())
            assertEquals("local.review.worker", live["scope"])
            assertEquals(LcncBlackboard.cidOf(replacement), live["programCid"])
            release.complete(Unit)
            val old = withTimeout(5_000) { first.await() }
            assertEquals(200, old.status, old.body)
            assertEquals(LcncBlackboard.cidOf(original), body(old)["programCid"])
            val fresh = post(rig, mapOf("program" to "review"))
            assertEquals(200, fresh.status, fresh.body)
            assertEquals(LcncBlackboard.cidOf(replacement), body(fresh)["programCid"])
            assertEquals(mapOf("replacement" to 1.0), body(fresh)["returns"])
            publish(rig, replacement.copy(controls = replacement.controls.copy(inspectionOnly = true)))
            val refused = body(get(rig, "/api/lcnc/workgroups"))
            assertTrue(assertIs<List<*>>(refused["workgroups"]).isEmpty())
            assertTrue(assertIs<Map<*, *>>(refused["refusals"]).containsKey("lcnc/program/review"))
            assertEquals(404, post(rig, mapOf("program" to "review")).status)
            rig.ctx.blackboard.remove(LcncBlackboard.programKey("review"))
            val removed = body(get(rig, "/api/lcnc/workgroups"))
            assertTrue(assertIs<Map<*, *>>(removed["refusals"]).isEmpty())
            assertTrue(assertIs<List<*>>(body(get(rig, "/api/lcnc/concentric"))["rings"]).isEmpty())
        } finally { release.complete(Unit); first.cancelAndJoin(); updated?.cancelAndJoin(); rig.close() }
    }

    @Test fun authoredParentFinishesARequiredChildDuringModuleDrain() = runBlocking {
        nestedRouteDuringRetirement(drain = true)
    }

    @Test fun authoredParentCanCallAChildWhileItsOwnBindingIsBeingReplaced() = runBlocking {
        nestedRouteDuringRetirement(drain = false)
    }

    @Test fun undeclaredAuthoredDependenciesUseDistinctWorkersAndRefuseTheSameVersionCycle() = runBlocking {
        fun caller(name: String) = LcncProgram(name, s_[
            LcncNode("request", "scope.in", mapOf("name" to "request", "kind" to "json")),
            LcncNode("count", "test.count"),
            LcncNode("child", "http.post", mapOf("path" to "/api/lcnc/run")),
            LcncNode("output", "scope.out", mapOf("name" to "child", "kind" to "json")),
        ], s_[LcncWire("request", "value", "count", "body"),
            LcncWire("count", "body", "child", "body"), LcncWire("child", "json", "output", "value")])
        val parent = caller("parent")
        val recursive = caller("recursive")
        val child = declared("child", "local").copy(controls = LcncConfixControls())
        val rig = fixture(programs = mapOf(parent.name to parent, child.name to child, recursive.name to recursive))
        var calls = 0
        rig.ctx.lcncRunners["test.count"] = LcncNodeRunner { _, inputs -> calls++; mapOf("body" to inputs["body"]) }
        rig.ctx.lcncRunners.putAll(SurfaceNodes.registry { method, path, value ->
            val text = "$method $path HTTP/1.1\r\nContent-Type: application/json\r\n\r\n" + jsonOf(value)
            body(assertNotNull(assertNotNull(rig.ctx.routes.match(path)).route(method, path, text, null)))
        })
        try {
            val result = withTimeout(5_000) { post(rig, mapOf("program" to parent.name,
                "inputs" to mapOf("request" to mapOf("program" to child.name)))) }
            val outer = receipt(rig, result)
            val inner = assertIs<Map<*, *>>(assertIs<Map<*, *>>(outer["returns"])["child"])
            assertEquals("completed", inner["status"], jsonOf(inner))
            assertEquals("lcnc/program/child@${LcncBlackboard.cidOf(child)}", inner["workgroup"])
            assertEquals(outer["claimId"], inner["parentClaimId"])
            assertEquals(outer["runId"], inner["parentRunId"])
            assertEquals(outer["executionFamily"], inner["executionFamily"])
            assertNotEquals(outer["workgroup"], inner["workgroup"])
            val rebuilt = withTimeout(5_000) { rig.server.routeHttp((
                "POST /api/lcnc/run/rebuild HTTP/1.1\r\nHost: t\r\n\r\n" +
                    jsonOf(mapOf("runId" to outer["runId"]))).encodeToByteArray()) }
            val rebuild = receipt(rig, rebuilt)
            val rebuiltChild = assertIs<Map<*, *>>(assertIs<Map<*, *>>(rebuild["returns"])["child"])
            assertEquals("completed", rebuiltChild["status"], jsonOf(rebuiltChild))
            assertEquals(outer["workgroup"], rebuild["workgroup"])
            assertNotEquals(outer["claimId"], rebuild["claimId"])
            assertEquals(rebuild["claimId"], rebuiltChild["parentClaimId"])
            assertEquals(rebuild["runId"], rebuiltChild["parentRunId"])
            assertEquals(rebuild["executionFamily"], rebuiltChild["executionFamily"])
            val cycle = withTimeout(5_000) { post(rig, mapOf("program" to recursive.name,
                "inputs" to mapOf("request" to mapOf("program" to recursive.name)))) }
            val cycleRoot = receipt(rig, cycle)
            val refused = assertIs<Map<*, *>>(assertIs<Map<*, *>>(cycleRoot["returns"])["child"])
            assertEquals(false, refused["ok"], jsonOf(refused))
            assertEquals("refused", refused["status"])
            assertTrue(refused["error"].toString().contains("cycle", ignoreCase = true), jsonOf(refused))
            assertEquals(3, calls, "The initial parent, rebuilt parent and recursive root each execute once")
        } finally { rig.close() }
    }

    private suspend fun nestedRouteDuringRetirement(drain: Boolean) = coroutineScope {
        val child = declared("child", "local.child")
        val parent = LcncProgram("parent", s_[
            LcncNode("request", "scope.in", mapOf("name" to "request", "kind" to "json")),
            LcncNode("hold", "test.hold"),
            LcncNode("child", "http.post", mapOf("path" to "/api/lcnc/run")),
            LcncNode("output", "scope.out", mapOf("name" to "child", "kind" to "json")),
        ], s_[
            LcncWire("request", "value", "hold", "body"),
            LcncWire("hold", "body", "child", "body"),
            LcncWire("child", "json", "output", "value"),
        ], controls = LcncConfixControls(subnet = "local.parent"))
        val rig = fixture(stored = listOf(parent, child))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        rig.ctx.lcncRunners["test.hold"] = LcncNodeRunner { _, inputs ->
            entered.complete(Unit)
            release.await()
            mapOf("body" to inputs["body"])
        }
        // The daemon's shipped SurfaceNodes binding calls the module route in this same context.
        rig.ctx.lcncRunners.putAll(SurfaceNodes.registry { method, path, value ->
            val text = "$method $path HTTP/1.1\r\nContent-Type: application/json\r\n\r\n" + jsonOf(value)
            val response = assertNotNull(assertNotNull(rig.ctx.routes.match(path)).route(method, path, text, null))
            body(response)
        })
        val request = mapOf("program" to child.name, "subnet" to "local.child.worker")
        val pending = async { post(rig, mapOf("program" to parent.name, "inputs" to mapOf("request" to request))) }
        var settling: Deferred<*>? = null
        var completed = false
        try {
            withTimeout(5_000) { entered.await() }
            val retiring = if (drain) async(start = CoroutineStart.UNDISPATCHED) { rig.supervisor.detach("kanban") }
            else {
                publish(rig, parent.copy(controls = parent.controls.copy(subnet = "local.parent.next")))
                async(start = CoroutineStart.UNDISPATCHED) { get(rig, "/api/lcnc/workgroups") }
            }
            settling = retiring
            if (drain) assertFalse(retiring.isCompleted)
            if (drain) assertEquals(503, post(rig, request).status, "External admission remains stopped")
            release.complete(Unit)
            val response = withTimeout(5_000) { pending.await() }
            assertEquals(200, response.status, response.body)
            val settled = withTimeout(5_000) { retiring.await() }
            if (drain) assertEquals(true, settled)
            else assertEquals(200, assertIs<JvmKanbanServer.HttpResponse>(settled).status)
            val parentReceipt = body(response)
            val childRun = assertIs<Map<*, *>>(assertIs<Map<*, *>>(parentReceipt["returns"])["child"])
            assertEquals("completed", childRun["status"], jsonOf(childRun))
            assertEquals(listOf("lcnc/program/parent@${LcncBlackboard.cidOf(parent)}", "lcnc/program/child@${LcncBlackboard.cidOf(child)}"),
                assertIs<List<*>>(childRun["claimLineage"]).map { assertIs<Map<*, *>>(it)["workgroup"] })
            assertEquals(parentReceipt["claimId"], childRun["parentClaimId"])
            assertEquals("lcnc/program/parent@${LcncBlackboard.cidOf(parent)}", childRun["parentWorkgroup"])
            assertEquals(parentReceipt["runId"], childRun["parentRunId"])
            assertEquals(parentReceipt["executionFamily"], childRun["executionFamily"])
            val retained = assertIs<Map<*, *>>(reifyMap(assertNotNull(rig.ctx.casStore.get(ContentId(childRun["receiptCid"].toString()))))["lcncRun"])
            for (key in listOf("claimLineage", "parentClaimId", "parentWorkgroup", "parentRunId", "executionFamily"))
                assertEquals(childRun[key], retained[key])
            if (drain) assertEquals("CLOSED", assertIs<Map<*, *>>(rig.handle.describe()["lcncWorkgroup"])["state"])
            completed = true
        } finally {
            release.complete(Unit)
            if (!completed) rig.ctx.scope.coroutineContext.job.cancel()
            pending.cancelAndJoin()
            settling?.cancelAndJoin()
            rig.close()
        }
    }

    @Test fun routedInlineAndNamedProgramsRetainContextVersionsAndRecoveredClaimIdentity() = runBlocking {
        val directory = Files.createTempDirectory("lcnc-subnet-restart-").toFile()
        val cas = CasStore.inMemory()
        val programs = linkedMapOf("stored" to parent(false), "child" to child())
        val muxHost = Host("mux")
        val rig = fixture(directory, cas, programs, muxHost = muxHost)
        val observed = mutableListOf<Observation>()
        rig.ctx.lcncRunners["test.probe"] = probe(observed)
        val recoveredRuns = mutableListOf<Map<String, Any?>>()
        try {
            val caller = Host("caller")
            val inline = withContext(caller + LcncClockKey { 4L }) {
                post(rig, mapOf("name" to "inline-route", "document" to document(parent(true)),
                    "subnet" to "local.review", "inputs" to mapOf("text" to "hi")))
            }
            val first = receipt(rig, inline, observed.single())
            assertEquals("HI@4", assertIs<Map<*, *>>(first["returns"])["result"])
            assertSame(caller, observed.single().host)
            assertEquals(1, observed.single().depth)
            assertNull(first["programKey"])
            recoveredRuns += first

            val named = receipt(rig, post(rig, mapOf("program" to "stored", "subnet" to "local.review.worker",
                "inputs" to mapOf("text" to "hi"))), observed.last())
            assertSame(muxHost, observed.last().host)
            assertEquals(3L, observed.last().clock)
            assertEquals("HI@3", assertIs<Map<*, *>>(named["returns"])["result"])
            assertEquals("lcnc/program/stored", named["programKey"])
            val versions = assertIs<Map<*, *>>(named["programVersions"])
            assertEquals(setOf<Any?>("stored", "child"), versions.keys)
            assertEquals(named["programCid"], versions["stored"])
            assertEquals(LcncBlackboard.cidOf(programs.getValue("child")), versions["child"])
            programs["child"] = child("changed:")
            val pinned = receipt(rig, post(rig, mapOf("program" to "stored", "subnet" to "local.review.worker",
                "inputs" to mapOf("text" to "hi"), "pinnedVersions" to mapOf("child" to versions["child"]))), observed.last())
            assertEquals(versions, pinned["programVersions"])
            assertEquals("HI@3", assertIs<Map<*, *>>(pinned["returns"])["result"])
            recoveredRuns += named
            recoveredRuns += pinned
            assertEquals(3, observed.map { it.claim }.distinct().size)
        } finally { rig.close() }
        val restarted = fixture(directory, cas, programs)
        try {
            for (run in recoveredRuns) {
                val recovered = normalized(restarted.ctx.blackboard.get("lcnc/run/${run["runId"]}"))
                assertEquals(run, recovered, "WAL recovery must retain exact routed execution evidence")
                val raw = assertNotNull(cas.get(ContentId(recovered["receiptCid"].toString())))
                val retained = assertIs<Map<*, *>>(reifyMap(raw)["lcncRun"])
                assertEquals(run["claimId"], retained["claimId"])
                assertEquals(run["programVersions"], retained["programVersions"])
                withTimeout(5_000) {
                    while (restarted.ctx.rete.snapshot().view.none {
                        it.fields["jobId"] == "lcnc/run/${run["runId"]}" &&
                            it.fields["executionFamily"] == run["executionFamily"]
                    }) delay(1)
                }
            }
        } finally { restarted.close() }
    }

    @Test fun malformedForeignAndExcessivelyDistantSubnetsNeverInvokeTheRunner() = runBlocking {
        val rig = fixture()
        var calls = 0
        rig.ctx.lcncRunners["test.count"] = LcncNodeRunner { _, _ -> calls++; emptyMap() }
        val document = document(LcncProgram("refusal", s_[LcncNode("work", "test.count")], emptySeriesOf()))
        try {
            for (subnet in listOf<Any?>(null, 7, "", "local..review", ".local", "local.", "local. review", "local.review\n")) {
                val response = post(rig, mapOf("document" to document, "subnet" to subnet))
                assertEquals(400, response.status, response.body)
                assertEquals("invalid_subnet", body(response)["error"])
            }
            for (subnet in listOf("global.review", "localhost.review", "local.review.worker.branch.task")) {
                val response = post(rig, mapOf("document" to document, "subnet" to subnet))
                assertEquals(404, response.status, response.body)
                assertEquals("no_workgroup", body(response)["error"])
            }
            assertEquals(0, calls)
            assertFalse(rig.ctx.blackboard.snapshot().values.keys.any { it.startsWith("lcnc/run/") })
            val boundary = receipt(rig, post(rig, mapOf("document" to document, "subnet" to "local.review.worker.branch")))
            assertEquals("local.review.worker.branch", boundary["subnet"])
            assertEquals(1, calls)
        } finally { rig.close() }
    }

    @Test fun cancellingTheHttpWaiterLeavesAdmittedWorkOwnedUntilModuleDrainCompletes() = runBlocking {
        val rig = fixture()
        val entered = CompletableDeferred<NuidFanoutElement>()
        val released = CompletableDeferred<Unit>()
        rig.ctx.lcncRunners["test.held"] = LcncNodeRunner { _, _ ->
            LcncWorkgroupKey.require()
            entered.complete(assertNotNull(currentCoroutineContext()[NuidFanoutElement]))
            released.await()
            mapOf("finished" to true)
        }
        val pending = async(start = CoroutineStart.UNDISPATCHED) { post(rig, mapOf("name" to "held", "subnet" to "local.review",
            "document" to document(LcncProgram("held", s_[LcncNode("work", "test.held")], emptySeriesOf())))) }
        var second: Deferred<JvmKanbanServer.HttpResponse>? = null
        try {
            val fanout = withTimeout(5_000) { entered.await() }
            val active = rig.ctx.blackboard.snapshot().values.values.filterIsInstance<Map<*, *>>()
                .single { it["program"] == "held" && it["status"] == "running" }
            // Inline parsing has no suspension before dispatch. With no registry mutation and room
            // in its queue, UNDISPATCHED returns at the dispatch acknowledgement/result await:
            // this request is admitted before drain, while the first handler is still held.
            val queued = async(start = CoroutineStart.UNDISPATCHED) { post(rig, mapOf("name" to "queued-debug", "subnet" to "local.review",
                "debugToken" to UUID.randomUUID().toString(), "timeoutMs" to 30_000,
                "document" to document(LcncProgram("queued-debug", s_[LcncNode("clock", "timer")], emptySeriesOf())))) }
            second = queued
            assertFalse(queued.isCompleted)
            assertEquals(borg.trikeshed.context.ElementState.ACTIVE, fanout.state)
            pending.cancelAndJoin()
            val draining = async(start = CoroutineStart.UNDISPATCHED) { rig.supervisor.detach("kanban") }
            assertFalse(draining.isCompleted)
            released.complete(Unit)
            assertTrue(withTimeout(5_000) { draining.await() })
            val completed = normalized(rig.ctx.blackboard.get("lcnc/run/${active["runId"]}"))
            assertEquals("completed", completed["status"])
            assertEquals(true, assertIs<Map<*, *>>(assertIs<Map<*, *>>(completed["outputs"])["work"])["finished"])
            assertEquals((active["claimId"] as Number).toLong(), (completed["claimId"] as Number).toLong())
            receipt(rig, JvmKanbanServer.HttpResponse(200, jsonOf(completed)))
            val queuedRun = receipt(rig, withTimeout(5_000) { queued.await() })
            assertEquals("queued-debug", queuedRun["program"])
            assertNotEquals(completed["claimId"], queuedRun["claimId"])
            assertEquals(3.0, assertIs<Map<*, *>>(assertIs<Map<*, *>>(queuedRun["outputs"])["clock"])["tick"])
            assertEquals("CLOSED", assertIs<Map<*, *>>(rig.handle.describe()["lcncWorkgroup"])["state"])
        } finally { released.complete(Unit); pending.cancelAndJoin(); second?.cancelAndJoin(); rig.close() }
    }

    @Test fun moduleDrainReleasesPinnedDebugRunAfterManifestReplacementCompletes() = runBlocking {
        val original = declared("held-review", "local.review")
        val rig = fixture(stored = listOf(original))
        val token = UUID.randomUUID().toString()
        val pending = async { post(rig, mapOf("program" to original.name, "debugToken" to token, "timeoutMs" to 30_000)) }
        var inventory: Deferred<JvmKanbanServer.HttpResponse>? = null
        try {
            withTimeout(5_000) {
                while (body(get(rig, "/api/lcnc/run/debug?token=$token"))["state"] != "paused") delay(10)
            }
            publish(rig, declared(original.name, "local.review.worker"))
            val replacing = async(start = CoroutineStart.UNDISPATCHED) { get(rig, "/api/lcnc/workgroups") }
            inventory = replacing
            val replaced = withTimeout(5_000) { replacing.await() }
            assertEquals(200, replaced.status, replaced.body)
            assertFalse(pending.isCompleted, "Replacing the latest binding cannot resume a pinned debug hold")
            assertTrue(withTimeout(5_000) { rig.supervisor.detach("kanban") })
            val completed = withTimeout(5_000) { pending.await() }
            assertEquals(200, completed.status, completed.body)
            assertEquals("completed", body(completed)["status"])
            assertEquals(LcncBlackboard.cidOf(original), body(completed)["programCid"])
            assertEquals("CLOSED", assertIs<Map<*, *>>(rig.handle.describe()["lcncWorkgroups"])["state"])
        } finally { pending.cancelAndJoin(); inventory?.cancelAndJoin(); rig.close() }
    }

    @Test fun moduleDrainReleasesARoutedDebugHoldBeforeWaitingForTheWorkgroup() = runBlocking {
        val rig = fixture()
        val token = UUID.randomUUID().toString()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { post(rig, mapOf("name" to "debug-drain", "subnet" to "local.review",
            "debugToken" to token, "timeoutMs" to 30_000,
            "document" to document(LcncProgram("debug-drain", s_[LcncNode("clock", "timer")], emptySeriesOf())))) }
        try {
            val paused = withTimeout(5_000) {
                var snapshot: Map<String, Any?>
                do {
                    snapshot = body(rig.server.routeHttp("GET /api/lcnc/run/debug?token=$token HTTP/1.1\r\nHost: t\r\n\r\n".encodeToByteArray()))
                    if (snapshot["state"] != "paused") delay(10)
                } while (snapshot["state"] != "paused")
                snapshot
            }
            assertFalse(pending.isCompleted)
            assertTrue(withTimeout(5_000) { rig.supervisor.detach("kanban") })
            val completed = receipt(rig, withTimeout(5_000) { pending.await() })
            assertEquals(paused["runId"], completed["runId"])
            assertEquals(3.0, assertIs<Map<*, *>>(assertIs<Map<*, *>>(completed["outputs"])["clock"])["tick"])
            assertEquals("CLOSED", assertIs<Map<*, *>>(rig.handle.describe()["lcncWorkgroup"])["state"])
        } finally { pending.cancelAndJoin(); rig.close() }
    }

    @Test fun authoredOrchestratorGathersAgentTasksVotesAndPollinatesACommittedRecipient(
        @org.junit.jupiter.api.io.TempDir directory: java.nio.file.Path,
    ) = runBlocking<Unit> { orchestratedAgentTasks(directory, failFirstCleanup = false) }

    @Test fun authoredOrchestratorPreservesAFailedTaskAndExplicitDissentThroughPollination(
        @org.junit.jupiter.api.io.TempDir directory: java.nio.file.Path,
    ) = runBlocking<Unit> { orchestratedAgentTasks(directory, failFirstCleanup = true) }

    private suspend fun orchestratedAgentTasks(directory: java.nio.file.Path, failFirstCleanup: Boolean) {
        val producer = LcncProgram("tasks", s_[
            LcncNode("first", "agent.run", mapOf("agent" to "local", "brief" to "inspect first", "jobId" to "task-a", "budgetSeconds" to "10")),
            LcncNode("second", "agent.run", mapOf("agent" to "local", "brief" to "inspect second", "jobId" to "task-b", "budgetSeconds" to "10")),
            LcncNode("summary", "text.fold"),
            LcncNode("receipts", "record.fold"),
            LcncNode("ballots", "scope.in", mapOf("name" to "ballots", "kind" to "json")),
            LcncNode("vote", "panel.vote", mapOf("quorum" to "2")),
            LcncNode("summary.out", "scope.out", mapOf("name" to "summary", "kind" to "text")),
            LcncNode("receipts.out", "scope.out", mapOf("name" to "tasks", "kind" to "json")),
            LcncNode("ballots.out", "scope.out", mapOf("name" to "ballots", "kind" to "json")),
            LcncNode("quorum.out", "scope.out", mapOf("name" to "quorum", "kind" to "num")),
            LcncNode("quorumMet.out", "scope.out", mapOf("name" to "quorumMet", "kind" to "json")),
            LcncNode("accepted.out", "scope.out", mapOf("name" to "accepted", "kind" to "json")),
            LcncNode("tally.out", "scope.out", mapOf("name" to "tally", "kind" to "json")),
        ], s_[
            LcncWire("first", "summary", "summary", "parts"), LcncWire("second", "summary", "summary", "parts"),
            LcncWire("first", "receipt", "receipts", "parts"), LcncWire("second", "receipt", "receipts", "parts"),
            LcncWire("ballots", "value", "vote", "ballots"),
            LcncWire("summary", "text", "summary.out", "value"), LcncWire("receipts", "turns", "receipts.out", "value"),
            LcncWire("vote", "transcript", "ballots.out", "value"), LcncWire("vote", "quorum", "quorum.out", "value"),
            LcncWire("vote", "quorumMet", "quorumMet.out", "value"), LcncWire("vote", "accepted", "accepted.out", "value"),
            LcncWire("vote", "tally", "tally.out", "value"),
        ], controls = LcncConfixControls(subnet = "local.tasks"))
        val recipient = LcncProgram("recipient", s_[
            LcncNode("input", "scope.in", mapOf("name" to "evidence", "kind" to "json")),
            LcncNode("output", "scope.out", mapOf("name" to "evidence", "kind" to "json")),
        ], s_[LcncWire("input", "value", "output", "value")], controls = LcncConfixControls(subnet = "local.publish"))
        val orchestrator = LcncProgram("orchestrator", s_[
            LcncNode("request", "scope.in", mapOf("name" to "request", "kind" to "json")),
            LcncNode("producer", "http.post", mapOf("path" to "/api/lcnc/run")),
            LcncNode("recipient.request", "scope", children = s_[
                LcncNode("source", "scope.in", mapOf("name" to "source", "kind" to "json")),
                LcncNode("cid", "pick", mapOf("path" to "receiptCid")),
                LcncNode("cid.out", "scope.out", mapOf("name" to "sourceReceiptCid", "kind" to "json")),
                LcncNode("program", "text.value", mapOf("value" to recipient.name)),
                LcncNode("program.out", "scope.out", mapOf("name" to "program", "kind" to "text")),
            ]),
            LcncNode("recipient", "http.post", mapOf("path" to "/api/lcnc/run")),
            LcncNode("producer.out", "scope.out", mapOf("name" to "producer", "kind" to "json")),
            LcncNode("recipient.out", "scope.out", mapOf("name" to "recipient", "kind" to "json")),
        ], s_[
            LcncWire("request", "value", "producer", "body"),
            LcncWire("producer", "json", "recipient.request", "source"),
            LcncWire("source", "value", "cid", "x"), LcncWire("cid", "y", "cid.out", "value"),
            LcncWire("program", "value", "program.out", "value"),
            LcncWire("recipient.request", "returns", "recipient", "body"),
            LcncWire("producer", "json", "producer.out", "value"), LcncWire("recipient", "json", "recipient.out", "value"),
        ], controls = LcncConfixControls(subnet = "local.orchestrator"))
        for (program in listOf(orchestrator, producer, recipient)) assertTrue(LcncTypeCheck.check(program).isEmpty())
        val sourceRepo = Files.createDirectories(directory.resolve("source")).toFile()
        suspend fun git(vararg arguments: String): String {
            val pipes = JvmProcessProtocolPipes.open(
                command = { _, _ -> listOf("/usr/bin/git") + arguments },
                environment = AgentEnvironment.build(mapOf("HOME" to directory.toString(), "LANG" to "C"),
                    AgentEnvironment.SYSTEM_PATH, AgentEnvironment.GIT),
                workingDirectory = sourceRepo.absolutePath, mergeError = true)
            val captured = ProcessCaptureChannel(currentCoroutineContext(), pipes,
                maxBytes = 1_048_576, timeoutMillis = 10_000).await()
            check(captured.exit == 0 && !captured.timedOut) { captured.text() }
            return captured.text().trim()
        }
        git("init", "-q", "-b", "main")
        git("config", "user.name", "subnet process fixture")
        git("config", "user.email", "fixture@localhost")
        Files.writeString(sourceRepo.toPath().resolve("README.md"), "Committed source\n")
        git("add", "README.md")
        git("commit", "-q", "-m", "source")
        Files.writeString(sourceRepo.toPath().resolve("uncommitted.txt"), "source only\n")
        val sourceHead = git("rev-parse", "HEAD")
        val sourceStatus = git("status", "--porcelain")
        val launches = directory.resolve("launches.txt")
        val rig = fixture(directory = directory.resolve("state").toFile(),
            stored = listOf(orchestrator, producer, recipient))
        val requests = mutableListOf<AgentRunRequest>()
        val backing = JvmFileOperations()
        val failedScratch = directory.resolve("forge/agents/agent-1").toFile()
        val cleanupAttempts = mutableListOf<String>()
        val cleanupFailure = java.io.IOException("injected first task cleanup failure: ${failedScratch.path}")
        val files = object : FileOperations by backing {
            override fun deleteRecursively(path: String) {
                if (failFirstCleanup && path == failedScratch.path) {
                    cleanupAttempts.add(path)
                    throw cleanupFailure
                }
                backing.deleteRecursively(path)
            }
        }
        val nio = NioSupervisor().apply { register(files) }
        val code = """
            brief=${'$'}(cat)
            case "${'$'}brief" in
                'inspect first') task=task-a ;;
                'inspect second') task=task-b ;;
                *) exit 90 ;;
            esac
            [ ! -e uncommitted.txt ] || exit 91
            printf '%s\n' "${'$'}task" >> "${'$'}2"
            printf 'Actual CLI task=%s\nbrief=%s\n' "${'$'}task" "${'$'}brief"
            printf 'Actual work for %s\n' "${'$'}task" > "${'$'}task.txt"
            printf 'Observed %s\n' "${'$'}task" > "${'$'}1"
        """.trimIndent()
        val real = JvmAgentRunner(
            rosterRows = listOf(AgentCliInfo("local", "/bin/sh", "local shell", enabled = true)),
            repoDir = sourceRepo, forgeHome = directory.resolve("forge").toFile(),
            cas = rig.ctx.casStore, attachments = rig.ctx.attachments, blackboard = rig.ctx.blackboard,
            environment = mapOf("HOME" to directory.toString(), "LANG" to "C"),
            definitions = { info -> AgentCli(info.id, info.path, briefOnStdin = true) { _, _, last, _ ->
                listOf("/bin/sh", "-c", code, "subnet-agent-fixture", last.absolutePath, launches.toString())
            } },
            toolDirs = listOf("/usr/bin", "/bin"),
        )
        // Observe the actual routed request, then delegate all result/evidence production.
        val agents = object : AgentRuns {
            override suspend fun roster(): List<AgentCliInfo> = real.roster()
            override suspend fun run(request: AgentRunRequest): AgentRunResult {
                requests += request
                val invocation = NuidInvocation.require()
                assertEquals("lcnc/program/tasks@${LcncBlackboard.cidOf(producer)}", invocation.a.name)
                if (!failFirstCleanup) return real.run(request)
                return withContext(nio) {
                    assertSame(invocation, NuidInvocation.require(), "Filesystem routing cannot replace the actual task claim")
                    assertNull(currentCoroutineContext()[FileOperations])
                    assertSame(files, currentCoroutineContext()[NioSupervisor]?.service<FileOperations>())
                    real.run(request)
                }
            }
        }
        rig.ctx.agentRuns = agents
        var ordinal = 0
        rig.ctx.lcncRunners.putAll(AgentNodes.registry(agents) { "agent-${++ordinal}" })
        rig.ctx.lcncRunners.putAll(CouncilNodes.registry(CouncilDialog { error("This construction makes no model call") }))
        rig.ctx.lcncRunners.putAll(SurfaceNodes.registry { method, path, value ->
            val raw = "$method $path HTTP/1.1\r\nContent-Type: application/json\r\n\r\n" + jsonOf(value)
            body(assertNotNull(assertNotNull(rig.ctx.routes.match(path)).route(method, path, raw, null)))
        })
        // These caller-authored ballots are fixed before execution, never inferred from agent results.
        val approveWeight = if (failFirstCleanup) 1.0 else 2.0
        val rejectWeight = if (failFirstCleanup) 2.0 else 1.0
        val ballots = listOf(
            mapOf("seat" to "review-a", "approve" to true, "weight" to approveWeight, "content" to "approve explicit evidence"),
            mapOf("seat" to "review-b", "approve" to false, "weight" to rejectWeight, "content" to "retain dissent"),
            mapOf("seat" to "unavailable", "ok" to false, "weight" to 0.5, "error" to "offline"),
        )
        try {
            val response = withTimeout(60_000) { post(rig, mapOf("program" to orchestrator.name,
                "inputs" to mapOf("request" to mapOf("program" to producer.name, "inputs" to mapOf("ballots" to ballots))))) }
            assertEquals(200, response.status, response.body)
            val outer = body(response)
            val returned = assertIs<Map<*, *>>(outer["returns"])
            val source = assertIs<Map<*, *>>(returned["producer"])
            val target = assertIs<Map<*, *>>(returned["recipient"])
            val gathered = assertIs<Map<*, *>>(source["returns"])
            assertEquals(2, requests.size, "Pollination must not rerun the producer's agent effects")
            assertEquals(listOf("task-a", "task-b"), requests.map { it.jobId })
            assertEquals(2, requests.map { it.runId }.distinct().size)
            assertEquals<List<String>>(if (failFirstCleanup) listOf(failedScratch.path) else emptyList(), cleanupAttempts,
                "Only the first actual task's exact scratch deletion can fail")
            val records = assertIs<List<*>>(gathered["tasks"]).map { assertIs<Map<*, *>>(it) }
            assertEquals(2, records.size)
            for ((record, request) in records.zip(requests)) {
                assertEquals(request.jobId, record["jobId"])
                assertEquals(request.runId, record["runId"])
                assertEquals(request.agent, record["agent"])
                val failed = failFirstCleanup && request.jobId == "task-a"
                assertEquals(!failed, record["ok"])
                if (failed) assertContains(assertIs<String>(record["error"]), assertNotNull(cleanupFailure.message))
                else assertEquals("", record["error"])
                assertEquals(false, record["retained"], "Cleanup failure cannot silently change the requested retention policy")
                assertEquals("local", record["agent"])
                assertEquals(sourceHead, record["base"])
                assertEquals(0, (record["exit"] as Number).toInt())
                assertEquals(false, record["killed"])
                assertEquals(false, record["truncated"])
                assertEquals("Observed ${request.jobId}", record["summary"])
                assertEquals(listOf("${request.jobId}.txt"), record["files"])
                val processTranscript = assertNotNull(rig.ctx.casStore.get(ContentId(record["transcriptCid"] as String))).decodeToString()
                assertEquals("Actual CLI task=${request.jobId}\nbrief=${request.brief}\n", processTranscript)
                val patch = assertNotNull(rig.ctx.casStore.get(ContentId(record["patchCid"] as String))).decodeToString()
                assertContains(patch, "diff --git a/${request.jobId}.txt b/${request.jobId}.txt")
                assertContains(patch, "+Actual work for ${request.jobId}")
                assertEquals(record, normalized(rig.ctx.blackboard.get("agent/run/${request.runId}")),
                    "The gathered task must retain the real runner's receipt")
                assertEquals(listOf("agents/${request.runId}/brief.md", "agents/${request.runId}/patch.diff",
                    "agents/${request.runId}/transcript.md"),
                    rig.ctx.attachments.listAttachments("agents/${request.runId}/").map { it.path }.sorted())
                val scratch = File(assertIs<String>(record["scratch"]))
                if (failed) {
                    assertEquals(failedScratch.absolutePath, scratch.absolutePath)
                    assertTrue(scratch.isDirectory, "The reached deletion fault leaves actual scratch for its failure receipt")
                    assertEquals("Actual work for task-a\n", Files.readString(scratch.toPath().resolve("repo/task-a.txt")))
                } else assertFalse(scratch.exists(), "An ordinary task removes its actual scratch before return")
                for (key in listOf("transcriptCid", "patchCid")) {
                    val cid = ContentId(assertIs<String>(record[key]))
                    assertEquals(cid, ContentId.of(assertNotNull(rig.ctx.casStore.get(cid))))
                }
            }
            assertContains(gathered["summary"].toString(), "Observed task-a")
            assertContains(gathered["summary"].toString(), "Observed task-b")
            assertEquals(!failFirstCleanup, gathered["accepted"])
            assertEquals(!failFirstCleanup, gathered["quorumMet"])
            assertEquals(2.0, gathered["quorum"])
            assertEquals(mapOf("approve" to approveWeight, "reject" to rejectWeight, "abstain" to 0.5), gathered["tally"])
            val transcript = assertIs<List<*>>(gathered["ballots"]).map { assertIs<Map<*, *>>(it) }
            assertEquals(listOf("review-a", "review-b", "unavailable"), transcript.map { it["seat"] })
            assertEquals(listOf(approveWeight, rejectWeight, 0.5), transcript.map { it["weight"] })
            assertEquals("offline", transcript.last()["error"])
            val evidence = assertIs<Map<*, *>>(assertIs<Map<*, *>>(target["returns"])["evidence"])
            assertEquals(source["receiptCid"], target["sourceReceiptCid"])
            assertEquals(source["receiptCid"], evidence["receiptCid"])
            assertEquals(source["runId"], evidence["runId"])
            assertEquals(source["programCid"], evidence["programCid"])
            assertEquals(gathered, evidence["returns"])
            assertTrue(assertIs<List<*>>(target["consumed"]).any { row ->
                val value = assertIs<Map<*, *>>(row)
                value["kind"] == "lcnc-run" && value["id"] == "lcnc/run/${source["runId"]}" && value["cid"] == source["receiptCid"]
            })
            val rootGroup = "lcnc/program/${orchestrator.name}@${LcncBlackboard.cidOf(orchestrator)}"
            for ((run, program) in listOf(outer to orchestrator, source to producer, target to recipient)) {
                assertEquals("completed", run["status"], jsonOf(run))
                assertEquals(true, run["ok"])
                assertEquals("lcnc/program/${program.name}@${LcncBlackboard.cidOf(program)}", run["workgroup"])
                assertEquals(program.controls.subnet, run["claimedAtSubnet"])
                assertEquals(outer["executionFamily"], run["executionFamily"])
                val lineage = assertIs<List<*>>(run["claimLineage"]).map { assertIs<Map<*, *>>(it)["workgroup"] }
                assertEquals(if (program === orchestrator) listOf(rootGroup) else listOf(rootGroup, run["workgroup"]), lineage)
                if (program !== orchestrator) {
                    assertEquals(outer["runId"], run["parentRunId"])
                    assertEquals(outer["claimId"], run["parentClaimId"])
                    assertEquals(rootGroup, run["parentWorkgroup"])
                }
                val cid = ContentId(assertIs<String>(run["receiptCid"]))
                val bytes = assertNotNull(rig.ctx.casStore.get(cid))
                assertEquals(cid, ContentId.of(bytes))
                val command = reifyMap(bytes)
                assertEquals("complete", command["type"])
                val retained = assertIs<Map<*, *>>(command["lcncRun"])
                for (key in listOf("runId", "status", "programCid", "executionFamily", "parentRunId", "claimLineage", "returns"))
                    assertEquals(run[key], retained[key], "The committed terminal command retains $key")
                assertEquals(cid.value, normalized(rig.ctx.blackboard.get("lcnc/run/${run["runId"]}"))["receiptCid"])
            }
            assertEquals(3, listOf(outer, source, target).map { it["claimId"] }.distinct().size)
            assertEquals(listOf("task-a", "task-b"), Files.readAllLines(launches),
                "Exactly two actual CLI launches; recipient pollination cannot replay an agent")
            assertEquals(sourceHead, git("rev-parse", "HEAD"))
            assertEquals(sourceStatus, git("status", "--porcelain"), "The source's dirty worktree is unchanged")
            assertFalse(File(sourceRepo, "task-a.txt").exists())
            assertFalse(File(sourceRepo, "task-b.txt").exists())
            assertFalse(File(sourceRepo, ".git/worktrees").exists())
            println("LCNC_PROCESS_COMPOSITION_PROOF=" + jsonOf(mapOf(
                "runs" to s_["outer" j outer, "source" j source, "target" j target].view.associate { (label, run) ->
                    label to s_["program", "programCid", "receiptCid", "subnet", "claimedAtSubnet", "workgroup", "claimId",
                        "claimLineage", "executionFamily", "parentRunId", "parentClaimId", "parentWorkgroup", "sourceReceiptCid"]
                        .view.associate { key -> key to run[key] }
                },
                "tasks" to records,
                "gathered" to s_["summary", "ballots", "quorum", "quorumMet", "accepted", "tally"]
                    .view.associate { key -> key to gathered[key] },
                "launches" to Files.readAllLines(launches), "cleanupAttempts" to cleanupAttempts.toList(),
                "sourceHead" to sourceHead, "sourceStatus" to sourceStatus,
            )))
        } finally {
            withContext(NonCancellable) {
                try { rig.close() }
                finally {
                    withContext(Dispatchers.IO) {
                        try { nio.drain() } finally { backing.deleteRecursively(failedScratch.path) }
                    }
                }
            }
        }
        assertFalse(failedScratch.exists(), "Fixture cleanup removes the actual scratch left by the deliberate fault")
    }
}
