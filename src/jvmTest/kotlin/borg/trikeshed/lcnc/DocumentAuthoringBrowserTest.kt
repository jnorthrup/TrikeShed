package borg.trikeshed.lcnc

import borg.trikeshed.graal.subvm.CoreNlpRuntime
import borg.trikeshed.graal.subvm.harness.DocumentModelFixture
import borg.trikeshed.job.*
import borg.trikeshed.lcnc.ccek.*
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.JvmKanbanServer.HttpResponse
import borg.trikeshed.modelmux.*
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.narsese.*
import borg.trikeshed.nlp.NlpReader
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import borg.trikeshed.vm.*
import kotlinx.coroutines.*
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Only model transport is fixed; editing, curation, execution and immutable inspection are production paths. */
class DocumentAuthoringBrowserTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path

    @Test
    @org.junit.jupiter.api.Timeout(value = 12, unit = java.util.concurrent.TimeUnit.MINUTES)
    fun retainedMedicalSourceAuthorsOneLocalRoute() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(SystemOperations.default.getenv("TRIKESHED_DOCUMENT_AUTHORING_UI") == "1")
        val modelCalls = AtomicInteger()
        val nlpCalls = AtomicInteger()
        val guestCalls = AtomicInteger()
        val camelCalls = AtomicInteger()
        val posts = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()
        val proofs = CopyOnWriteArrayList<String>()
        val failures = CopyOnWriteArrayList<String>()
        val stages = listOf("applied", "completed", "frozen")
        val edited = """
            const destination = input.evaluation.firings.length === 0 ? 'log:document-review-empty' : 'log:document-review';
            return {from: 'direct:document-review', steps: [
              {setHeader: {name: 'CurationReceipt', value: input.receiptCid}},
              {setHeader: {name: 'AuthoringReview', value: 'browser-edited'}},
              {to: destination}
            ]};
        """.trimIndent()
        val session = DocumentModelFixture.open {
            modelCalls.incrementAndGet()
            ModelResponse("""{"format":"TRIPLET_JSON","triplets":[]}""", ModelUsage(0, 0, 0), "fixture", "fixture")
        }
        val owner = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + owner + session.htx + session.reactor)
        val context = vmRuntimeTestContext(scope, session.htx + session.reactor)
        val binding = ccekReactorBinding(scope.coroutineContext)
        val cas = context.casStore
        val host = HypervisorVmHost()
        val nlp = CoreNlpRuntime()
        val bag = BeliefBagElement(cas = cas, parentJob = owner)
        val log = DocumentAppendLog.open(directory.resolve("curation.wal").toString(), directory.toString())
        var curator: DocumentCuratorElement? = null
        try {
            bag.open()
            val reader = DocumentCuratorElement.create(scope,
                NlpReader { text -> nlpCalls.incrementAndGet(); nlp.read(text) }, session.model, "fixture", cas, log, bag)
            curator = reader
            val medical = LcncProgramConfix.fromJson(LcncWorkspacePresets.MEDICAL,
                LcncWorkspacePresets.documents().getValue(LcncWorkspacePresets.MEDICAL))
            val source = DocumentCurationLegos.retainSource(cas,
                reifyStrict(medical.nodes.view.single { it.id == "source" }.params.getValue("value")))
            val curated = reader.curate(source)
            assertNotNull(curated.record.nlp)
            assertTrue(DocumentCurationFacts.ruleCandidates(curated.record).size >= 2)
            assertEquals(1, modelCalls.get()); assertEquals(1, nlpCalls.get())
            val assumptions = assertIs<List<*>>(reifyStrict(medical.nodes.view.single { it.id == "assumptions" }
                .params.getValue("value"))).map { reifyMap(jsonOf(it)) }
            assertEquals(3, assumptions.size)
            val routing = LcncWorkspacePresets.routing(VmLanguage.JAVASCRIPT, curated.recordCid.value, assumptions)
            val program = routing.copy(nodes = routing.nodes α { node ->
                if (node.id == "sheet") node.copy(params = node.params + mapOf("offset" to "1", "limit" to "1")) else node
            })
            val original = program.nodes.view.single { it.id == "author" }.params.getValue("source")
            assertNotEquals(original, edited)
            val expectedSheet = reifyMap(jsonOf(DocumentCurationLegos.sheet(curated.recordCid, curated.record,
                mapOf("cursor" to "rules", "offset" to "1", "limit" to "1"))))
            val retained = listOf(curated.recordCid, source.originalCid, source.extractedTextCid).distinct()
                .associateWith { assertNotNull(cas.get(it)).copyOf() }
            val recordBytes = DocumentCuratorCodec.encode(curated.record)
            val frames = linkedMapOf<Long, ByteArray>()
            log.replay { sequence, bytes -> frames[sequence] = bytes.copyOf() }
            assertTrue(frames.isNotEmpty())
            fun counted(actual: LcncNodeRunner, counter: AtomicInteger): LcncNodeRunner = object : LcncNodeRunner,
                LcncServiceBinding by (actual as LcncServiceBinding) {
                override suspend fun sourceDebugging(node: LcncNode, inputs: Map<String, Any?>) = actual.sourceDebugging(node, inputs)
                override suspend fun execute(node: LcncNode, inputs: Map<String, Any?>): Map<String, Any?> {
                    counter.incrementAndGet()
                    return actual.execute(node, inputs)
                }
            }
            context.lcncRunners.putAll(PureNodes.registry { 0L })
            context.lcncRunners.putAll(kanbanLcncRegistry())
            context.lcncRunners[DocumentCurationLegos.SHEET] = DocumentCurationLegos.sheet(reader)
            context.lcncRunners[SubVmLegos.POLYGLOT] = counted(SubVmLegos.polyglot(host), guestCalls)
            context.lcncRunners[SubVmLegos.CAMEL] = counted(SubVmLegos.camel(host), camelCalls)
            lateinit var active: ModuleContext
            var completed: Map<String, Any?>? = null
            val evidenceBytes = linkedMapOf<ContentId, ByteArray>()
            fun runs() = active.blackboard.snapshot().values.filterKeys { it.startsWith("lcnc/run/") }
                .values.map { reifyMap(jsonOf(it)) }
            fun admissions() = posts.filter { it.first == "/api/lcnc/run" && "document" in it.second }
            suspend fun unchanged() {
                assertEquals(1, modelCalls.get(), "Only initial curation may request the model")
                assertEquals(1, nlpCalls.get(), "Retained authoring must not re-enter NLP")
                for ((cid, bytes) in retained) assertContentEquals(bytes, cas.get(cid), cid.value)
                assertContentEquals(recordBytes, DocumentCuratorCodec.encode(assertNotNull(reader.record(curated.recordCid))))
                var count = 0
                log.replay { sequence, bytes -> assertContentEquals(frames[sequence], bytes); count++ }
                assertEquals(frames.size, count, "No new curation ledger frames")
                assertTrue(posts.all { it.first in listOf("/api/lcnc/run", "/api/lcnc/workspace", "/api/lcnc/rdf/align") },
                    "Unexpected API POST: ${posts.map { it.first }}")
                // The endpoint selectors discover their read-only catalog through single-node dispatch.
                // That request is not admission of this authored construction or execution of its source.
                for ((_, request) in posts.filter { it.first == "/api/lcnc/run" && "document" !in it.second }) {
                    assertEquals(setOf("type", "params", "inputs"), request.keys)
                    assertEquals(SubVmLegos.CAMEL_CATALOG, request["type"])
                    assertEquals(mapOf("reach" to "LOCAL"), request["params"])
                    assertEquals(emptyMap<String, Any?>(), request["inputs"])
                }
            }
            fun runEvidence(): Map<String, Any?> {
                val request = admissions().single().second
                assertFalse(listOf("type", "program", "pulse", "baseReceiptCid", "rebuildOf", "sourceReceiptCid", "debugToken")
                    .any { it in request }, "One ordinary inline Run, not replay, pulse, debug or node dispatch")
                val admitted = LcncProgramConfix.fromJson(assertIs<String>(request["name"]), jsonOf(request["document"]))
                assertEquals(edited, admitted.nodes.view.single { it.id == "author" }.params["source"])
                val run = runs().single()
                assertEquals("completed", run["status"]); assertEquals(true, run["ok"])
                assertEquals(1, guestCalls.get()); assertEquals(1, camelCalls.get())
                assertEquals(1, host.ids().size)
                assertTrue(host.ids().none { host.get(it)?.isAlive == true }, "Guest release must precede fixture drain")
                fun content(field: String): ByteArray {
                    val cid = ContentId(assertIs<String>(run[field]))
                    val bytes = assertNotNull(cas.get(cid))
                    assertEquals(cid, ContentId.of(bytes))
                    val prior = evidenceBytes[cid]
                    if (prior == null) evidenceBytes[cid] = bytes.copyOf() else assertContentEquals(prior, bytes)
                    return bytes
                }
                val frozenBytes = content("programCid")
                assertContentEquals(LcncProgramConfix.toJson(admitted).encodeToByteArray(), frozenBytes,
                    "Freeze the actual admitted document, including UI-populated defaults")
                val frozen = LcncProgramConfix.fromJson(admitted.name, frozenBytes.decodeToString())
                assertEquals(edited, frozen.nodes.view.single { it.id == "author" }.params["source"])
                val command = reifyMap(content("receiptCid"))
                assertEquals("lcnc/run/${run["runId"]}", command["jobId"])
                val receipt = assertIs<Map<*, *>>(command["lcncRun"])
                for (key in listOf("runId", "programCid", "invocationsCid", "status", "outputs", "consumed"))
                    assertEquals(run[key], receipt[key], key)
                val outputs = assertIs<Map<*, *>>(receipt["outputs"])
                fun output(id: String) = assertIs<Map<*, *>>(outputs[id])
                val sheet = output("sheet")
                assertEquals(curated.recordCid.value, sheet["receiptCid"])
                assertEquals(expectedSheet["selection"], sheet["selection"])
                assertEquals(expectedSheet["ruleCandidates"], sheet["ruleCandidates"])
                val evaluation = assertIs<Map<*, *>>(output("simulation")["evaluation"])
                assertEquals(assumptions, evaluation["assertions"])
                val firings = assertIs<List<*>>(evaluation["firings"]).map { assertIs<Map<*, *>>(it) }
                assertEquals(3, firings.size)
                assertEquals(assumptions.map { it["obj"] }.toSet(), firings.map { it["matchedObject"] }.toSet())
                val rule = assertIs<Map<*, *>>(assertIs<List<*>>(expectedSheet["ruleCandidates"]).single())
                assertTrue(firings.all { it["ruleCid"] == rule["ruleCid"] && it["provenanceCid"] == source.extractedTextCid.value })
                val payload = mapOf("receiptCid" to curated.recordCid.value, "selection" to sheet["selection"], "evaluation" to evaluation)
                assertEquals(payload, output("payload")["returns"])
                val definition = mapOf("from" to "direct:document-review", "steps" to listOf(
                    mapOf("setHeader" to mapOf("name" to "CurationReceipt", "value" to curated.recordCid.value)),
                    mapOf("setHeader" to mapOf("name" to "AuthoringReview", "value" to "browser-edited")),
                    mapOf("to" to "log:document-review")))
                assertEquals(definition, output("author")["value"])
                val route = output("route")
                assertEquals(true, route["ok"])
                assertEquals(payload, reifyStrict(assertIs<String>(route["routed"])))
                val camel = assertIs<Map<*, *>>(route["receipt"])
                assertEquals(definition, camel["plan"])
                assertEquals("direct:document-review", camel["from"])
                assertEquals(payload, reifyStrict(assertIs<String>(camel["reply"])))
                val headers = assertIs<Map<*, *>>(camel["headers"])
                assertEquals(curated.recordCid.value, headers["CurationReceipt"])
                assertEquals("browser-edited", headers["AuthoringReview"])
                assertEquals(listOf(mapOf("kind" to "document-curation", "id" to source.name,
                    "cid" to curated.recordCid.value)), receipt["consumed"])
                val invocations = reifyMap(content("invocationsCid"))
                assertEquals("lcnc-invocations", invocations["kind"])
                assertEquals(run["runId"], invocations["runId"])
                assertEquals(run["programCid"], invocations["programCid"])
                assertEquals(0, (invocations["dropped"] as Number).toInt())
                val entries = assertIs<List<*>>(invocations["entries"]).map { assertIs<Map<*, *>>(it) }
                for ((id, type) in listOf("author" to SubVmLegos.POLYGLOT, "route" to SubVmLegos.CAMEL)) {
                    val entry = entries.single { it["nodeId"] == id }
                    assertEquals(type, entry["type"]); assertEquals("completed", entry["status"])
                }
                completed?.let { assertEquals(it, run, "Inspection cannot replace the observed run") }
                return run
            }
            suspend fun verify(proof: Map<String, Any?>) {
                val stage = assertIs<String>(proof["stage"])
                assertEquals(stages.getOrNull(proofs.size), stage, "Exactly one ordered proof per stage")
                unchanged()
                when (stage) {
                    "applied" -> {
                        assertEquals(original, proof["draftBefore"])
                        assertEquals(true, proof["dialogClosed"])
                        assertEquals(edited, proof["authorSource"])
                        assertTrue(admissions().isEmpty(), "Applying source must not admit a program")
                        assertTrue(runs().isEmpty())
                        assertEquals(0, guestCalls.get()); assertEquals(0, camelCalls.get()); assertTrue(host.ids().isEmpty())
                    }
                    "completed" -> {
                        val run = runEvidence()
                        assertEquals(run["receiptCid"], proof["receiptValue"])
                        val text = assertIs<String>(proof["evidenceText"])
                        for (field in listOf("runId", "status", "receiptCid", "programCid", "invocationsCid"))
                            assertTrue(text.contains(assertIs<String>(run[field])), field)
                        assertEquals("Frozen program at invocation · ${run["programCid"]}", proof["frozenReference"])
                        completed = run
                    }
                    "frozen" -> {
                        val run = runEvidence()
                        assertEquals(run["programCid"], proof["contentId"])
                        val frozen = reifyMap(assertNotNull(cas.get(ContentId(run["programCid"] as String))))
                        val index = assertIs<List<*>>(frozen["nodes"]).indexOfFirst { assertIs<Map<*, *>>(it)["id"] == "author" }
                        assertTrue(index >= 0)
                        val path = listOf("nodes", index, "params", "source")
                        assertEquals(reifyStrict(jsonOf(path)), reifyStrict(assertIs<String>(proof["path"])))
                        assertEquals(edited, reifyStrict(assertIs<String>(proof["selectedValue"])))
                        assertEquals(edited, proof["authorSource"])
                        assertEquals(true, proof["retainedNotice"])
                        assertTrue(posts.any { (endpoint, request) ->
                            val selected = request["source"] as? Map<*, *>
                            endpoint == "/api/lcnc/workspace" && selected?.get("kind") == "cas" &&
                                selected["cid"] == run["programCid"] && request["path"] == reifyStrict(jsonOf(path))
                        }, "The actual Confix path request must select the retained source scalar")
                    }
                }
                proofs += stage
            }
            retainedSimulationBrowser(context, reader, binding, program, executorThreads = 4,
                opened = { active = it },
                requests = { method, path -> if (path.startsWith("/api/") && method !in listOf("GET", "HEAD", "POST"))
                    failures += "Unexpected browser write: $method $path" },
                requestBodies = { method, path, body -> if (method == "POST" && path.startsWith("/api/")) {
                    try { posts += path to reifyMap(body) }
                    catch (failure: Exception) { failures += "Invalid API POST: $path ${failure.message}" }
                } },
                testRoute = { method, path, body ->
                    if (method == "POST" && path == "/_test/proof") try {
                        verify(reifyMap(body)); HttpResponse(200, "{\"ok\":true}")
                    } catch (failure: Throwable) {
                        failures += failure.message ?: "Browser proof failed"
                        if (failure is CancellationException) throw failure
                        HttpResponse(409, jsonOf(mapOf("ok" to false, "error" to failure.message)))
                    } else null
                },
                observations = {
                    val document = admissions().firstOrNull()?.second?.get("document") as? Map<*, *>
                    val nodes = document?.get("nodes") as? List<*>
                    mapOf("expectedSource" to edited, "curationCid" to curated.recordCid.value,
                    "authorIndex" to (nodes?.indexOfFirst { (it as? Map<*, *>)?.get("id") == "author" }
                        ?: program.nodes.view.indexOfFirst { it.id == "author" }),
                    "runPosts" to admissions().size,
                    "catalogRequests" to posts.filter { it.first == "/api/lcnc/run" && "document" !in it.second }.map { it.second },
                    "guestCalls" to guestCalls.get(),
                    "camelCalls" to camelCalls.get(), "modelCalls" to modelCalls.get(), "nlpCalls" to nlpCalls.get(),
                    "runs" to runs().map { run -> run.filterKeys { it in listOf("runId", "status", "receiptCid", "programCid", "invocationsCid") } },
                    "proofs" to proofs.toList(), "failures" to failures.toList()) })
            assertEquals(stages, proofs.toList(), "Missing browser proof; drain cannot satisfy a missing stage")
            assertTrue(failures.isEmpty(), failures.joinToString("\n"))
            unchanged(); runEvidence()
        } finally {
            withContext(NonCancellable) {
                try { curator?.drain() } finally {
                    try { bag.drain() } finally {
                        try { host.close(); nlp.close(); log.close() } finally {
                            try { owner.cancelAndJoin() } finally { session.close() }
                        }
                    }
                }
            }
        }
    }
}
