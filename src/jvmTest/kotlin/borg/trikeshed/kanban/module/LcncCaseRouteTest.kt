package borg.trikeshed.kanban.module

import borg.trikeshed.context.nuid.*
import borg.trikeshed.couch.*
import borg.trikeshed.couch.persistence.CouchCommitStore
import borg.trikeshed.dag.*
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.*
import borg.trikeshed.kanban.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lcnc.ccek.*
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.modelmux.BrainChatOptions
import borg.trikeshed.module.*
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.userspace.nio.file.spi.StorageDurability
import borg.trikeshed.userspace.reactor.MuxReactorElement
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/** Case creation and one authored edge through the existing HTTP queue and owned worker. */
class LcncCaseRouteTest {
    @TempDir lateinit var directory: Path

    data class Seat(
        val node: LcncNode,
        val system: String,
        val prompt: String,
        val options: BrainChatOptions,
        val workgroup: String,
        val claim: Long,
        val programCid: String,
        val invocation: Map<String, Any?>,
    )

    data class Legal(
        val node: LcncNode,
        val brief: String,
        val workgroup: String,
        val claim: Long,
        val programCid: String,
        val invocation: Map<String, Any?>,
    )

    class Rig(
        val ctx: ModuleContext,
        val supervisor: ModuleSupervisor,
        val reactor: MuxReactorElement,
        val cas: CasStore,
        val storage: PackCasStore,
        val commits: CouchCommitStore,
        val calls: MutableList<String>,
        val seats: MutableList<Seat>,
        val legalCalls: MutableList<String>,
        val legal: MutableList<Legal>,
        val records: MutableList<String>,
    ) {
        val server = JvmKanbanServer(moduleRoutes = ctx.routes)

        suspend fun close(): Unit = withContext(NonCancellable) {
            val failures = mutableListOf<Throwable>()
            suspend fun release(action: suspend () -> Unit) {
                try { action() } catch (failure: Throwable) { failures.add(failure) }
            }
            release { supervisor.detach("kanban") }
            try { ctx.drainCouncilCases() } catch (failure: Throwable) {
                failures.forEach(failure::addSuppressed)
                throw failure // Failed settlement must leave its reactor, parent and storage available.
            }
            release { reactor.drain() }
            release { ctx.scope.coroutineContext.job.cancelAndJoin() }
            release { commits.close() }
            release { storage.close() }
            if (failures.isNotEmpty()) throw IllegalStateException("LCNC case fixture cleanup failed", failures.first()).also { failure ->
                failures.drop(1).forEach(failure::addSuppressed)
            }
        }
    }

    val programs = s_["preset-tribunal-case", "preset-legal-tribunal-case"]

    fun preset(name: String): LcncProgram = LcncProgramConfix.fromJson(name, LcncPresets.all().getValue(name))

    suspend fun invocation(node: LcncNode): Join<LcncWorkgroup, Map<String, Any?>> {
        val context = currentCoroutineContext()
        val selected = NuidInvocation.require()
        val live = assertNotNull(assertNotNull(context[NuidFanoutElement]).currentInvocation(),
            "Node execution must belong to an active NUID slot")
        assertSame(selected, live, "Public invocation metadata alone cannot authorize this effect")
        val program = LcncWorkgroupKey.require()
        assertEquals(selected.a, program.a)
        assertSame(selected.b, program.b.a)
        val invocation = assertNotNull(context[LcncInvocationLedger.Invocation]).values.toMap()
        assertEquals(node.id, invocation["nodeId"])
        assertEquals(node.type, invocation["type"])
        return program j invocation
    }

    fun grounded(brief: String): String = "Grounded citations:\n  - Miranda v. Arizona U.S. 436\nsource: $brief"

    suspend fun fixture(program: String = "preset-tribunal-case", seatFailure: Throwable? = null,
                        seatFailureNode: String? = null, casView: ((CasStore) -> CasStore)? = null,
                        dialog: TribunalDialog? = null): Rig {
        val files = JvmFileOperations()
        val root = directory.resolve(program)
        val storage = PackCasStore(files, root.resolve("cas").toString())
        val cas = casView?.invoke(storage) ?: storage
        val commits = CouchCommitStore(cas, StorageDurability.DURABLE, files, root.resolve("couch").toString())
        val store = CouchStoreFactory.durableCasBacked(cas, commits)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val reactor = MuxReactorElement(parentJob = scope.coroutineContext.job)
        reactor.open()
        val ctx = ModuleContext(
            couch = Couch("lcnc-case-test", store, cas), rete = ReteNetwork(), productions = ReteProductionRegistry(),
            beliefBag = null, turnReview = null, blackboard = ConfixBlackboard.empty(), casStore = cas,
            attachments = CouchAttachmentGateway(store, cas), routes = ModuleRouteRegistry(), scope = scope,
            clock = { 1234L }, stateDir = root.toFile(),
            ccekBinding = ccekReactorBinding(scope.coroutineContext + reactor),
        )
        val calls = mutableListOf<String>()
        val seats = mutableListOf<Seat>()
        val legalCalls = mutableListOf<String>()
        val legal = mutableListOf<Legal>()
        val records = mutableListOf<String>()
        ctx.lcncRunners.putAll(PureNodes.registry { 1L })
        ctx.lcncRunners.putAll(CouncilNodes.registry(CouncilDialog { error("Unexpected council seat") }))
        // Scoped wiring fakes from LegalTribunalExecutionTest; canonical curation has its own host proof.
        ctx.lcncRunners["legal.ingest"] = LcncNodeRunner { node, inputs ->
            legalCalls += node.id
            val text = (node.params["brief"]?.takeIf { it.isNotBlank() }?.let { briefName ->
                currentCoroutineContext()[LcncScopeFrame]?.binding(briefName)?.toString()
            } ?: (inputs["text"] as? String) ?: (inputs["text?"] as? String)
                ?: node.params["text"]?.takeIf { it.isNotBlank() })?.takeIf { it.isNotBlank() }
            require(text != null) { "legal.ingest: no text bound, wired, or in params" }
            val actual = invocation(node)
            legal += Legal(node, text, actual.a.a.name, actual.a.b.a.claimId, actual.a.b.b.value, actual.b)
            mapOf(
                "citations" to listOf(mapOf("case" to "Miranda v. Arizona", "reporter" to "U.S.", "page" to "436")),
                "elements" to mapOf("holdings" to emptyList<Any?>(), "parties" to emptyList<Any?>(), "refused" to emptyList<Any?>()),
                "brief" to grounded(text),
            )
        }
        ctx.lcncRunners["legal.evidence"] = LcncNodeRunner { node, inputs ->
            legalCalls += node.id
            val brief = ((inputs["brief"] ?: inputs["brief?"]) as? String).orEmpty()
            val actual = invocation(node)
            legal += Legal(node, brief, actual.a.a.name, actual.a.b.a.claimId, actual.a.b.b.value, actual.b)
            mapOf("brief" to brief)
        }
        ctx.lcncRunners.putAll(TribunalNodes.registry(TribunalDialog { node, system, prompt ->
            calls += node.id
            val actual = invocation(node)
            seats += Seat(node, system, prompt, BrainChatOptions.parse(node.params),
                actual.a.a.name, actual.a.b.a.claimId, actual.a.b.b.value, actual.b)
            if (seatFailureNode == null || node.id == seatFailureNode) seatFailure?.let { throw it }
            val text = when (node.id) {
                "n4" -> """{"disposition":"clarify","needsClarification":true,"clarificationQuestion":"Explain the source","mistrial":false}"""
                "cl.deliberate" -> """{"disposition":"granted","needsClarification":false,"clarificationQuestion":"","mistrial":false}"""
                else -> "${node.id}:$prompt"
            }
            dialog?.seat(node, system, prompt) ?: (text to "fixture/${node.id}")
        }, tribunalRecord(cas) { verdict ->
            records += verdict.value
            verdict.value
        }))
        val supervisor = ModuleSupervisor(ctx)
        supervisor.attach(KanbanModule())
        return Rig(ctx, supervisor, reactor, cas, storage, commits, calls, seats, legalCalls, legal, records)
    }

    suspend fun post(rig: Rig, request: Map<String, Any?>): JvmKanbanServer.HttpResponse =
        postRaw(rig, jsonOf(request))

    suspend fun postRaw(rig: Rig, body: String): JvmKanbanServer.HttpResponse =
        rig.server.routeHttp(("POST /api/lcnc/run HTTP/1.1\r\nHost: t\r\nContent-Type: application/json\r\n\r\n" +
            body).encodeToByteArray())

    fun rawTransition(initial: Map<String, Any?>, revision: String, key: String): String =
        """{"program":"preset-tribunal-case","expectProgramCid":${jsonOf(initial["programCid"])},"case":{"intent":"transition","caseId":${jsonOf(initial["caseId"])},"idempotencyKey":${jsonOf(key)},"expectedRevision":$revision,"priorStateCid":${jsonOf(initial["stateCid"])},"requestedTarget":"argue"}}"""

    fun initialize(caseId: String = "matter", key: String = "open", brief: String = "Motion: admit the source",
                   program: String = "preset-tribunal-case") =
        mapOf("program" to program, "inputs" to mapOf("brief" to brief),
            "case" to mapOf("intent" to "initialize", "caseId" to caseId, "idempotencyKey" to key))

    fun transition(initial: Map<String, Any?>, key: String = "argue", target: String = "argue",
                   program: String = "preset-tribunal-case"): Map<String, Any?> =
        mapOf("program" to program, "expectProgramCid" to initial["programCid"],
            "case" to mapOf("intent" to "transition", "caseId" to initial["caseId"], "idempotencyKey" to key,
                "expectedRevision" to initial["revision"], "priorStateCid" to initial["stateCid"], "requestedTarget" to target))

    fun changedCase(request: Map<String, Any?>, fields: Map<String, Any?>): Map<String, Any?> {
        val prior = assertIs<Map<*, *>>(request["case"]).entries.associate { it.key.toString() to it.value }
        return request + ("case" to (prior + fields))
    }

    fun blob(rig: Rig, cid: Any?): ByteArray {
        val reference = ContentId(assertIs<String>(cid))
        return assertNotNull(rig.cas.get(reference), reference.value).also {
            assertEquals(reference, ContentId.of(it), "Referenced bytes must match their actual CID")
        }
    }

    fun receipt(rig: Rig, response: JvmKanbanServer.HttpResponse): Map<String, Any?> {
        assertEquals(200, response.status, response.body)
        val run = reifyMap(response.body)
        assertEquals("completed", run["status"], response.body)
        assertEquals(true, run["ok"], response.body)
        assertTrue((run["claimId"] as Number).toLong() > 0)
        assertEquals("lcnc/program/${run["program"]}@${run["programCid"]}", run["workgroup"])
        val retained = assertIs<Map<*, *>>(reifyMap(blob(rig, run["receiptCid"]))["lcncRun"])
        for (key in listOf("runId", "status", "claimId", "workgroup", "programCid", "case"))
            assertEquals(run[key], retained[key], "The ordinary committed receipt must retain $key")
        return run
    }

    fun metadata(rig: Rig, run: Map<String, Any?>, caseId: String = "matter"): Map<String, Any?> {
        val view = assertIs<Map<*, *>>(run["case"]).entries.associate { it.key.toString() to it.value }
        assertEquals(caseId, view["caseId"])
        val bytes = blob(rig, view["snapshotCid"])
        val snapshot = CanonicalCbor.decodeJobSnapshot(bytes)
        assertContentEquals(bytes, CanonicalCbor.encode(snapshot))
        assertEquals(JobId.of("case/$caseId"), snapshot.jobId)
        assertEquals((view["revision"] as Number).toLong(), snapshot.revision)
        assertEquals(view["lifecycle"], snapshot.lifecycle)
        assertEquals(view["stateCid"], CanonicalCbor.decodeMap(bytes)["stateCid"])
        val state = CanonicalCbor.decodeMap(blob(rig, view["stateCid"]))
        assertEquals("lcnc-case-state", state["kind"])
        assertEquals(1L, state["version"])
        for (key in listOf("caseId", "programCid", "lane"))
            assertEquals(view[key], state[key], "The response must describe its actual authenticated head's $key")
        assertEquals(view["phase"], if (state["phase"] == "admitted") "interrupted" else state["phase"],
            "An admitted head without completion must be presented as interrupted")
        blob(rig, view["programCid"])
        assertEquals(run["programCid"], view["programCid"])
        return view
    }

    fun queuedCaseRequest(rig: Rig, run: Map<String, Any?>): Map<String, Any?> {
        var queued = run
        while (queued["status"] != "queued") queued = assertIs<Map<*, *>>(
            reifyMap(blob(rig, queued["previousReceiptCid"]))["lcncRun"]).entries.associate { it.key.toString() to it.value }
        val selected = assertIs<Map<*, *>>(assertIs<Map<*, *>>(queued["queueRequest"])["case"])
        assertEquals(setOf("intent", "requestCid"), selected.keys)
        val retained = CanonicalCbor.decodeMap(blob(rig, selected["requestCid"]))
        assertEquals("lcnc-case-request", retained["kind"])
        assertEquals(1L, retained["version"])
        assertEquals(selected["intent"], retained["intent"])
        assertEquals(run["programCid"], retained["programCid"])
        return retained
    }

    fun caseFrames(rig: Rig, caseId: String = "matter"): List<JobLog.Frame> =
        CouchJobLog.open(rig.ctx.couch, "council-log/${ContentId.of(caseId.encodeToByteArray()).hex}").replay().toList()

    fun sameState(expected: Map<String, Any?>, actual: Map<String, Any?>) {
        for (key in listOf("caseId", "snapshotCid", "revision", "stateCid", "programCid", "lifecycle", "lane", "phase"))
            assertEquals(expected[key], actual[key], "Case $key changed")
    }

    fun noEffects(rig: Rig) {
        assertTrue(rig.calls.isEmpty(), "Initialization/read/refusal reached provider nodes: ${rig.calls}")
        assertTrue(rig.legalCalls.isEmpty(), "Initialization/read/refusal reached the legal prefix: ${rig.legalCalls}")
        assertTrue(rig.records.isEmpty(), "Initialization/read/refusal reached the terminal record callback")
    }

    fun authored(nodes: Series<LcncNode>, id: String): LcncNode? {
        for (node in nodes.view) {
            if (node.id == id) return node
            authored(node.children, id)?.let { return it }
        }
        return null
    }

    fun argueOnly(rig: Rig, run: Map<String, Any?>, brief: String) {
        assertEquals(listOf("n2"), rig.calls)
        val call = rig.seats.single()
        assertEquals("n2", call.node.id)
        assertTrue(assertIs<List<*>>(call.invocation["path"]).isNotEmpty(), "Counsel must execute inside its visible authored scope")
        val originalProgram = preset(run["program"].toString().removeSuffix("-case"))
        val original = assertNotNull(authored(originalProgram.nodes, "n2"))
        assertEquals(original.params, call.node.params, "The actual authored counsel and model options must execute")
        assertEquals(original.params["system"], call.system)
        val isLegal = run["program"] == "preset-legal-tribunal-case"
        assertEquals(if (isLegal) grounded(brief) else brief, call.prompt,
            "Counsel must receive the persisted brief through the authored prefix")
        val options = BrainChatOptions.parse(original.params)
        assertEquals(options.maxTokens, call.options.maxTokens)
        assertEquals(options.temperature, call.options.temperature)
        assertEquals(options.models.view.map { it.pair }, call.options.models.view.map { it.pair })
        assertEquals(run["workgroup"], call.workgroup)
        assertEquals((run["claimId"] as Number).toLong(), call.claim)
        assertEquals(run["programCid"], call.programCid)
        assertTrue(rig.records.isEmpty())
        val observations = reifyMap(blob(rig, run["invocationsCid"]))
        val entries = assertIs<List<*>>(observations["entries"]).map { assertIs<Map<*, *>>(it) }
        if (isLegal) {
            assertEquals(listOf("n1", "n1b"), rig.legalCalls)
            assertEquals(listOf(brief, grounded(brief)), rig.legal.map { it.brief })
            for (prefix in rig.legal) {
                assertEquals(assertNotNull(authored(originalProgram.nodes, prefix.node.id)).params, prefix.node.params)
                assertEquals(run["workgroup"], prefix.workgroup)
                assertEquals((run["claimId"] as Number).toLong(), prefix.claim)
                assertEquals(run["programCid"], prefix.programCid)
                assertEquals(call.invocation["path"], prefix.invocation["path"], "The original legal prefix belongs inside the counsel scope")
            }
            assertEquals(listOf("n1", "n1b"), entries.filter {
                it["type"] in setOf("legal.ingest", "legal.evidence")
            }.map { it["nodeId"] })
            assertTrue(entries.filter { it["nodeId"] in setOf("n1", "n1b") }.all { it["status"] == "completed" })
        } else assertTrue(rig.legalCalls.isEmpty())
        assertEquals(if (isLegal) listOf("n1", "n1b", "n2") else listOf("n2"), entries.filter {
            it["type"] in setOf("legal.ingest", "legal.evidence", "mux.chat")
        }.map { it["nodeId"] })
        assertEquals(listOf("n2"), entries.filter { it["type"] == "mux.chat" }.map { it["nodeId"] })
        assertTrue(entries.none { it["type"] == "tribunal.record" }, "No terminal node may run within argue")
        assertEquals("completed", entries.single { it["nodeId"] == "n2" }["status"])
    }

    val loopPrograms = s_["preset-tribunal-case-v2", "preset-legal-tribunal-case-v2"]

    data class Completion(val request: Map<String, Any?>, val run: Map<String, Any?>,
                          val view: Map<String, Any?>, val outputs: Map<String, Any?>)

    fun judge(needs: Any? = false, question: String = "", mistrial: Boolean = false): String = jsonOf(mapOf(
        "disposition" to if (mistrial) "mistrial" else if (needs == true || needs == "true") "clarify" else "granted",
        "needsClarification" to needs, "clarificationQuestion" to question, "mistrial" to mistrial))

    fun loopDialog(judges: Series<String>): TribunalDialog {
        val calls = AtomicInteger()
        val rulings = AtomicInteger()
        return TribunalDialog { node, _, _ ->
            val ordinal = calls.incrementAndGet()
            val content = if (node.id == "n4") judges[rulings.getAndIncrement()] else "Accepted answer $ordinal from ${node.id}"
            content to "fixture/${node.id}/$ordinal"
        }
    }

    fun head(rig: Rig, view: Map<String, Any?>): Map<String, Any?> = CanonicalCbor.decodeMap(blob(rig, view["stateCid"]))
    fun io(rig: Rig, view: Map<String, Any?>): Map<*, *> = assertIs<Map<*, *>>(head(rig, view)["io"])
    fun counter(rig: Rig, view: Map<String, Any?>, key: String): Long = (io(rig, view)[key] as? Number)?.toLong() ?: 0L

    suspend fun loopInitialize(rig: Rig, program: String, brief: String, caseId: String = "matter"): Completion {
        val request = initialize(caseId, "open-$caseId", brief, program)
        val calls = rig.calls.toList()
        val prefix = rig.legalCalls.toList()
        val response = post(rig, request)
        assertEquals(calls, rig.calls)
        assertEquals(prefix, rig.legalCalls)
        assertTrue(rig.records.isEmpty())
        val run = receipt(rig, response)
        return Completion(request, run, metadata(rig, run, caseId), emptyMap())
    }

    suspend fun loopNext(rig: Rig, prior: Completion, program: String, key: String, target: String,
                         id: String, prompt: String, c: Long, q: Long, caseId: String = "matter"): Completion {
        val request = transition(prior.view, key, target, program)
        val attempted = rig.calls.size
        val response = post(rig, request)
        assertEquals(attempted + 1, rig.calls.size,
            "The actual selected provider must execute before success metadata; an unsupported LOOP is behavioral RED")
        assertEquals(id, rig.calls.last())
        val run = receipt(rig, response)
        val actual = rig.seats.last()
        val originalId = if (id == "case.argue.repeat.seat") "n2" else id
        val original = assertNotNull(authored(preset(program.removeSuffix("-case-v2")).nodes, originalId))
        assertEquals(original.params, actual.node.params)
        assertEquals(original.params["system"], actual.system)
        assertEquals(prompt, actual.prompt)
        val options = BrainChatOptions.parse(original.params)
        assertEquals(options.maxTokens, actual.options.maxTokens)
        assertEquals(options.temperature, actual.options.temperature)
        assertEquals(options.models.view.map { it.pair }, actual.options.models.view.map { it.pair })
        assertEquals(run["workgroup"], actual.workgroup)
        assertEquals((run["claimId"] as Number).toLong(), actual.claim)
        assertEquals(run["programCid"], actual.programCid)
        assertTrue(assertIs<List<*>>(actual.invocation["path"]).isNotEmpty())
        val entries = assertIs<List<*>>(reifyMap(blob(rig, run["invocationsCid"]))["entries"]).map { assertIs<Map<*, *>>(it) }
        assertEquals(listOf(id), entries.filter { it["type"] == "mux.chat" }.map { it["nodeId"] })
        assertEquals("completed", entries.single { it["nodeId"] == id }["status"])
        assertTrue(entries.none { it["type"] == "tribunal.record" })
        if (id != "n2") assertTrue(entries.none { it["type"] in setOf("legal.ingest", "legal.evidence") })
        val view = metadata(rig, run, caseId)
        assertEquals(target, view["lane"])
        assertEquals("active", view["lifecycle"])
        assertEquals("completed", view["phase"])
        assertEquals((prior.view["revision"] as Number).toLong() + 2L, (view["revision"] as Number).toLong())
        assertEquals(c, counter(rig, view, "loop.iterations.rebut-argue"))
        assertEquals(q, counter(rig, view, "loop.iterations.deliberate-clarify"))
        val prefixes: Series<String> = if (program == "preset-legal-tribunal-case-v2")
            (rig.calls.count { it == "n2" } * 2) j { index: Int -> if (index % 2 == 0) "n1" else "n1b" }
        else emptySeriesOf()
        assertEquals<List<String>>(prefixes.view.toList(), rig.legalCalls)
        assertTrue(rig.records.isEmpty())
        val output = result(rig, view)
        assertEquals(prompt, output["prompt"])
        return Completion(request, run, view, output)
    }

    fun source(completion: Completion): Map<String, Any?> = mapOf("lane" to completion.view["lane"],
        "stateCid" to completion.view["stateCid"], "snapshotCid" to completion.view["snapshotCid"])

    fun loopRound(rig: Rig, completion: Completion, initial: Completion, argument: Completion,
                  sources: Series<Completion>, clarification: Completion? = null) {
        for (cid in s_[completion.view["admissionCid"], completion.view["stateCid"]].view) {
            val state = CanonicalCbor.decodeMap(blob(rig, cid))
            val pending = state["lane"] == "argue" && state["phase"] == "admitted"
            val expected = mapOf("kind" to "lcnc-case-round", "version" to 2L,
                "initialStateCid" to initial.view["stateCid"],
                "sources" to (sources α { source(it) }).view.toList(),
                "argueAdmissionCid" to if (pending) null else argument.view["admissionCid"],
                "clarificationSource" to clarification?.let(::source))
            val actualIo = assertIs<Map<*, *>>(state["io"])
            assertEquals(expected, actualIo["lcnc.case.round"])
            for (key in s_["loop.iterations.rebut-argue", "loop.iterations.deliberate-clarify"].view)
                assertEquals(counter(rig, completion.view, key), (actualIo[key] as? Number)?.toLong() ?: 0L)
            if (state["lane"] != "deliberate" || state["phase"] != "completed") {
                assertEquals(false, actualIo["clarification"])
                assertFalse(actualIo.containsKey("lcnc.case.ruling"))
            }
        }
        for (accepted in sources.view) {
            val snapshot = CanonicalCbor.decodeJobSnapshot(blob(rig, accepted.view["snapshotCid"]))
            assertEquals(JobId.of("case/${initial.view["caseId"]}"), snapshot.jobId)
            assertEquals(accepted.view["stateCid"], snapshot.stateCid)
            assertEquals("completed", head(rig, accepted.view)["phase"])
            result(rig, accepted.view)
        }
    }

    fun judgeProjection(rig: Rig, completion: Completion) {
        val parsed = CouncilNodes.ruling(assertIs<String>(completion.outputs["content"]))
        val actual = io(rig, completion.view)
        assertEquals(parsed["needsClarification"], actual["clarification"])
        assertEquals(mapOf("evidenceCid" to completion.view["evidenceCid"],
            "needsClarification" to parsed["needsClarification"], "clarificationQuestion" to parsed["clarificationQuestion"],
            "mistrial" to parsed["mistrial"]), actual["lcnc.case.ruling"])
    }

    suspend fun loopRefused(rig: Rig, completion: Completion, program: String, key: String, target: String,
                            error: String, caseId: String = "matter") {
        val calls = rig.calls.toList()
        val prefix = rig.legalCalls.toList()
        val frames = caseFrames(rig, caseId)
        val response = post(rig, transition(completion.view, key, target, program))
        assertEquals(calls, rig.calls)
        assertEquals(prefix, rig.legalCalls)
        assertTrue(rig.records.isEmpty())
        assertEquals(409, response.status, response.body)
        assertTrue(assertIs<String>(reifyMap(response.body)["error"]).contains(error), response.body)
        assertEquals(frames, caseFrames(rig, caseId))
    }

    fun counselRecord(brief: String, a: Completion, b: Completion): String =
        transcript(brief, s_["argue" j a.outputs, "rebut" j b.outputs])

    suspend fun legacyFold(rig: Rig, program: String, id: String, brief: Any?, parts: Any?): String {
        val node = assertNotNull(authored(preset(program.removeSuffix("-case-v2")).nodes, id))
        assertEquals("text.fold", node.type)
        val output = rig.ctx.lcncRunners.getValue("text.fold").execute(node, mapOf("brief?" to brief, "parts" to parts))
        return assertIs<String>(output["text"])
    }

    suspend fun initialRecord(rig: Rig, program: String, a: Completion, b: Completion, j: Completion): String =
        legacyFold(rig, program, "fold.record", a.outputs["prompt"],
            s_[a.outputs["content"], b.outputs["content"], j.outputs["content"]].view.toList())

    fun headHistory(rig: Rig, final: Completion, expected: Int) {
        val heads = mutableSetOf<String>()
        var cid: Any? = final.view["stateCid"]
        while (cid != null) {
            assertTrue(heads.add(assertIs<String>(cid)), "The actual retained history must not cycle")
            val state = CanonicalCbor.decodeMap(blob(rig, cid))
            assertEquals(final.view["caseId"], state["caseId"])
            assertEquals(final.view["programCid"], state["programCid"])
            cid = state["priorStateCid"]
        }
        assertEquals(expected, heads.size)
        val states = caseFrames(rig).map { CanonicalCbor.decodeJobCommand(it.payload) }.filterIsInstance<JobCommand.State>()
        assertEquals(expected, states.size)
        assertEquals(heads, states.map { it.stateCid }.toSet())
    }

    suspend fun historical(rig: Rig, completed: Series<Completion>, current: Completion, program: String) {
        val frames = caseFrames(rig)
        val calls = rig.calls.toList()
        val prefix = rig.legalCalls.toList()
        for (accepted in completed.view) {
            val response = post(rig, accepted.request)
            assertEquals(calls, rig.calls)
            assertEquals(prefix, rig.legalCalls)
            assertTrue(rig.records.isEmpty())
            val run = receipt(rig, response)
            sameState(accepted.view, metadata(rig, run))
            assertEquals(accepted.outputs, run["returns"])
            val read = mapOf("program" to program, "case" to mapOf("intent" to "read", "caseId" to "matter"))
            sameState(current.view, metadata(rig, receipt(rig, post(rig, read))))
            assertEquals(frames, caseFrames(rig))
        }
    }

    data class TerminalCall(val node: LcncNode, val text: String, val invocation: Map<String, Any?>,
                            val workgroup: String, val claim: Long, val programCid: String,
                            var output: Map<String, Any?>? = null)

    enum class RecordFault { BEFORE, AFTER }

    class TerminalProbe {
        val calls = mutableListOf<TerminalCall>()
        val fault = AtomicReference<RecordFault?>()
    }

    fun terminalProbe(rig: Rig): TerminalProbe {
        val probe = TerminalProbe()
        val runner = rig.ctx.lcncRunners.getValue("tribunal.record")
        val services = assertIs<LcncServiceBinding>(runner)
        rig.ctx.lcncRunners["tribunal.record"] = object : LcncNodeRunner by runner, LcncServiceBinding by services {
            override suspend fun run(node: LcncNode, inputs: Map<String, Any?>): Map<String, Any?> =
                super<LcncNodeRunner>.run(node, inputs)

            override suspend fun execute(node: LcncNode, inputs: Map<String, Any?>): Map<String, Any?> {
                val owned = invocation(node)
                val text = assertIs<String>(inputs["text"] ?: inputs["text?"])
                val call = TerminalCall(node, text, owned.b, owned.a.a.name, owned.a.b.a.claimId, owned.a.b.b.value)
                probe.calls.add(call)
                if (probe.fault.compareAndSet(RecordFault.BEFORE, null)) error("fixture record before delivery")
                val output = runner.execute(node, inputs)
                call.output = output
                if (probe.fault.compareAndSet(RecordFault.AFTER, null)) error("fixture terminal report acknowledgement lost")
                return output
            }
        }
        return probe
    }

    suspend fun terminalReady(rig: Rig, program: String, brief: String, caseId: String = "matter"): Series<Completion> {
        val initial = loopInitialize(rig, program, brief, caseId)
        val a = loopNext(rig, initial, program, "A-$caseId", "argue", "n2",
            if (program == "preset-legal-tribunal-case-v2") grounded(brief) else brief, 0L, 0L, caseId)
        val b = loopNext(rig, a, program, "B-$caseId", "rebut", "n3",
            transcript(brief, s_["argue" j a.outputs]), 0L, 0L, caseId)
        val j = loopNext(rig, b, program, "J-$caseId", "deliberate", "n4", counselRecord(brief, a, b), 0L, 0L, caseId)
        return s_[initial, a, b, j]
    }

    fun terminalView(rig: Rig, run: Map<String, Any?>, caseId: String = "matter"): Map<String, Any?> {
        val view = assertIs<Map<*, *>>(run["case"]).entries.associate { it.key.toString() to it.value }
        assertEquals(caseId, view["caseId"])
        val snapshotBytes = blob(rig, view["snapshotCid"])
        val snapshot = CanonicalCbor.decodeJobSnapshot(snapshotBytes)
        assertContentEquals(snapshotBytes, CanonicalCbor.encode(snapshot))
        assertEquals(JobId.of("case/$caseId"), snapshot.jobId)
        assertEquals((view["revision"] as Number).toLong(), snapshot.revision)
        assertEquals(view["lifecycle"], snapshot.lifecycle)
        assertEquals(view["stateCid"], snapshot.stateCid)
        val state = head(rig, view)
        assertEquals("terminal-prepared", state["phase"])
        assertNull(state["admissionCid"])
        assertContentEquals(blob(rig, view["stateCid"]), encodeLcncCase(state))
        for (key in s_["caseId", "programCid", "lane"].view) assertEquals(view[key], state[key])
        assertEquals(run["programCid"], view["programCid"])
        val prepared = CanonicalCbor.decodeJobSnapshot(blob(rig, view["preparationSnapshotCid"]))
        assertEquals(snapshot.jobId, prepared.jobId)
        assertEquals("active", prepared.lifecycle)
        assertEquals(snapshot.stateCid, prepared.stateCid)
        if (snapshot.lifecycle == "active") {
            assertEquals("terminal-pending", view["phase"])
            assertEquals(prepared.revision, snapshot.revision)
            assertNull(view["terminalReceiptCid"])
        } else {
            assertTrue(snapshot.lifecycle in setOf("closed", "failed"))
            assertEquals("settled", view["phase"])
            assertEquals(prepared.revision + 1L, snapshot.revision)
            assertEquals(view["snapshotCid"], view["terminalReceiptCid"])
        }
        assertEquals(state["evidenceCid"], view["terminalEvidenceCid"])
        val evidence = CanonicalCbor.decodeMap(blob(rig, view["terminalEvidenceCid"]))
        assertContentEquals(blob(rig, view["terminalEvidenceCid"]), encodeLcncCase(evidence))
        assertEquals("lcnc-case-terminal", evidence["kind"])
        assertEquals(1L, evidence["version"])
        for (key in s_["caseId", "programCid", "initializeRequestCid", "requestCid", "run"].view) assertEquals(state[key], evidence[key])
        assertEquals(view["terminalOperation"], evidence["operation"])
        assertEquals(view["terminalSource"], evidence["source"])
        assertEquals(state["priorStateCid"], assertIs<Map<*, *>>(evidence["source"])["stateCid"])
        val sourceSnapshot = CanonicalCbor.decodeJobSnapshot(blob(rig, assertIs<Map<*, *>>(evidence["source"])["snapshotCid"]))
        assertEquals(snapshot.jobId, sourceSnapshot.jobId)
        assertEquals(state["priorStateCid"], sourceSnapshot.stateCid)
        assertEquals("active", sourceSnapshot.lifecycle)
        assertEquals(sourceSnapshot.revision + 1L, prepared.revision)
        assertEquals(view["terminalTextCid"], evidence["textCid"])
        assertEquals(view["terminalTranscriptCid"], evidence["transcriptCid"])
        blob(rig, view["terminalTextCid"]).decodeToString(throwOnInvalidSequence = true)
        blob(rig, view["terminalTranscriptCid"]).decodeToString(throwOnInvalidSequence = true)
        val actualSources = assertIs<List<*>>(evidence["sources"]).map { assertIs<Map<*, *>>(it) }
        val acceptedSources = caseFrames(rig, caseId).map { CanonicalCbor.decodeJobCommand(it.payload) }
            .filterIsInstance<JobCommand.State>().filter { head(rig, mapOf("stateCid" to it.stateCid))["phase"] == "completed" }
        assertEquals(acceptedSources.map { it.stateCid }, actualSources.map { it["stateCid"] },
            "Final evidence retains every actual accepted completion, in log order")
        for (source in actualSources) {
            val selected = assertIs<Map<*, *>>(source)
            val retained = CanonicalCbor.decodeJobSnapshot(blob(rig, selected["snapshotCid"]))
            assertEquals(snapshot.jobId, retained.jobId)
            assertEquals(selected["stateCid"], retained.stateCid)
            val sourceHead = head(rig, mapOf("stateCid" to selected["stateCid"]))
            assertEquals("completed", sourceHead["phase"])
            assertEquals(selected["lane"], sourceHead["lane"])
            result(rig, mapOf("stateCid" to selected["stateCid"]))
        }
        return view
    }

    fun terminalReport(rig: Rig, run: Map<String, Any?>, view: Map<String, Any?>): Map<*, *> {
        val report = assertIs<Map<*, *>>(assertIs<Map<*, *>>(run["returns"])["report"])
        val text = blob(rig, view["terminalTextCid"]).decodeToString(throwOnInvalidSequence = true)
        assertEquals(text, report["text"])
        assertEquals(view["terminalTextCid"], report["cid"])
        assertEquals(view["terminalReceiptCid"], report["recorded"])
        assertEquals(text.length.toLong(), (report["chars"] as Number).toLong())
        return report
    }

    fun terminalFrames(rig: Rig, prior: Map<String, Any?>, current: Map<String, Any?>, request: Map<String, Any?>) {
        val commands = caseFrames(rig, assertIs<String>(current["caseId"])).map { CanonicalCbor.decodeJobCommand(it.payload) }
        val prepare = assertIs<JobCommand.State>(commands[commands.size - 2])
        val terminal = commands.last()
        assertTrue(terminal is JobCommand.Complete || terminal is JobCommand.Fail,
            "A retained terminal report must name an actual accepted lifecycle command")
        val head = head(rig, current)
        val cid = ContentId(assertIs<String>(head["requestCid"]))
        assertEquals("lcnc-case/${cid.hex}/prepare", prepare.idempotencyKey)
        assertEquals("lcnc-case/${cid.hex}/terminal", terminal.idempotencyKey)
        assertEquals(prior["stateCid"], prepare.priorStateCid)
        assertEquals(current["stateCid"], prepare.stateCid)
        assertEquals((prior["revision"] as Number).toLong(), prepare.expectedRevision)
        val terminalRevision = when (terminal) {
            is JobCommand.Complete -> terminal.expectedRevision
            is JobCommand.Fail -> terminal.expectedRevision
            else -> error("No actual terminal command")
        }
        assertEquals(prepare.expectedRevision + 1L, terminalRevision)
        assertEquals((prior["revision"] as Number).toLong() + 2L, (current["revision"] as Number).toLong())
        val retained = CanonicalCbor.decodeMap(blob(rig, head["requestCid"]))
        val asked = assertIs<Map<*, *>>(request["case"])
        assertEquals(asked["idempotencyKey"], retained["idempotencyKey"])
        assertEquals(asked["requestedTarget"], retained["requestedTarget"])
    }

    suspend fun terminalRead(rig: Rig, program: String, current: Map<String, Any?>) {
        val frames = caseFrames(rig, assertIs<String>(current["caseId"]))
        val calls = rig.calls.toList()
        val prefix = rig.legalCalls.toList()
        val request = mapOf("program" to program, "case" to mapOf("intent" to "read", "caseId" to current["caseId"]))
        sameState(current, terminalView(rig, receipt(rig, post(rig, request)), assertIs<String>(current["caseId"])))
        assertEquals(calls, rig.calls)
        assertEquals(prefix, rig.legalCalls)
        assertTrue(rig.records.isEmpty(), "The trusted case callback must replace the ambient legacy record service")
        assertEquals(frames, caseFrames(rig, assertIs<String>(current["caseId"])))
    }

    suspend fun unresolvedRuleRefused(rig: Rig, program: String, current: Completion, key: String) {
        val frames = caseFrames(rig)
        val calls = rig.calls.toList()
        val prefix = rig.legalCalls.toList()
        val original = rig.ctx.lcncRunners.getValue("tribunal.record")
        val probe = terminalProbe(rig)
        try {
            val response = post(rig, transition(current.view, key, "rule", program))
            assertEquals(409, response.status, response.body)
            assertTrue(assertIs<String>(reifyMap(response.body)["error"]).isNotBlank())
            assertTrue(probe.calls.isEmpty(), "An unresolved judge cannot enter the terminal record scope")
            assertEquals(calls, rig.calls)
            assertEquals(prefix, rig.legalCalls)
            assertTrue(rig.records.isEmpty())
            sameState(current.view, metadata(rig, reifyMap(response.body)))
            assertEquals(frames, caseFrames(rig), "Refusal must precede even terminal preparation")
        } finally { rig.ctx.lcncRunners["tribunal.record"] = original }
    }

    @Test fun resolvedRuleRecordsActualTerminalReceiptAndReopensWithoutAnotherSeat() = runBlocking<Unit> {
        for (program in loopPrograms.view) {
            val brief = "Motion: retain the real final ruling"
            val raw = if (program == "preset-legal-tribunal-case-v2") "The motion is granted on the retained record."
                else judge()
            val rig = fixture(program, dialog = loopDialog(s_[raw]))
            lateinit var completed: Series<Completion>
            lateinit var request: Map<String, Any?>
            lateinit var settled: Map<String, Any?>
            lateinit var report: Map<*, *>
            lateinit var committed: List<JobLog.Frame>
            try {
                val probe = terminalProbe(rig)
                completed = terminalReady(rig, program, brief)
                val j = completed[3]
                request = transition(j.view, "rule", "rule", program)
                val seats = rig.calls.toList()
                val response = post(rig, request)
                assertEquals(1, probe.calls.size,
                    "The actual zero-provider record scope must execute before terminal success metadata; HTTP ${response.status}: ${response.body}")
                assertIs<JobCommand.Complete>(CanonicalCbor.decodeJobCommand(caseFrames(rig).last().payload),
                    "The rule request must really close the case; an unsupported terminal boundary is behavioral RED")
                assertEquals(seats, rig.calls)
                val run = receipt(rig, response)
                settled = terminalView(rig, run)
                assertEquals("closed", settled["lifecycle"])
                assertEquals("rule", settled["lane"])
                assertEquals("complete", settled["terminalOperation"])
                assertEquals(true, run["terminalWalkObserved"])
                report = terminalReport(rig, run, settled)
                assertEquals(raw, report["text"], "Plain prose and parsed rulings retain their actual judge text")
                val actual = probe.calls.single()
                assertEquals("case.rule.record", actual.node.id)
                assertEquals(raw, actual.text)
                assertEquals(run["workgroup"], actual.workgroup)
                assertEquals((run["claimId"] as Number).toLong(), actual.claim)
                assertEquals(run["programCid"], actual.programCid)
                val delivered = assertIs<Map<*, *>>(assertIs<Map<*, *>>(actual.output)["report"])
                for (key in s_["text", "cid", "recorded"].view) assertEquals(report[key], delivered[key])
                assertEquals((report["chars"] as Number).toLong(), (delivered["chars"] as Number).toLong())
                val entries = assertIs<List<*>>(reifyMap(blob(rig, run["invocationsCid"]))["entries"])
                    .map { assertIs<Map<*, *>>(it) }
                assertEquals(listOf("case.rule.record"), entries.filter { it["type"] == "tribunal.record" }.map { it["nodeId"] })
                assertEquals("completed", entries.single { it["nodeId"] == "case.rule.record" }["status"])
                assertTrue(entries.none { it["type"] == "mux.chat" || it["type"] in setOf("legal.ingest", "legal.evidence") })
                terminalFrames(rig, j.view, settled, request)
                assertEquals(11, caseFrames(rig).size)
                committed = caseFrames(rig)
                val replay = receipt(rig, post(rig, request))
                sameState(settled, terminalView(rig, replay))
                assertEquals(false, replay["terminalWalkObserved"])
                assertEquals(report, terminalReport(rig, replay, settled))
                assertEquals(1, probe.calls.size)
                terminalRead(rig, program, settled)
                for (accepted in completed.view) {
                    val old = receipt(rig, post(rig, accepted.request))
                    sameState(accepted.view, metadata(rig, old))
                    assertEquals(accepted.outputs, old["returns"])
                    terminalRead(rig, program, settled)
                    assertEquals(1, probe.calls.size)
                }
            } finally { rig.close() }
            val reopened = fixture(program)
            try {
                val probe = terminalProbe(reopened)
                terminalRead(reopened, program, settled)
                val replay = receipt(reopened, post(reopened, request))
                noEffects(reopened)
                assertTrue(probe.calls.isEmpty())
                sameState(settled, terminalView(reopened, replay))
                assertEquals(false, replay["terminalWalkObserved"])
                assertEquals(report, terminalReport(reopened, replay, settled))
                for (accepted in completed.view) {
                    val old = receipt(reopened, post(reopened, accepted.request))
                    noEffects(reopened)
                    sameState(accepted.view, metadata(reopened, old))
                    assertEquals(accepted.outputs, old["returns"])
                    terminalRead(reopened, program, settled)
                }
                val changed = post(reopened, changedCase(request, mapOf("idempotencyKey" to "another-rule")))
                assertEquals(409, changed.status, changed.body)
                assertTrue(probe.calls.isEmpty())
                noEffects(reopened)
                assertEquals(committed, caseFrames(reopened))
                terminalRead(reopened, program, settled)
            } finally { reopened.close() }
        }
    }

    @Test fun explicitAbortSettlesIdleCompletedAndRecoveredInterruptedCasesWithoutAnotherSeat() = runBlocking<Unit> {
        val program = "preset-tribunal-case-v2"
        val actualMistrial = """{"disposition":"mistrial","needsClarification":false,"mistrial":true,"reason":"The source record is inconsistent.","preserved":"full judge text"}"""
        val dialog = TribunalDialog { node, _, prompt ->
            if (node.id == "n2" && prompt.contains("interrupt this counsel")) error("fixture interrupted counsel")
            (if (node.id == "n4") actualMistrial else "Accepted ${node.id}: $prompt") to "fixture/${node.id}"
        }
        val rig = fixture(program, dialog = dialog)
        val accepted = mutableListOf<Join<Map<String, Any?>, Map<String, Any?>>>()
        val initializers = mutableListOf<Completion>()
        try {
            val probe = terminalProbe(rig)
            for (kind in s_["idle", "completed"].view) {
                val brief = "Motion: abort $kind"
                val completed = if (kind == "completed") terminalReady(rig, program, brief, kind)
                    else s_[loopInitialize(rig, program, brief, kind)]
                initializers.add(completed[0])
                val prior = completed[completed.size - 1]
                val request = transition(prior.view, "abort-$kind", "mistrial", program)
                val calls = rig.calls.toList()
                val before = caseFrames(rig, kind)
                val response = post(rig, request)
                assertEquals(calls, rig.calls, "ABORT must never replay or add a provider seat")
                assertEquals(accepted.size + 1, probe.calls.size,
                    "An ABORT from outside the authored declaration's source must execute the real terminal scope")
                val command = assertIs<JobCommand.Fail>(CanonicalCbor.decodeJobCommand(caseFrames(rig, kind).last().payload))
                val run = receipt(rig, response)
                val settled = terminalView(rig, run, kind)
                assertEquals("failed", settled["lifecycle"])
                assertEquals("mistrial", settled["lane"])
                assertEquals("fail", settled["terminalOperation"])
                assertEquals(true, run["terminalWalkObserved"])
                val report = terminalReport(rig, run, settled)
                assertEquals(true, CouncilNodes.ruling(assertIs<String>(report["text"]))["mistrial"])
                if (kind == "completed") assertEquals(actualMistrial, report["text"], "A real model mistrial preserves all actual judge text")
                assertTrue(command.reason.isNotBlank())
                terminalFrames(rig, prior.view, settled, request)
                assertEquals(before.size + 2, caseFrames(rig, kind).size)
                accepted.add(request j settled)
                terminalRead(rig, program, settled)
            }
            val interrupted = loopInitialize(rig, program, "Motion: interrupt this counsel", "interrupted")
            initializers.add(interrupted)
            val failed = post(rig, transition(interrupted.view, "failed-counsel", "argue", program))
            assertEquals(400, failed.status, failed.body)
            assertEquals("fixture interrupted counsel", reifyMap(failed.body)["error"])
            val pending = metadata(rig, reifyMap(failed.body), "interrupted")
            assertEquals("interrupted", pending["phase"])
        } finally { rig.close() }
        val reopened = fixture(program)
        try {
            val probe = terminalProbe(reopened)
            val read = mapOf("program" to program, "case" to mapOf("intent" to "read", "caseId" to "interrupted"))
            val prior = metadata(reopened, receipt(reopened, post(reopened, read)), "interrupted")
            assertEquals("interrupted", prior["phase"])
            noEffects(reopened)
            val request = transition(prior, "abort-interrupted", "mistrial", program)
            val response = post(reopened, request)
            assertEquals(1, probe.calls.size)
            assertIs<JobCommand.Fail>(CanonicalCbor.decodeJobCommand(caseFrames(reopened, "interrupted").last().payload))
            noEffects(reopened)
            val run = receipt(reopened, response)
            val settled = terminalView(reopened, run, "interrupted")
            assertEquals("failed", settled["lifecycle"])
            terminalFrames(reopened, prior, settled, request)
            terminalReport(reopened, run, settled)
            accepted.add(request j settled)
            for (terminal in accepted) {
                val frames = caseFrames(reopened, assertIs<String>(terminal.b["caseId"]))
                val replay = receipt(reopened, post(reopened, terminal.a))
                assertEquals(false, replay["terminalWalkObserved"])
                sameState(terminal.b, terminalView(reopened, replay, assertIs<String>(terminal.b["caseId"])))
                terminalReport(reopened, replay, terminal.b)
                terminalRead(reopened, program, terminal.b)
                val changed = post(reopened, changedCase(terminal.a, mapOf("idempotencyKey" to "changed-abort")))
                assertEquals(409, changed.status, changed.body)
                assertEquals(frames, caseFrames(reopened, assertIs<String>(terminal.b["caseId"])))
                noEffects(reopened)
                assertEquals(1, probe.calls.size, "Only recovered interrupted delivery may walk after this reopen")
            }
            for (initial in initializers) {
                sameState(initial.view, metadata(reopened, receipt(reopened, post(reopened, initial.request)), assertIs<String>(initial.view["caseId"])))
                noEffects(reopened)
            }
        } finally { reopened.close() }
    }

    @Test fun preparationAndLostTerminalReportResumeOnlyTheExactZeroProviderDeliveryAcrossReopen() = runBlocking<Unit> {
        val program = "preset-tribunal-case-v2"
        for (fault in s_[RecordFault.BEFORE, RecordFault.AFTER].view) {
            val caseId = "delivery-${fault.name.lowercase()}"
            lateinit var mask: ReadMask
            val rig = fixture(program, dialog = loopDialog(s_[judge()]),
                casView = { ReadMask(it).also { selected -> mask = selected } })
            lateinit var request: Map<String, Any?>
            lateinit var original: Map<String, Any?>
            lateinit var before: List<JobLog.Frame>
            try {
                val probe = terminalProbe(rig)
                val completed = terminalReady(rig, program, "Motion: $caseId", caseId)
                val prior = completed[3]
                request = transition(prior.view, "settle-$caseId", "rule", program)
                before = caseFrames(rig, caseId)
                val calls = rig.calls.toList()
                probe.fault.set(fault)
                val response = post(rig, request)
                assertEquals(1, probe.calls.size,
                    "The controlled failure must arise at the actual admitted record node")
                assertEquals(calls, rig.calls)
                assertEquals(400, response.status, response.body)
                val failed = reifyMap(response.body)
                assertEquals("failed", failed["status"])
                assertEquals(if (fault == RecordFault.BEFORE) "fixture record before delivery"
                    else "fixture terminal report acknowledgement lost", failed["error"])
                assertNotEquals(true, failed["terminalWalkObserved"], "A failed walker must not claim joined terminal output")
                original = terminalView(rig, failed, caseId)
                assertEquals(if (fault == RecordFault.BEFORE) "terminal-pending" else "settled", original["phase"])
                assertEquals(before.size + if (fault == RecordFault.BEFORE) 1 else 2, caseFrames(rig, caseId).size)
                if (fault == RecordFault.BEFORE) {
                    assertNull(probe.calls.single().output)
                    assertIs<JobCommand.State>(CanonicalCbor.decodeJobCommand(caseFrames(rig, caseId).last().payload))
                    val changed = post(rig, changedCase(request, mapOf("idempotencyKey" to "different-prepared-intent")))
                    assertEquals(409, changed.status, changed.body)
                    assertEquals("case_terminal_pending", reifyMap(changed.body)["error"])
                    assertEquals(1, probe.calls.size)
                    val committed = caseFrames(rig, caseId)
                    val evidence = ContentId(assertIs<String>(original["terminalEvidenceCid"]))
                    val bytes = blob(rig, evidence.value)
                    val corrupt = bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
                    for (replacement in s_[null, corrupt].view) {
                        val reads = mask.reads.get()
                        mask.selected.set(evidence j replacement)
                        try {
                            val blocked = post(rig, request)
                            assertEquals(400, blocked.status, blocked.body)
                            assertEquals("failed", reifyMap(blocked.body)["status"])
                            assertTrue(assertIs<String>(reifyMap(blocked.body)["error"]).contains(evidence.value), blocked.body)
                            assertTrue(mask.reads.get() > reads, "Pending delivery must authenticate its actual retained evidence")
                            assertEquals(1, probe.calls.size)
                            assertEquals(committed, caseFrames(rig, caseId))
                        } finally { mask.selected.set(null) }
                    }
                } else {
                    val accepted = assertIs<Map<*, *>>(assertIs<Map<*, *>>(probe.calls.single().output)["report"])
                    assertEquals(original["terminalReceiptCid"], accepted["recorded"])
                    assertIs<JobCommand.Complete>(CanonicalCbor.decodeJobCommand(caseFrames(rig, caseId).last().payload))
                }
                terminalRead(rig, program, original)
                val retained = assertIs<Map<*, *>>(reifyMap(blob(rig, failed["receiptCid"]))["lcncRun"])
                for (key in s_["status", "error", "case"].view) assertEquals(failed[key], retained[key])
            } finally { mask.selected.set(null); rig.close() }
            val reopened = fixture(program)
            try {
                val probe = terminalProbe(reopened)
                terminalRead(reopened, program, original)
                noEffects(reopened)
                val response = post(reopened, request)
                assertEquals(if (fault == RecordFault.BEFORE) 1 else 0, probe.calls.size,
                    "Only an undelivered preparation may walk; a committed terminal report is reconstructed")
                assertIs<JobCommand.Complete>(CanonicalCbor.decodeJobCommand(caseFrames(reopened, caseId).last().payload))
                noEffects(reopened)
                val run = receipt(reopened, response)
                val settled = terminalView(reopened, run, caseId)
                assertEquals("settled", settled["phase"])
                assertEquals(original["stateCid"], settled["stateCid"], "Delivery must not append another preparation")
                assertEquals(original["preparationSnapshotCid"], settled["preparationSnapshotCid"])
                assertEquals(fault == RecordFault.BEFORE, run["terminalWalkObserved"])
                terminalReport(reopened, run, settled)
                assertEquals(before.size + 2, caseFrames(reopened, caseId).size)
                if (fault == RecordFault.AFTER) {
                    sameState(original, settled)
                    val entries = assertIs<List<*>>(reifyMap(blob(reopened, run["invocationsCid"]))["entries"])
                    assertTrue(entries.isEmpty(), "Reconstructed success must not invent a record invocation or cleanup observation")
                    assertTrue(assertIs<Map<*, *>>(run["outputs"]).isEmpty())
                }
                terminalRead(reopened, program, settled)
            } finally { reopened.close() }
        }
    }

    class ReceiptWriteFault(private val actual: CasStore) : CasStore() {
        val armed = java.util.concurrent.atomic.AtomicBoolean()
        val refused = AtomicReference<ByteArray?>()
        override val durability get() = actual.durability
        override fun get(cid: ContentId) = actual.get(cid)
        override fun holds(cid: ContentId) = actual.holds(cid)
        override fun sync() = actual.sync()
        override fun putRef(path: String, bytes: ByteArray, cid: ContentId) = actual.putRef(path, bytes, cid)
        override fun put(bytes: ByteArray): ContentId {
            val command = if (bytes.firstOrNull() == '{'.code.toByte()) runCatching { reifyMap(bytes) }.getOrNull() else null
            val run = command?.get("lcncRun") as? Map<*, *>
            if (command?.get("type") == "complete" && run?.get("status") == "completed" &&
                (run["case"] as? Map<*, *>)?.get("phase") == "settled" && armed.compareAndSet(true, false)) {
                refused.set(bytes.copyOf())
                error("fixture ordinary terminal receipt persistence failed")
            }
            return actual.put(bytes)
        }
    }

    fun failureText(failure: Throwable): String = buildString {
        val seen = mutableSetOf<Throwable>()
        fun appendFailure(value: Throwable) {
            if (!seen.add(value)) return
            append(value.message).append('\n')
            value.cause?.let(::appendFailure)
            value.suppressed.forEach(::appendFailure)
        }
        appendFailure(failure)
    }

    @Test fun terminalAcceptanceSurvivesFailureOfTheSeparateOrdinaryReceiptStore() = runBlocking<Unit> {
        val program = "preset-tribunal-case-v2"
        lateinit var fault: ReceiptWriteFault
        val rig = fixture(program, dialog = loopDialog(s_[judge()]),
            casView = { ReceiptWriteFault(it).also { selected -> fault = selected } })
        lateinit var request: Map<String, Any?>
        lateinit var settled: Map<String, Any?>
        lateinit var committed: List<JobLog.Frame>
        lateinit var uncertainRunId: String
        try {
            val probe = terminalProbe(rig)
            val completed = terminalReady(rig, program, "Motion: distinct case and ordinary receipt stores")
            request = transition(completed[3].view, "rule-with-receipt-failure", "rule", program)
            val calls = rig.calls.toList()
            fault.armed.set(true)
            val outcome = runCatching { withTimeout(15_000) { post(rig, request) } }
            val attempted = assertNotNull(fault.refused.get(),
                "Failure must occur on the real ordinary completion payload after settled metadata exists")
            assertTrue(outcome.isFailure || outcome.getOrThrow().status != 200,
                "A missing ordinary receipt cannot be reported as committed HTTP success")
            assertEquals(1, probe.calls.size)
            assertEquals(calls, rig.calls)
            assertFalse(rig.storage.holds(ContentId.of(attempted)), "The refused ordinary completion bytes were not persisted")
            val command = reifyMap(attempted)
            val attemptedRun = assertIs<Map<*, *>>(command["lcncRun"]).entries.associate { it.key.toString() to it.value }
            uncertainRunId = assertIs<String>(attemptedRun["runId"])
            settled = terminalView(rig, attemptedRun)
            assertEquals("closed", settled["lifecycle"])
            val actualReport = assertIs<Map<*, *>>(assertIs<Map<*, *>>(probe.calls.single().output)["report"])
            assertEquals(settled["terminalReceiptCid"], actualReport["recorded"])
            terminalFrames(rig, completed[3].view, settled, request)
            committed = caseFrames(rig)
            assertIs<JobCommand.Complete>(CanonicalCbor.decodeJobCommand(committed.last().payload))
            val frame = head(rig, settled)
            val key = "lcnc-case/${ContentId(assertIs<String>(frame["requestCid"])).hex}/terminal"
            rig.ctx.councilCases.withCase("matter", create = false) { case ->
                assertEquals(settled["terminalReceiptCid"], assertNotNull(case.receipt(key)).value,
                    "The actual case owner must retain its terminal acknowledgement despite ordinary-store failure")
            }
        } finally {
            fault.armed.set(false)
            if (fault.refused.get() == null) rig.close() else {
                val cleanup = assertNotNull(runCatching { rig.close() }.exceptionOrNull(),
                    "The poisoned ordinary store must surface its real failure during awaited cleanup")
                assertTrue(failureText(cleanup).contains("fixture ordinary terminal receipt persistence failed"), failureText(cleanup))
            }
        }
        val reopened = fixture(program)
        try {
            val probe = terminalProbe(reopened)
            val recovered = assertIs<Map<*, *>>(reopened.ctx.blackboard.get("lcnc/run/$uncertainRunId"))
            assertEquals("interrupted", recovered["status"], "The missing ordinary completion cannot be invented during recovery")
            assertEquals("runtime_restarted", recovered["error"])
            val retainedRecovery = assertIs<Map<*, *>>(reifyMap(blob(reopened, recovered["receiptCid"]))["lcncRun"])
            assertEquals("interrupted", retainedRecovery["status"])
            terminalRead(reopened, program, settled)
            noEffects(reopened)
            val run = receipt(reopened, post(reopened, request))
            sameState(settled, terminalView(reopened, run))
            assertEquals(false, run["terminalWalkObserved"])
            terminalReport(reopened, run, settled)
            assertTrue(probe.calls.isEmpty())
            noEffects(reopened)
            assertEquals(committed, caseFrames(reopened))
        } finally { reopened.close() }
    }

    @Test fun boundedCounselAndClarificationKeepTwentyFiveHeadsAndHistoricalReceipts() = runBlocking<Unit> {
        for (program in loopPrograms.view) {
            val question = "Explain the source {and its quoted \"limit\"}"
            val rig = fixture(program, dialog = loopDialog(s_[judge("true", question), judge(false)]))
            val completed = mutableListOf<Completion>()
            val brief = "Motion: retain every actual counsel round"
            lateinit var last: Completion
            try {
                val initial = loopInitialize(rig, program, brief).also(completed::add)
                var a = loopNext(rig, initial, program, "A0", "argue", "n2",
                    if (program == "preset-legal-tribunal-case-v2") grounded(brief) else brief, 0L, 0L).also(completed::add)
                var b = loopNext(rig, a, program, "B0", "rebut", "n3",
                    transcript(brief, s_["argue" j a.outputs]), 0L, 0L).also(completed::add)
                val firstA = a
                val firstB = b
                repeat(3) { index ->
                    val prior = b
                    val next = loopNext(rig, prior, program, "A${index + 1}", "argue", "case.argue.repeat.seat",
                        counselRecord(brief, a, b), index + 1L, 0L).also(completed::add)
                    // No v2-only assertion precedes this actual repeat-seat attempt on the first-slice baseline.
                    loopRound(rig, firstA, initial, firstA, emptySeriesOf())
                    loopRound(rig, firstB, initial, firstA, s_[firstA])
                    loopRound(rig, next, initial, next, emptySeriesOf())
                    assertNotEquals(a.view["admissionCid"], next.view["admissionCid"])
                    loopRefused(rig, next, program, "old-B-${index + 1}", "deliberate", "case_join_requires_counsel")
                    a = next
                    b = loopNext(rig, a, program, "B${index + 1}", "rebut", "n3",
                        transcript(brief, s_["argue" j a.outputs]), index + 1L, 0L).also(completed::add)
                    loopRound(rig, b, initial, a, s_[a])
                }
                loopRefused(rig, b, program, "fourth-counsel", "argue", "rebut-argue")
                val j = loopNext(rig, b, program, "J0", "deliberate", "n4", counselRecord(brief, a, b), 3L, 0L)
                    .also(completed::add)
                loopRound(rig, j, initial, a, s_[a, b])
                judgeProjection(rig, j)
                assertEquals(true, io(rig, j.view)["clarification"])
                unresolvedRuleRefused(rig, program, j, "cannot-rule-before-clarification")
                val ac = loopNext(rig, j, program, "Ac", "argue", "case.argue.repeat.seat",
                    legacyFold(rig, program, "cl.fold.argue", initialRecord(rig, program, a, b, j), question), 3L, 1L).also(completed::add)
                loopRound(rig, ac, initial, ac, emptySeriesOf(), j)
                loopRefused(rig, ac, program, "preclarification-B", "deliberate", "case_join_requires_counsel")
                val bc = loopNext(rig, ac, program, "Bc", "rebut", "n3",
                    legacyFold(rig, program, "cl.fold.rebut", ac.outputs["prompt"], ac.outputs["content"]), 3L, 1L)
                    .also(completed::add)
                loopRound(rig, bc, initial, ac, s_[ac], j)
                loopRefused(rig, bc, program, "fourth-after-clarification", "argue", "rebut-argue")
                last = loopNext(rig, bc, program, "Jc", "deliberate", "n4",
                    legacyFold(rig, program, "cl.fold.deliberate", bc.outputs["prompt"], bc.outputs["content"]), 3L, 1L)
                    .also(completed::add)
                loopRound(rig, last, initial, ac, s_[ac, bc], j)
                judgeProjection(rig, last)
                assertEquals(false, io(rig, last.view)["clarification"])
                loopRefused(rig, last, program, "no-new-question", "argue", "no permitted transition")
                assertEquals(12, rig.calls.size)
                assertEquals(27, caseFrames(rig).size, "25 state heads plus the real Submit/Start frames")
                headHistory(rig, last, 25)
                historical(rig, completed.toSeries(), last, program)
            } finally { rig.close() }
            val reopened = fixture(program)
            lateinit var settled: Completion
            try {
                val read = mapOf("program" to program, "case" to mapOf("intent" to "read", "caseId" to "matter"))
                sameState(last.view, metadata(reopened, receipt(reopened, post(reopened, read))))
                noEffects(reopened)
                headHistory(reopened, last, 25)
                historical(reopened, completed.toSeries(), last, program)
                noEffects(reopened)
                assertEquals(27, caseFrames(reopened).size)
                val probe = terminalProbe(reopened)
                val request = transition(last.view, "rule-after-maximum-history", "rule", program)
                val response = post(reopened, request)
                assertEquals(1, probe.calls.size, "The maximum retained history must still reach actual terminal delivery")
                assertIs<JobCommand.Complete>(CanonicalCbor.decodeJobCommand(caseFrames(reopened).last().payload))
                noEffects(reopened)
                val run = receipt(reopened, response)
                val view = terminalView(reopened, run)
                terminalReport(reopened, run, view)
                terminalFrames(reopened, last.view, view, request)
                settled = Completion(request, run, view, emptyMap())
                assertEquals(29, caseFrames(reopened).size, "Submit/Start, 26 State heads, then actual Complete")
                headHistory(reopened, settled, 26)
                terminalRead(reopened, program, view)
            } finally { reopened.close() }
            val terminalReopen = fixture(program)
            try {
                terminalRead(terminalReopen, program, settled.view)
                headHistory(terminalReopen, settled, 26)
                val frames = caseFrames(terminalReopen)
                val replay = receipt(terminalReopen, post(terminalReopen, settled.request))
                assertEquals(false, replay["terminalWalkObserved"])
                sameState(settled.view, terminalView(terminalReopen, replay))
                terminalReport(terminalReopen, replay, settled.view)
                noEffects(terminalReopen)
                assertEquals(frames, caseFrames(terminalReopen))
            } finally { terminalReopen.close() }
        }
    }

    @Test fun counselLoopInsideClarificationKeepsItsOriginalJudgeAndQuestion() = runBlocking<Unit> {
        for (program in loopPrograms.view) {
            val question = "Identify the retained answer {with \"quotes\"}"
            val rig = fixture(program, dialog = loopDialog(s_[judge(true, question), judge(true, "Still unresolved")]))
            val brief = "Motion: keep clarification and counsel bounds independent"
            try {
                val initial = loopInitialize(rig, program, brief)
                val a = loopNext(rig, initial, program, "A0", "argue", "n2",
                    if (program == "preset-legal-tribunal-case-v2") grounded(brief) else brief, 0L, 0L)
                val b = loopNext(rig, a, program, "B0", "rebut", "n3", transcript(brief, s_["argue" j a.outputs]), 0L, 0L)
                val j = loopNext(rig, b, program, "J0", "deliberate", "n4", counselRecord(brief, a, b), 0L, 0L)
                judgeProjection(rig, j)
                unresolvedRuleRefused(rig, program, j, "cannot-rule-with-question")
                val ac = loopNext(rig, j, program, "Ac", "argue", "case.argue.repeat.seat",
                    legacyFold(rig, program, "cl.fold.argue", initialRecord(rig, program, a, b, j), question), 0L, 1L)
                val bc = loopNext(rig, ac, program, "Bc", "rebut", "n3",
                    legacyFold(rig, program, "cl.fold.rebut", ac.outputs["prompt"], ac.outputs["content"]), 0L, 1L)
                val repeated = loopNext(rig, bc, program, "Ac-loop", "argue", "case.argue.repeat.seat",
                    legacyFold(rig, program, "cl.fold.argue", counselRecord(brief, ac, bc), question), 1L, 1L)
                loopRound(rig, repeated, initial, repeated, emptySeriesOf(), j)
                loopRefused(rig, repeated, program, "old-clarification-B", "deliberate", "case_join_requires_counsel")
                val rebut = loopNext(rig, repeated, program, "Bc-loop", "rebut", "n3",
                    legacyFold(rig, program, "cl.fold.rebut", repeated.outputs["prompt"], repeated.outputs["content"]), 1L, 1L)
                val unresolved = loopNext(rig, rebut, program, "Jc", "deliberate", "n4",
                    legacyFold(rig, program, "cl.fold.deliberate", rebut.outputs["prompt"], rebut.outputs["content"]), 1L, 1L)
                loopRound(rig, unresolved, initial, repeated, s_[repeated, rebut], j)
                judgeProjection(rig, unresolved)
                assertEquals(true, io(rig, unresolved.view)["clarification"])
                assertEquals("Still unresolved", assertIs<Map<*, *>>(io(rig, unresolved.view)["lcnc.case.ruling"])["clarificationQuestion"])
                loopRefused(rig, unresolved, program, "second-clarification", "argue", "deliberate-clarify")
                unresolvedRuleRefused(rig, program, unresolved, "cannot-rule-after-exhaustion")
                assertEquals("active", unresolved.view["lifecycle"])
                assertTrue(rig.records.isEmpty(), "An unresolved exhausted question cannot become a terminal verdict")
                assertEquals(8, rig.calls.size)
                assertEquals(19, caseFrames(rig).size)
            } finally { rig.close() }
        }
    }

    @Test fun freshJudgeParserAndMistrialControlClarificationAdmission() = runBlocking<Unit> {
        for (program in loopPrograms.view) {
            val rig = fixture(program, dialog = loopDialog(s_[judge(false), "Plain unparsed ruling", judge(true, "Do not follow", true)]))
            val brief = "Motion: derive authorization only from the actual current judge"
            try {
                for ((index, name) in s_["false", "unparsed", "mistrial"].view.withIndex()) {
                    val caseId = "predicate-$name"
                    val initial = loopInitialize(rig, program, brief, caseId)
                    val a = loopNext(rig, initial, program, "A0-$name", "argue", "n2",
                        if (program == "preset-legal-tribunal-case-v2") grounded(brief) else brief, 0L, 0L, caseId)
                    val b = loopNext(rig, a, program, "B0-$name", "rebut", "n3",
                        transcript(brief, s_["argue" j a.outputs]), 0L, 0L, caseId)
                    val j = loopNext(rig, b, program, "J0-$name", "deliberate", "n4", counselRecord(brief, a, b), 0L, 0L, caseId)
                    loopRound(rig, j, initial, a, s_[a, b])
                    judgeProjection(rig, j)
                    assertEquals(false, io(rig, j.view)["clarification"])
                    assertEquals(index == 2, assertIs<Map<*, *>>(io(rig, j.view)["lcnc.case.ruling"])["mistrial"])
                    loopRefused(rig, j, program, "no-question-$name", "argue", "no permitted transition", caseId)
                    assertEquals(0L, counter(rig, j.view, "loop.iterations.deliberate-clarify"))
                    assertEquals("active", j.view["lifecycle"])
                    assertTrue(rig.records.isEmpty())
                    assertEquals(9, caseFrames(rig, caseId).size)
                }
            } finally { rig.close() }
        }
    }

    @Test fun failedLoopConsumesItsBoundAcrossDurableReopenWithoutReplay() = runBlocking<Unit> {
        for (program in loopPrograms.view) {
            val brief = "Motion: an admitted failed loop still consumes its traversal"
            val rig = fixture(program, IllegalStateException("fixture repeat counsel failed"), "case.argue.repeat.seat",
                dialog = loopDialog(emptySeriesOf()))
            lateinit var initial: Completion
            lateinit var a: Completion
            lateinit var b: Completion
            lateinit var admitted: Map<String, Any?>
            lateinit var request: Map<String, Any?>
            lateinit var committed: List<JobLog.Frame>
            try {
                initial = loopInitialize(rig, program, brief)
                a = loopNext(rig, initial, program, "A0", "argue", "n2",
                    if (program == "preset-legal-tribunal-case-v2") grounded(brief) else brief, 0L, 0L)
                b = loopNext(rig, a, program, "B0", "rebut", "n3", transcript(brief, s_["argue" j a.outputs]), 0L, 0L)
                request = transition(b.view, "A1-failed", "argue", program)
                val response = post(rig, request)
                assertEquals(listOf("n2", "n3", "case.argue.repeat.seat"), rig.calls)
                assertEquals(400, response.status, response.body)
                val run = reifyMap(response.body)
                assertEquals("fixture repeat counsel failed", run["error"])
                admitted = metadata(rig, run)
                assertEquals("argue", admitted["lane"])
                assertEquals("interrupted", admitted["phase"])
                assertEquals(1L, counter(rig, admitted, "loop.iterations.rebut-argue"))
                assertEquals(0L, counter(rig, admitted, "loop.iterations.deliberate-clarify"))
                assertEquals(counselRecord(brief, a, b), rig.seats.last().prompt)
                assertEquals(assertNotNull(authored(preset(program.removeSuffix("-case-v2")).nodes, "n2")).params,
                    rig.seats.last().node.params)
                val actual = io(rig, admitted)
                val round = assertIs<Map<*, *>>(actual["lcnc.case.round"])
                assertEquals(2L, round["version"])
                assertEquals(initial.view["stateCid"], round["initialStateCid"])
                assertEquals(emptyList<Any?>(), round["sources"])
                assertTrue(round.containsKey("argueAdmissionCid"))
                assertNull(round["argueAdmissionCid"])
                assertTrue(round.containsKey("clarificationSource"))
                assertNull(round["clarificationSource"])
                assertEquals(false, actual["clarification"])
                assertFalse(actual.containsKey("lcnc.case.ruling"))
                assertNull(head(rig, admitted)["evidenceCid"])
                committed = caseFrames(rig)
                assertEquals(8, committed.size)
            } finally { rig.close() }
            val reopened = fixture(program)
            try {
                val read = mapOf("program" to program, "case" to mapOf("intent" to "read", "caseId" to "matter"))
                sameState(admitted, metadata(reopened, receipt(reopened, post(reopened, read))))
                noEffects(reopened)
                val response = post(reopened, request)
                noEffects(reopened)
                assertEquals(409, response.status, response.body)
                assertEquals("case_interrupted", reifyMap(response.body)["error"])
                sameState(admitted, metadata(reopened, reifyMap(response.body)))
                assertEquals(1L, counter(reopened, admitted, "loop.iterations.rebut-argue"))
                assertEquals(committed, caseFrames(reopened))
                for (accepted in s_[initial, a, b].view) {
                    val replay = receipt(reopened, post(reopened, accepted.request))
                    noEffects(reopened)
                    sameState(accepted.view, metadata(reopened, replay))
                    sameState(admitted, metadata(reopened, receipt(reopened, post(reopened, read))))
                    assertEquals(committed, caseFrames(reopened))
                }
            } finally { reopened.close() }
        }
    }

    fun stage(rig: Rig, run: Map<String, Any?>, id: String, prompt: String): Map<String, Any?> {
        val actual = rig.seats.last()
        val original = assertNotNull(authored(preset(run["program"].toString().removeSuffix("-case")).nodes, id))
        assertEquals(id, actual.node.id)
        assertEquals(original.params, actual.node.params)
        assertEquals(original.params["system"], actual.system)
        assertEquals(prompt, actual.prompt)
        val options = BrainChatOptions.parse(original.params)
        assertEquals(options.maxTokens, actual.options.maxTokens)
        assertEquals(options.temperature, actual.options.temperature)
        assertEquals(options.models.view.map { it.pair }, actual.options.models.view.map { it.pair })
        assertEquals(run["workgroup"], actual.workgroup)
        assertEquals((run["claimId"] as Number).toLong(), actual.claim)
        assertEquals(run["programCid"], actual.programCid)
        assertTrue(assertIs<List<*>>(actual.invocation["path"]).isNotEmpty())
        val entries = assertIs<List<*>>(reifyMap(blob(rig, run["invocationsCid"]))["entries"])
            .map { assertIs<Map<*, *>>(it) }
        assertEquals(listOf(id), entries.filter { it["type"] == "mux.chat" }.map { it["nodeId"] })
        assertEquals("completed", entries.single { it["nodeId"] == id }["status"])
        assertTrue(entries.none { it["type"] == "tribunal.record" })
        if (id != "n2") assertTrue(entries.none { it["type"] in setOf("legal.ingest", "legal.evidence") })
        assertTrue(rig.records.isEmpty())
        return metadata(rig, run).also {
            assertEquals("active", it["lifecycle"])
            assertEquals("completed", it["phase"])
        }
    }

    fun result(rig: Rig, view: Map<String, Any?>): Map<String, Any?> {
        val state = CanonicalCbor.decodeMap(blob(rig, view["stateCid"]))
        val result = CanonicalCbor.decodeMap(blob(rig, state["evidenceCid"]))
        assertEquals("lcnc-case-result", result["kind"])
        assertEquals(1L, result["version"])
        for (key in listOf("caseId", "programCid", "requestCid", "admissionCid", "run"))
            assertEquals(state[key], result[key], "The accepted result must belong to the actual completed head's $key")
        return assertIs<Map<*, *>>(assertIs<Map<*, *>>(result["output"])["returns"])
            .entries.associate { it.key.toString() to it.value }
    }

    fun transcript(brief: String, sources: Series<Join<String, Map<String, Any?>>>): String = buildString {
        append("Original brief:\n").append(brief)
        for (source in sources.view) {
            append("\n\n").append(source.a).append(" prompt:\n").append(source.b["prompt"])
            append("\n\n").append(source.a).append(" response:\n").append(source.b["content"])
            append("\n\n").append(source.a).append(" model:\n").append(source.b["model"])
        }
    }

    fun round(rig: Rig, view: Map<String, Any?>, initial: Map<String, Any?>,
              sources: Series<Join<String, Map<String, Any?>>>) {
        val expected = mapOf("kind" to "lcnc-case-round", "version" to 1L, "initialStateCid" to initial["stateCid"],
            "sources" to (sources α { mapOf("lane" to it.a, "stateCid" to it.b["stateCid"], "snapshotCid" to it.b["snapshotCid"]) }).view.toList())
        for (cid in listOfNotNull(view["admissionCid"], view["stateCid"]).distinct()) {
            val state = CanonicalCbor.decodeMap(blob(rig, cid))
            assertEquals(expected, assertIs<Map<*, *>>(state["io"])["lcnc.case.round"],
                "Admission and completion must retain the exact accepted counsel references for this round")
        }
        assertEquals(sources.size, (sources α { it.b["stateCid"] }).view.toSet().size)
        for (source in sources.view) {
            val snapshot = CanonicalCbor.decodeJobSnapshot(blob(rig, source.b["snapshotCid"]))
            assertEquals(JobId.of("case/${initial["caseId"]}"), snapshot.jobId)
            assertEquals(source.b["stateCid"], snapshot.stateCid)
            val state = CanonicalCbor.decodeMap(blob(rig, snapshot.stateCid))
            assertEquals(initial["caseId"], state["caseId"])
            assertEquals(initial["programCid"], state["programCid"])
            assertEquals(source.a, state["lane"])
            assertEquals("completed", state["phase"])
            result(rig, source.b)
        }
    }

    @Test fun acceptedCounselRoundFeedsOriginalRebutAndJoinedJudge() = runBlocking<Unit> {
        for (program in programs.view) {
            val rig = fixture(program)
            val brief = "Motion: the accepted source supports granting relief"
            try {
                val opened = post(rig, initialize(program = program) + ("inputs" to mapOf(
                    "brief" to brief, "record" to "Initializer record must not supply a later seat")))
                noEffects(rig)
                val initial = metadata(rig, receipt(rig, opened))
                val argueRun = receipt(rig, post(rig, transition(initial, program = program)))
                argueOnly(rig, argueRun, brief)
                val argue = metadata(rig, argueRun)
                val argument = result(rig, argue)
                val rebutResponse = post(rig, transition(argue, "rebut", "rebut", program))
                assertEquals(listOf("n2", "n3"), rig.calls,
                    "A completed argue head must admit actual original n3; an unsupported response is the first-slice behavioral gap")
                val rebutRun = receipt(rig, rebutResponse)
                val rebut = stage(rig, rebutRun, "n3", transcript(brief, s_["argue" j argument]))
                assertEquals("rebut", rebut["lane"])
                assertEquals((argue["revision"] as Number).toLong() + 2L, (rebut["revision"] as Number).toLong())
                round(rig, rebut, initial, s_["argue" j argue])
                val rebuttal = result(rig, rebut)
                val judgeResponse = post(rig, transition(rebut, "judge", "deliberate", program))
                assertEquals(listOf("n2", "n3", "n4"), rig.calls)
                val judgeRun = receipt(rig, judgeResponse)
                val judge = stage(rig, judgeRun, "n4", transcript(brief, s_["argue" j argument, "rebut" j rebuttal]))
                assertEquals("deliberate", judge["lane"])
                assertEquals((rebut["revision"] as Number).toLong() + 2L, (judge["revision"] as Number).toLong())
                round(rig, judge, initial, s_["argue" j argue, "rebut" j rebut])
                val verdict = result(rig, judge)
                assertEquals("fixture/n4", verdict["model"])
                assertTrue(assertIs<String>(verdict["content"]).contains("needsClarification"))
                assertEquals(if (program == "preset-legal-tribunal-case") listOf("n1", "n1b") else emptyList(), rig.legalCalls)
                assertTrue(rig.records.isEmpty(), "Judge completion is not terminal settlement or an automatic clarification walk")
                assertEquals(9, caseFrames(rig).size)
            } finally { rig.close() }
        }
    }

    @Test fun joinRequiresAcceptedRebutAndHistoricalRetriesKeepTheCurrentJudgeHead() = runBlocking<Unit> {
        for (program in programs.view) {
            val rig = fixture(program)
            val brief = "Motion: retain both sides of this actual round"
            try {
                val initial = metadata(rig, receipt(rig, post(rig, initialize(brief = brief, program = program))))
                val argueRequest = transition(initial, program = program)
                val argueRun = receipt(rig, post(rig, argueRequest))
                argueOnly(rig, argueRun, brief)
                val argue = metadata(rig, argueRun)
                val argument = result(rig, argue)
                val before = caseFrames(rig)
                val early = post(rig, transition(argue, "premature-judge", "deliberate", program))
                assertEquals(listOf("n2"), rig.calls)
                assertTrue(rig.records.isEmpty())
                assertEquals(409, early.status, early.body)
                assertEquals("case_join_requires_counsel", reifyMap(early.body)["error"])
                assertEquals(before, caseFrames(rig), "A missing accepted rebut cannot admit judge State")
                val rebutRequest = transition(argue, "rebut", "rebut", program)
                val rebutResponse = post(rig, rebutRequest)
                assertEquals(listOf("n2", "n3"), rig.calls)
                val rebutRun = receipt(rig, rebutResponse)
                val rebut = stage(rig, rebutRun, "n3", transcript(brief, s_["argue" j argument]))
                val rebuttal = result(rig, rebut)
                val judgeRequest = transition(rebut, "judge", "deliberate", program)
                val judgeResponse = post(rig, judgeRequest)
                assertEquals(listOf("n2", "n3", "n4"), rig.calls)
                val judgeRun = receipt(rig, judgeResponse)
                val judge = stage(rig, judgeRun, "n4", transcript(brief, s_["argue" j argument, "rebut" j rebuttal]))
                round(rig, judge, initial, s_["argue" j argue, "rebut" j rebut])
                val committed = caseFrames(rig)
                for (accepted in listOf(argueRequest j (argue j argument), rebutRequest j (rebut j rebuttal),
                    judgeRequest j (judge j result(rig, judge)))) {
                    val replay = receipt(rig, post(rig, accepted.a))
                    val historical = metadata(rig, replay)
                    sameState(accepted.b.a, historical)
                    assertEquals(accepted.b.b, result(rig, historical))
                    assertEquals(accepted.b.b, replay["returns"])
                    assertEquals(listOf("n2", "n3", "n4"), rig.calls)
                    assertTrue(rig.records.isEmpty())
                    assertEquals(committed, caseFrames(rig))
                    val read = mapOf("program" to program, "case" to mapOf("intent" to "read", "caseId" to "matter"))
                    sameState(judge, metadata(rig, receipt(rig, post(rig, read))))
                }
                for (changed in listOf(changedCase(argueRequest, mapOf("requestedTarget" to "rebut")),
                    changedCase(rebutRequest, mapOf("requestedTarget" to "deliberate")))) {
                    val refused = post(rig, changed)
                    assertEquals(listOf("n2", "n3", "n4"), rig.calls)
                    assertEquals(409, refused.status, refused.body)
                    assertEquals(committed, caseFrames(rig))
                }
                assertEquals(if (program == "preset-legal-tribunal-case") listOf("n1", "n1b") else emptyList(), rig.legalCalls)
                assertTrue(rig.records.isEmpty())
                assertEquals(9, committed.size)
            } finally { rig.close() }
        }
    }

    @Test fun failedRebutIsNotASecondAcceptedJoinSourceAcrossReopen() = runBlocking<Unit> {
        for (program in programs.view) {
            var initial: Map<String, Any?>
            var argue: Map<String, Any?>
            var admitted: Map<String, Any?>
            var rebutRequest: Map<String, Any?>
            var committed: List<JobLog.Frame>
            val rig = fixture(program, IllegalStateException("fixture n3 failed after admission"), "n3")
            try {
                val opened = post(rig, initialize(program = program))
                noEffects(rig)
                initial = metadata(rig, receipt(rig, opened))
                val argueRun = receipt(rig, post(rig, transition(initial, program = program)))
                argueOnly(rig, argueRun, "Motion: admit the source")
                argue = metadata(rig, argueRun)
                rebutRequest = transition(argue, "rebut", "rebut", program)
                val failed = post(rig, rebutRequest)
                assertEquals(listOf("n2", "n3"), rig.calls)
                assertEquals(400, failed.status, failed.body)
                val run = reifyMap(failed.body)
                assertEquals("failed", run["status"])
                assertEquals("fixture n3 failed after admission", run["error"])
                val attempted = rig.seats.last()
                assertEquals("n3", attempted.node.id)
                assertEquals(assertNotNull(authored(preset(program.removeSuffix("-case")).nodes, "n3")).params, attempted.node.params)
                assertEquals(transcript("Motion: admit the source", s_["argue" j result(rig, argue)]), attempted.prompt)
                admitted = metadata(rig, run)
                assertEquals("rebut", admitted["lane"])
                assertEquals("interrupted", admitted["phase"])
                assertEquals((argue["revision"] as Number).toLong() + 1L, (admitted["revision"] as Number).toLong())
                val head = CanonicalCbor.decodeMap(blob(rig, admitted["stateCid"]))
                assertNull(head["evidenceCid"], "An attempted rebuttal cannot become accepted source evidence")
                round(rig, admitted, initial, s_["argue" j argue])
                committed = caseFrames(rig)
                assertEquals(6, committed.size)
                val read = mapOf("program" to program, "case" to mapOf("intent" to "read", "caseId" to "matter"))
                sameState(admitted, metadata(rig, receipt(rig, post(rig, read))))
                for (request in listOf(transition(admitted, "judge", "deliberate", program), rebutRequest)) {
                    val refused = post(rig, request)
                    assertEquals(listOf("n2", "n3"), rig.calls)
                    assertTrue(rig.records.isEmpty())
                    assertEquals(409, refused.status, refused.body)
                    assertEquals("case_interrupted", reifyMap(refused.body)["error"])
                    sameState(admitted, metadata(rig, reifyMap(refused.body)))
                    assertEquals(committed, caseFrames(rig))
                }
                assertEquals(if (program == "preset-legal-tribunal-case") listOf("n1", "n1b") else emptyList(), rig.legalCalls)
            } finally { rig.close() }
            val reopened = fixture(program)
            try {
                val read = mapOf("program" to program, "case" to mapOf("intent" to "read", "caseId" to "matter"))
                val recovered = metadata(reopened, receipt(reopened, post(reopened, read)))
                sameState(admitted, recovered)
                noEffects(reopened)
                round(reopened, recovered, initial, s_["argue" j argue])
                for (request in listOf(transition(recovered, "judge-after-reopen", "deliberate", program), rebutRequest)) {
                    val refused = post(reopened, request)
                    noEffects(reopened)
                    assertEquals(409, refused.status, refused.body)
                    assertEquals("case_interrupted", reifyMap(refused.body)["error"])
                    sameState(admitted, metadata(reopened, reifyMap(refused.body)))
                    assertEquals(committed, caseFrames(reopened), "A failed second branch cannot satisfy JOIN after recovery")
                }
            } finally { reopened.close() }
        }
    }

    class ReadMask(private val actual: CasStore) : CasStore() {
        val selected = AtomicReference<Join<ContentId, ByteArray?>?>()
        val reads = AtomicInteger()
        override val durability get() = actual.durability
        override fun put(bytes: ByteArray) = actual.put(bytes)
        override fun putRef(path: String, bytes: ByteArray, cid: ContentId) = actual.putRef(path, bytes, cid)
        override fun sync() = actual.sync()
        override fun holds(cid: ContentId) = actual.holds(cid)
        override fun get(cid: ContentId): ByteArray? {
            val fault = selected.get()
            if (fault == null || cid != fault.a) return actual.get(cid)
            reads.incrementAndGet()
            return fault.b?.copyOf()
        }
    }

    @Test fun judgeLoadsTheActualAcceptedCounselSnapshotBeforeAdmission() = runBlocking<Unit> {
        lateinit var view: ReadMask
        val rig = fixture(casView = { ReadMask(it).also { actual -> view = actual } })
        val brief = "Motion: authenticate the accepted counsel before judge"
        try {
            assertSame(view, rig.cas)
            assertSame(view, rig.ctx.casStore)
            val opened = post(rig, initialize(brief = brief))
            noEffects(rig)
            val initial = metadata(rig, receipt(rig, opened))
            val argueRun = receipt(rig, post(rig, transition(initial)))
            argueOnly(rig, argueRun, brief)
            val argue = metadata(rig, argueRun)
            val argument = result(rig, argue)
            val rebutResponse = post(rig, transition(argue, "rebut", "rebut"))
            assertEquals(listOf("n2", "n3"), rig.calls)
            val rebut = stage(rig, receipt(rig, rebutResponse), "n3", transcript(brief, s_["argue" j argument]))
            val rebuttal = result(rig, rebut)
            val judgeRequest = transition(rebut, "judge", "deliberate")
            val source = ContentId(assertIs<String>(argue["snapshotCid"]))
            val sourceBytes = blob(rig, source.value)
            val altered = sourceBytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            assertNotEquals(source, ContentId.of(altered))
            val committed = caseFrames(rig)
            assertEquals(7, committed.size)
            for (replacement in s_[null, altered].view) {
                val before = view.reads.get()
                view.selected.set(source j replacement)
                try {
                    val response = post(rig, judgeRequest)
                    assertEquals(listOf("n2", "n3"), rig.calls, "A named but unreadable accepted source cannot enter judge")
                    assertTrue(rig.records.isEmpty())
                    assertEquals(400, response.status, response.body)
                    val failed = reifyMap(response.body)
                    assertEquals("failed", failed["status"])
                    assertEquals("execution", failed["phase"])
                    assertTrue(assertIs<String>(failed["error"]).contains(source.value), response.body)
                    assertEquals("unavailable", assertIs<Map<*, *>>(failed["case"])["phase"])
                    assertTrue(view.reads.get() > before, "The actual controller must read the masked accepted snapshot")
                    val retained = assertIs<Map<*, *>>(reifyMap(blob(rig, failed["receiptCid"]))["lcncRun"])
                    for (key in listOf("runId", "status", "phase", "error", "case")) assertEquals(failed[key], retained[key])
                    assertEquals(committed, caseFrames(rig), "Source authentication must precede judge admission State")
                } finally { view.selected.set(null) }
                val read = mapOf("program" to "preset-tribunal-case", "case" to mapOf("intent" to "read", "caseId" to "matter"))
                sameState(rebut, metadata(rig, receipt(rig, post(rig, read))))
                assertEquals(listOf("n2", "n3"), rig.calls)
                assertEquals(committed, caseFrames(rig))
            }
            val judgeResponse = post(rig, judgeRequest)
            assertEquals(listOf("n2", "n3", "n4"), rig.calls)
            val judge = stage(rig, receipt(rig, judgeResponse), "n4", transcript(brief, s_["argue" j argument, "rebut" j rebuttal]))
            round(rig, judge, initial, s_["argue" j argue, "rebut" j rebut])
            assertEquals("deliberate", judge["lane"])
            assertEquals(9, caseFrames(rig).size)
            assertTrue(rig.records.isEmpty())
        } finally { view.selected.set(null); rig.close() }
    }

    @Test fun failedArgueRetainsInterruptedHeadAcrossReopenAndRefusesExactRetry() = runBlocking<Unit> {
        var admitted: Map<String, Any?>
        var request: Map<String, Any?>
        var committed: List<JobLog.Frame>
        val failureText = "fixture n2 failed after admission"
        val first = fixture(seatFailure = IllegalStateException(failureText))
        try {
            val opened = post(first, initialize())
            noEffects(first)
            val initial = metadata(first, receipt(first, opened))
            request = transition(initial)
            val response = post(first, request)
            assertEquals(listOf("n2"), first.calls, "The actual queued provider attempt must precede the controlled failure")
            assertTrue(first.legalCalls.isEmpty())
            assertTrue(first.records.isEmpty())
            assertEquals(400, response.status, response.body)
            val failed = reifyMap(response.body)
            assertEquals("failed", failed["status"])
            assertEquals(false, failed["ok"])
            assertEquals("execution", failed["phase"])
            assertEquals(failureText, failed["error"])
            val retained = assertIs<Map<*, *>>(reifyMap(blob(first, failed["receiptCid"]))["lcncRun"])
            for (key in listOf("runId", "status", "ok", "claimId", "workgroup", "programCid", "case", "invocationsCid"))
                assertEquals(failed[key], retained[key], "The ordinary failure receipt must retain $key")
            val seat = first.seats.single()
            assertEquals("n2", seat.node.id)
            assertEquals(assertNotNull(authored(preset("preset-tribunal").nodes, "n2")).params, seat.node.params)
            assertEquals("Motion: admit the source", seat.prompt)
            assertEquals(failed["workgroup"], seat.workgroup)
            assertEquals((failed["claimId"] as Number).toLong(), seat.claim)
            assertEquals(failed["programCid"], seat.programCid)
            assertTrue(assertIs<List<*>>(seat.invocation["path"]).isNotEmpty())
            admitted = metadata(first, failed)
            assertEquals("active", admitted["lifecycle"])
            assertEquals("argue", admitted["lane"])
            assertEquals("interrupted", admitted["phase"])
            assertEquals((initial["revision"] as Number).toLong() + 1L, (admitted["revision"] as Number).toLong())
            assertNotEquals(initial["stateCid"], admitted["stateCid"])
            val head = CanonicalCbor.decodeMap(blob(first, admitted["stateCid"]))
            assertEquals("admitted", head["phase"])
            assertNull(head["evidenceCid"], "A failed provider cannot create completed seat evidence")
            assertEquals(initial["stateCid"], head["priorStateCid"])
            val owner = assertIs<Map<*, *>>(head["run"])
            assertEquals(failed["runId"], owner["runId"])
            assertEquals(seat.claim, owner["claimId"])
            val observations = reifyMap(blob(first, failed["invocationsCid"]))
            val entries = assertIs<List<*>>(observations["entries"]).map { assertIs<Map<*, *>>(it) }
            val invocation = entries.single { it["type"] == "mux.chat" }
            assertEquals("n2", invocation["nodeId"])
            assertEquals("failed", invocation["status"])
            assertEquals(failureText, invocation["reason"])
            assertTrue(entries.none { it["type"] == "tribunal.record" })
            committed = caseFrames(first)
            assertEquals(listOf("submit", "start", "state", "state"), committed.map {
                CanonicalCbor.decodeJobCommand(it.payload).operationName
            })
            val read = mapOf("program" to "preset-tribunal-case", "case" to mapOf("intent" to "read", "caseId" to "matter"))
            sameState(admitted, metadata(first, receipt(first, post(first, read))))
            assertEquals(listOf("n2"), first.calls)
            assertTrue(first.records.isEmpty())
            assertEquals(committed, caseFrames(first))
        } finally { first.close() }
        val reopened = fixture()
        try {
            val read = mapOf("program" to "preset-tribunal-case", "case" to mapOf("intent" to "read", "caseId" to "matter"))
            sameState(admitted, metadata(reopened, receipt(reopened, post(reopened, read))))
            noEffects(reopened)
            assertEquals(committed, caseFrames(reopened))
            val response = post(reopened, request)
            noEffects(reopened)
            assertEquals(409, response.status, response.body)
            val refused = reifyMap(response.body)
            assertEquals("refused", refused["status"])
            assertEquals("case_interrupted", refused["error"])
            sameState(admitted, metadata(reopened, refused))
            assertEquals(committed, caseFrames(reopened), "An exact interrupted retry cannot write State or replay the provider")
            sameState(admitted, metadata(reopened, receipt(reopened, post(reopened, read))))
            noEffects(reopened)
            assertEquals(committed, caseFrames(reopened))
        } finally { reopened.close() }
    }

    @Test fun explicitCaseIntentCannotExecuteAnUnguardedLegacyPreset() = runBlocking<Unit> {
        val rig = fixture()
        try {
            val response = post(rig, initialize() + ("program" to "preset-tribunal"))
            noEffects(rig)
            assertTrue(response.status in 400..499, response.body)
            assertTrue(caseFrames(rig).isEmpty(), "A flat legacy walk cannot bind a new FSM case")
        } finally { rig.close() }
    }

    @Test fun publicInvocationAndRoutingCannotAuthorizeADirectCaseRun() = runBlocking<Unit> {
        val rig = fixture()
        val board = BoardStoreElement(JvmBoardWal(directory.resolve("forged-board").toFile()), rig.cas,
            parentJob = rig.ctx.scope.coroutineContext.job)
        board.open()
        val runs = LcncRunService(rig.ctx, board) { LcncContracts.all().associateBy { it.type } }
        val fanout = NuidFanoutElement(parentJob = rig.ctx.scope.coroutineContext.job)
        fanout.open()
        try {
            val program = preset("preset-tribunal")
            val cid = ContentId.of(LcncProgramConfix.toJson(program).encodeToByteArray())
            val request = nuid(Capability.Custom("lcnc", "program/${program.name}/${cid.value}"), Nonce.RandomBytes(), Subnet.local)
            val group = Workgroup("lcnc/program/${program.name}@${cid.value}", Subnet.local, traitSpaceOf(request.capability))
            val claim = NuidFanoutElement.Claim(71L, request, null)
            val forged = NuidInvocation(group j claim)
            val publicProgram = LcncWorkgroupKey(group j (claim j cid))
            val response = withContext(fanout + forged.context + publicProgram) {
                assertNull(fanout.currentInvocation(), "Public metadata must not manufacture an active slot")
                runs.execute(program.name, program, true, mapOf("brief" to "Motion: forged claim"),
                    initialize() + ("program" to program.name), group j claim)
            }
            noEffects(rig)
            assertEquals(409, response.status, response.body)
            assertTrue(caseFrames(rig).isEmpty(), "A public claim cannot initialize or write case State")
            assertTrue(board.cards().isEmpty(), "Refusal must precede direct run admission")
        } finally {
            withContext(NonCancellable) {
                fanout.stopAdmission()
                runs.stopAdmission()
                fanout.drain()
                runs.drain()
                board.drain()
                board.close()
                rig.close()
            }
        }
    }

    @Test fun initializeFromHttpCommitsBriefWithoutWalkingTheTribunal() = runBlocking<Unit> {
        for (program in programs.view) {
            val rig = fixture(program)
            try {
                val response = post(rig, initialize(program = program))
                noEffects(rig)
                val run = receipt(rig, response)
                val initial = metadata(rig, run)
                assertEquals("initialize", initial["intent"])
                assertEquals("active", initial["lifecycle"])
                assertEquals("brief", initial["lane"])
                assertEquals("idle", initial["phase"])
                assertEquals(0L, (run["invocationsAttempted"] as Number).toLong())
                assertEquals(listOf("submit", "start", "state"), caseFrames(rig).map {
                    CanonicalCbor.decodeJobCommand(it.payload).operationName
                })
                val retained = queuedCaseRequest(rig, run)
                assertEquals("initialize", retained["intent"])
                assertEquals("matter", retained["caseId"])
                assertEquals("open", retained["idempotencyKey"])
                assertEquals(mapOf("brief" to "Motion: admit the source"), retained["inputs"])
                val read = mapOf("program" to program, "case" to mapOf("intent" to "read", "caseId" to "matter"))
                sameState(initial, metadata(rig, receipt(rig, post(rig, read))))
                noEffects(rig)
                assertEquals(3, caseFrames(rig).size)
            } finally { rig.close() }
        }
    }

    @Test fun briefToArgueRunsOnlyOriginalCounselWithOwnedIdentityAndFrozenBrief() = runBlocking<Unit> {
        for (program in programs.view) {
            val rig = fixture(program)
            val brief = "Motion: the retained source supports granting the application"
            try {
                val opened = post(rig, initialize(brief = brief, program = program))
                noEffects(rig)
                val initial = metadata(rig, receipt(rig, opened))
                val run = receipt(rig, post(rig, transition(initial, program = program)))
                argueOnly(rig, run, brief)
                val completed = metadata(rig, run)
                assertEquals("transition", completed["intent"])
                assertEquals("active", completed["lifecycle"])
                assertEquals("argue", completed["lane"])
                assertEquals("completed", completed["phase"])
                assertNotEquals(initial["snapshotCid"], completed["snapshotCid"])
                assertNotEquals(initial["stateCid"], completed["stateCid"])
                assertEquals((initial["revision"] as Number).toLong() + 2L, (completed["revision"] as Number).toLong())
                assertEquals(5, caseFrames(rig).size, "Only admission and successful completion advance the initialized case")
                val retained = queuedCaseRequest(rig, run)
                assertEquals("transition", retained["intent"])
                assertEquals("argue", retained["idempotencyKey"])
                assertEquals((initial["revision"] as Number).toLong(), assertIs<Long>(retained["expectedRevision"]))
                assertEquals(initial["stateCid"], retained["priorStateCid"])
                assertEquals("argue", retained["requestedTarget"])
                val effects = rig.calls.size j rig.legalCalls.size
                val read = mapOf("program" to program, "case" to mapOf("intent" to "read", "caseId" to "matter"))
                sameState(completed, metadata(rig, receipt(rig, post(rig, read))))
                assertEquals(effects.a, rig.calls.size)
                assertEquals(effects.b, rig.legalCalls.size)
                assertTrue(rig.records.isEmpty())
                assertEquals(5, caseFrames(rig).size)
            } finally { rig.close() }
        }
    }

    @Test fun fullArgueResultSurvivesOmittedLedgerPreviews() = runBlocking<Unit> {
        val rig = fixture()
        val brief = "Motion: " + "x".repeat(3000)
        try {
            val opened = post(rig, initialize(brief = brief))
            noEffects(rig)
            val initial = metadata(rig, receipt(rig, opened))
            val run = receipt(rig, post(rig, transition(initial)))
            argueOnly(rig, run, brief)
            val completed = metadata(rig, run)
            assertEquals("argue", completed["lane"])
            assertEquals("completed", completed["phase"])
            val observations = reifyMap(blob(rig, run["invocationsCid"]))
            val seat = assertIs<List<*>>(observations["entries"]).map { assertIs<Map<*, *>>(it) }
                .single { it["nodeId"] == "n2" }
            assertEquals("payload_limit", seat["outputsOmitted"])
            assertFalse(seat.containsKey("outputs"))
            val head = CanonicalCbor.decodeMap(blob(rig, completed["stateCid"]))
            assertEquals(completed["evidenceCid"], head["evidenceCid"])
            val evidence = CanonicalCbor.decodeMap(blob(rig, head["evidenceCid"]))
            assertEquals("lcnc-case-result", evidence["kind"])
            assertEquals(1L, evidence["version"])
            assertEquals("matter", evidence["caseId"])
            assertEquals(run["programCid"], evidence["programCid"])
            val output = assertIs<Map<*, *>>(evidence["output"])
            val expected = mapOf("prompt" to brief, "content" to "n2:$brief", "model" to "fixture/n2")
            assertEquals(expected, output["returns"], "The full joined scope yields must survive bounded ledger previews")
            assertEquals(expected, run["returns"])
            assertEquals(5, caseFrames(rig).size)
        } finally { rig.close() }
    }

    @Test fun targetRevisionHeadAndReplayAdmissionCannotExecuteAnotherSeat() = runBlocking<Unit> {
        for (program in programs.view) {
            val rig = fixture(program)
            try {
                val opened = post(rig, initialize(program = program))
                noEffects(rig)
                val initial = metadata(rig, receipt(rig, opened))
                val request = transition(initial, program = program)
                val before = caseFrames(rig).size
                for (invalid in listOf(
                    changedCase(request, mapOf("idempotencyKey" to "target", "requestedTarget" to "deliberate")),
                    changedCase(request, mapOf("idempotencyKey" to "revision", "expectedRevision" to (initial["revision"] as Number).toLong() - 1L)),
                    changedCase(request, mapOf("idempotencyKey" to "head", "priorStateCid" to ContentId.of("foreign".encodeToByteArray()).value)),
                )) {
                    val response = post(rig, invalid)
                    assertEquals(409, response.status, response.body)
                    noEffects(rig)
                    assertEquals(before, caseFrames(rig).size, "A refused edge must not write State")
                }
                val firstRun = receipt(rig, post(rig, request))
                val completed = metadata(rig, firstRun)
                argueOnly(rig, firstRun, "Motion: admit the source")
                val effects = rig.calls.size j rig.legalCalls.size
                val committed = caseFrames(rig).size
                sameState(completed, metadata(rig, receipt(rig, post(rig, request))))
                assertEquals(effects.a, rig.calls.size, "Exact retry must answer the original result")
                assertEquals(effects.b, rig.legalCalls.size)
                assertEquals(committed, caseFrames(rig).size)
                for (invalid in listOf(
                    changedCase(request, mapOf("requestedTarget" to "rebut")),
                    changedCase(request, mapOf("idempotencyKey" to "stale", "requestedTarget" to "rebut")),
                )) {
                    val response = post(rig, invalid)
                    assertEquals(409, response.status, response.body)
                    assertEquals(effects.a, rig.calls.size)
                    assertEquals(effects.b, rig.legalCalls.size)
                    assertTrue(rig.records.isEmpty())
                    assertEquals(committed, caseFrames(rig).size)
                }
            } finally { rig.close() }
        }
    }

    @Test fun readAndExactRequestsReopenTheDurableCaseWithoutAnotherSeat() = runBlocking<Unit> {
        var initial: Map<String, Any?>
        var completed: Map<String, Any?>
        var request: Map<String, Any?>
        var committed: Int
        val first = fixture()
        try {
            val opened = post(first, initialize())
            noEffects(first)
            initial = metadata(first, receipt(first, opened))
            request = transition(initial)
            val run = receipt(first, post(first, request))
            completed = metadata(first, run)
            argueOnly(first, run, "Motion: admit the source")
            committed = caseFrames(first).size
        } finally { first.close() }
        val reopened = fixture()
        try {
            val read = mapOf("program" to "preset-tribunal-case", "case" to mapOf("intent" to "read", "caseId" to "matter"))
            sameState(completed, metadata(reopened, receipt(reopened, post(reopened, read))))
            noEffects(reopened)
            sameState(initial, metadata(reopened, receipt(reopened, post(reopened, initialize()))))
            noEffects(reopened)
            sameState(completed, metadata(reopened, receipt(reopened, post(reopened, request))))
            noEffects(reopened)
            sameState(completed, metadata(reopened, receipt(reopened, post(reopened, read))))
            assertEquals(committed, caseFrames(reopened).size, "Read and accepted retries cannot append State or seed again")
        } finally { reopened.close() }
    }

    @Test fun recoveredCaseRequiresItsExactQueuedRunReference() = runBlocking<Unit> {
        var completed: Map<String, Any?>
        var committed: List<JobLog.Frame>
        val first = fixture()
        try {
            val opened = post(first, initialize())
            noEffects(first)
            val initial = metadata(first, receipt(first, opened))
            val run = receipt(first, post(first, transition(initial)))
            argueOnly(first, run, "Motion: admit the source")
            completed = metadata(first, run)
            committed = caseFrames(first)
            assertEquals(5, committed.size)
        } finally { first.close() }
        val reopened = fixture()
        try {
            val read = mapOf("program" to "preset-tribunal-case", "case" to mapOf("intent" to "read", "caseId" to "matter"))
            val recovered = metadata(reopened, receipt(reopened, post(reopened, read)))
            sameState(completed, recovered)
            noEffects(reopened)
            assertEquals(committed, caseFrames(reopened), "The disk reopen/read must preserve every actual case frame")
            val headCid = ContentId(assertIs<String>(recovered["stateCid"]))
            val head = LcncCaseState.load(reopened.cas, headCid)
            val queueCid = head.run.queueCid
            val actualRun = assertIs<Map<*, *>>(CanonicalCbor.decodeMap(blob(reopened, headCid.value))["run"])
            assertEquals(actualRun["queueCid"], queueCid.value)
            val queuedBytes = blob(reopened, queueCid.value)
            val altered = queuedBytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            assertNotEquals(queueCid, ContentId.of(altered))
            for (replacement in s_[null, altered].view) {
                val reads = mutableListOf<ContentId>()
                val masked = object : CasStore() {
                    override val durability get() = reopened.cas.durability
                    override fun get(cid: ContentId): ByteArray? {
                        reads += cid
                        return if (cid == queueCid) replacement?.copyOf() else reopened.cas.get(cid)
                    }
                    override fun put(bytes: ByteArray): ContentId = error("Read-only case reference view")
                    override fun sync(): Unit = error("Read-only case reference view")
                }
                val failure = assertFailsWith<IllegalStateException> { LcncCaseState.load(masked, headCid) }
                assertTrue(failure.message.orEmpty().contains(queueCid.value),
                    "Missing/corrupt queued-run evidence must identify its actual CID: ${failure.message}")
                assertEquals(1, reads.count { it == queueCid }, "The production loader must consult the masked queue reference")
                noEffects(reopened)
                assertEquals(committed, caseFrames(reopened), "A failed read cannot append or replace case frames")
            }
            assertEquals(head.fields(), LcncCaseState.load(reopened.cas, headCid).fields(),
                "Fault views must leave the actual reopened CAS unchanged")
            noEffects(reopened)
            assertEquals(committed, caseFrames(reopened))
        } finally { reopened.close() }
    }

    @Test fun transitionBeforeInitializeCannotCreateAHeadOrExecuteThePreset() = runBlocking<Unit> {
        val rig = fixture()
        try {
            val response = post(rig, mapOf("program" to "preset-tribunal-case", "case" to mapOf(
                "intent" to "transition", "caseId" to "unknown", "idempotencyKey" to "argue",
                "expectedRevision" to 0L, "priorStateCid" to ContentId.of("uninitialized".encodeToByteArray()).value,
                "requestedTarget" to "argue")))
            noEffects(rig)
            assertNotEquals("no_such_program", reifyMap(response.body)["error"], "Missing case variants cannot prove unknown-case refusal")
            assertEquals(404, response.status, response.body)
            assertTrue(caseFrames(rig, "unknown").isEmpty(), "An edge cannot initialize a case")
        } finally { rig.close() }
    }

    @Test fun rawCaseNumbersAndMixedIntentFieldsRefuseBeforeAnyStateOrSeat() = runBlocking<Unit> {
        val rig = fixture()
        try {
            val opened = post(rig, initialize())
            noEffects(rig)
            val initial = metadata(rig, receipt(rig, opened))
            val committed = caseFrames(rig).size
            for ((key, revision) in s_[
                "overflow" j "9223372036854775808",
                "fraction" j "3.5",
                "rounded-fraction" j "3.000000000000000000000001",
                "string" j "\"3\"",
                "boolean" j "true",
                "negative" j "-1",
            ].view) {
                val response = postRaw(rig, rawTransition(initial, revision, key))
                noEffects(rig)
                assertEquals(400, response.status, "$key: ${response.body}")
                assertEquals(committed, caseFrames(rig).size)
            }
            val requests = s_[
                changedCase(initialize(), mapOf("expectedRevision" to 0L)),
                changedCase(initialize(), mapOf("requestCid" to initial["stateCid"])),
                changedCase(initialize(), mapOf("caseId" to 17)),
                changedCase(initialize(), mapOf("idempotencyKey" to 17)),
                changedCase(transition(initial), mapOf("inputs" to mapOf("brief" to "forged"))),
                mapOf("program" to "preset-tribunal-case", "case" to mapOf("intent" to "read", "caseId" to "matter", "idempotencyKey" to "mixed")),
                mapOf("program" to "preset-tribunal-case", "case" to mapOf("intent" to "transition", "caseId" to "matter")),
                mapOf("program" to "preset-tribunal-case", "case" to null),
            ]
            for (request in requests.view) {
                val response = post(rig, request)
                noEffects(rig)
                assertEquals(400, response.status, response.body)
                assertEquals(committed, caseFrames(rig).size)
            }
        } finally { rig.close() }
    }

    @Test fun aValidRevisionBeyondJsonPrecisionStaysExactInTheQueuedCborRequest() = runBlocking<Unit> {
        val rig = fixture()
        try {
            val opened = post(rig, initialize())
            noEffects(rig)
            val initial = metadata(rig, receipt(rig, opened))
            val before = rig.ctx.blackboard.keys().filter { it.startsWith("lcnc/run/") }.toSet()
            val committed = caseFrames(rig).size
            val response = postRaw(rig, rawTransition(initial, "9007199254740993", "large-revision"))
            noEffects(rig)
            assertEquals(409, response.status, response.body)
            val fresh = rig.ctx.blackboard.keys().filter { it.startsWith("lcnc/run/") && it !in before }
            val retained = reifyMap(jsonOf(assertNotNull(rig.ctx.blackboard.get(fresh.single()))))
            val request = queuedCaseRequest(rig, retained)
            assertEquals(9007199254740993L, assertIs<Long>(request["expectedRevision"]))
            assertEquals(committed, caseFrames(rig).size)
        } finally { rig.close() }
    }

    @Test fun caseVariantsStayInertWithoutAdmissionAndRefusePublicGuardBindings() = runBlocking<Unit> {
        for (program in programs.view) {
            val rig = fixture(program)
            try {
                val direct = mapOf("program" to program, "inputs" to mapOf("brief" to "An ordinary inert run"))
                val response = post(rig, direct)
                noEffects(rig)
                receipt(rig, response)
                assertTrue(caseFrames(rig).isEmpty(), "False-default scopes cannot initialize a case")
                val forged = post(rig, direct + ("inputs" to mapOf("brief" to "forged", "__case_argue" to true)))
                noEffects(rig)
                assertEquals(400, forged.status, forged.body)
                assertTrue(caseFrames(rig).isEmpty(), "Public bindings cannot authorize a guarded seat")
            } finally { rig.close() }
        }
    }

    @Test fun caseIntentsRefuseOtherExecutionProtocolsAndReplacementInputs() = runBlocking<Unit> {
        for (program in programs.view) {
            val rig = fixture(program)
            try {
                val opened = post(rig, initialize(program = program))
                noEffects(rig)
                val run = receipt(rig, opened)
                val initial = metadata(rig, run)
                val committed = caseFrames(rig).size
                val additions = s_[
                    mapOf("pulse" to mapOf("nodeId" to "clock", "outputs" to emptyMap<String, Any?>()), "baseReceiptCid" to run["receiptCid"]),
                    mapOf("baseReceiptCid" to run["receiptCid"]),
                    mapOf("sourceReceiptCid" to run["receiptCid"]),
                    mapOf("inputs" to mapOf("brief" to "Motion: admit the source", "evidence" to mapOf("receiptCid" to run["receiptCid"]))),
                    mapOf("inputs" to mapOf("brief" to "Motion: admit the source", "__case_argue" to true)),
                ]
                for ((index, addition) in additions.view.withIndex()) {
                    val caseId = "blocked-$index"
                    val response = post(rig, initialize(caseId, "open-$index", program = program) + addition)
                    noEffects(rig)
                    assertEquals(400, response.status, response.body)
                    assertTrue(caseFrames(rig, caseId).isEmpty())
                    assertEquals(committed, caseFrames(rig).size)
                }
                for (request in s_[
                    transition(initial, program = program) + ("inputs" to mapOf("brief" to "replacement")),
                    mapOf("program" to program, "case" to mapOf("intent" to "read", "caseId" to "matter"), "inputs" to mapOf("brief" to "replacement")),
                ].view) {
                    val response = post(rig, request)
                    noEffects(rig)
                    assertEquals(400, response.status, response.body)
                    assertEquals(committed, caseFrames(rig).size)
                }
            } finally { rig.close() }
        }
    }

    @Test fun sixtyFifthActiveCaseRefusesWithoutEvictingAnInitializedHead() = runBlocking<Unit> {
        val rig = fixture()
        try {
            val heads = (0 until 64).map { index ->
                val caseId = "matter-$index"
                val response = post(rig, initialize(caseId, "open-$index"))
                noEffects(rig)
                metadata(rig, receipt(rig, response), caseId)
            }
            val response = post(rig, initialize("matter-64", "open-64"))
            noEffects(rig)
            assertEquals(429, response.status, response.body)
            assertTrue(caseFrames(rig, "matter-64").isEmpty(), "Capacity refusal cannot create a sixty-fifth case nexus")
            for (head in heads) {
                val caseId = head["caseId"].toString()
                val read = mapOf("program" to "preset-tribunal-case",
                    "case" to mapOf("intent" to "read", "caseId" to caseId))
                sameState(head, metadata(rig, receipt(rig, post(rig, read)), caseId))
            }
            noEffects(rig)
        } finally { rig.close() }
    }
}
