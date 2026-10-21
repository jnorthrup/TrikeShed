package borg.trikeshed.forge.server

import borg.trikeshed.parse.reifyMap
import borg.trikeshed.parse.jsonOf

import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.dag.PlaneFacts
import borg.trikeshed.graal.subvm.CoreNlpRuntime
import borg.trikeshed.graal.subvm.CamelCatalog
import borg.trikeshed.graal.subvm.GuestModules
import borg.trikeshed.job.ContentId
import borg.trikeshed.modelmux.BrainClient
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.collections.SeriesBuffer
import borg.trikeshed.litebike.WireHttpResponse
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.narsese.*
import borg.trikeshed.relaxfactory.CouchHttpSurface
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.fileIoContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import modelmux.acp.providerTag

/** One daemon-owned curator, shared by document HTTP, LCNC, and applicant projections. */
class DocumentCurationWire private constructor(
    private val ctx: ModuleContext,
    val curator: DocumentCuratorElement,
    private val nlp: CoreNlpRuntime,
    private val log: DocumentAppendLog,
    private val panels: PatchWire,
    val destination: Map<String, Any?>,
) {
    companion object {
        const val PROGRAM = "document.curate"
        const val PROFILE_PROGRAM = "headhunter.curate"

        fun program(name: String, instructions: String? = null): LcncProgram {
            val nodes = SeriesBuffer<LcncNode>()
            val wires = SeriesBuffer<LcncWire>()
            nodes.add(LcncNode("source", LcncContracts.SCOPE_IN, mapOf("name" to "source", "kind" to "DocumentSource"), 40.0, 80.0))
            nodes.add(LcncNode("curate", DocumentCurationLegos.CURATE, instructions?.let { mapOf("instructions" to it) }.orEmpty(), 350.0, 80.0))
            wires.add(LcncWire("source", "value", "curate", "source"))
            if (instructions != null) {
                nodes.add(LcncNode("profile", "headhunter.profile", x = 660.0, y = 80.0))
                nodes.add(LcncNode("knowledge", LcncContracts.SCOPE_OUT, mapOf("name" to "profile", "kind" to "json"), 970.0, 80.0))
                wires.add(LcncWire("curate", "receiptCid", "profile", "receiptCid"))
                wires.add(LcncWire("profile", "profile", "knowledge", "value"))
            }
            // ruleCandidates is a typed yield shaped for nal.rule.admit; admission is a cable an operator adds.
            for ((index, port) in s_["receiptCid", "record", "sheet", "sheets", "ruleCandidates"].view.withIndex()) {
                nodes.add(LcncNode(port, LcncContracts.SCOPE_OUT, mapOf("name" to port, "kind" to when (port) {
                    "receiptCid" -> "id"; "ruleCandidates" -> "ProposedRules"; else -> "json"
                }), 970.0, 240.0 + index * 160))
                wires.add(LcncWire("curate", port, port, "value"))
            }
            return LcncProgram(name, nodes.drain(), wires.drain())
        }

        suspend fun open(ctx: ModuleContext, brain: BrainClient, panels: PatchWire): DocumentCurationWire? =
            withContext(ctx.muxContext + fileIoContext) {
                val bag = ctx.beliefBag ?: return@withContext unavailable(ctx,
                    "Document curation requires the daemon belief bag. Start with --belief-bag or TRIKESHED_BELIEF_BAG=1; this does not submit documents to a model.")
                val mux = brain.modelMux()
                val model = mux.defaultModel ?: brain.lastModel() ?: brain.endpointSummaries().firstOrNull()?.model
                    ?: return@withContext unavailable(ctx,
                        "No document curation model is configured. Configure a chat model in ModelMux, then start the document curator.")
                if ("corenlp" !in GuestModules.installed()) return@withContext unavailable(ctx,
                    "The CoreNLP guest module is not installed. Install CoreNLP before starting document curation.")
                val session = try { mux.session(model).getOrThrow() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { return@withContext unavailable(ctx,
                        "Document curation model is unavailable: ${failure.message ?: failure::class.simpleName}") }
                val base = session.baseUrl.trimEnd('/')
                val destination = mapOf("provider" to (session.model.b.providerTag ?: model),
                    "model" to model, "url" to "$base/chat/completions")
                session.close()
                val toolOntology = DocumentCurationToolset.all(
                    buildList {
                        add("available:forge.document.curate=/api/documents")
                        add("available:forge.document.curate=/api/documents/curate")
                        add("available:forge.lcnc.run=/api/lcnc/run")
                        addAll(CamelCatalog.endpoints().map { "available:camel.endpoint=$it" })
                        addAll(mux.listModels("chat").view.map { "available:${DocumentCurationToolset.MODEL_BASE}.model=${it.a}" })
                        addAll(brain.providerRoster().map {
                            "available:${DocumentCurationToolset.MODEL_BASE}.provider=${it.provider ?: it.name};model=${it.model}"
                        })
                    })
                val files = checkNotNull(ctx.scope.coroutineContext[FileOperations])
                val path = files.resolvePath(ctx.stateDir.absolutePath, "documents")
                files.mkdirs(path)
                val log = DocumentAppendLog.open(files.resolvePath(path, "curation.wal"), path)
                val nlp = CoreNlpRuntime()
                var curator: DocumentCuratorElement? = null
                var wire: DocumentCurationWire? = null
                try {
                    curator = DocumentCuratorElement.create(CoroutineScope(ctx.scope.coroutineContext + ctx.muxContext),
                        nlp, DocumentModel.through(ctx.muxContext, timeoutMs = 90_000, expectedBaseUrl = base),
                        model, ctx.casStore, log, bag,
                        rete = ctx.narsRete,
                        toolOntology = toolOntology,
                        observer = DocumentCuratorObserver { stage, correlation, refs ->
                            ctx.blackboard.put("document/stage/$correlation/$stage", mapOf(
                                "stage" to stage, "correlation" to correlation, "atMs" to ctx.clock(),
                                "references" to (refs α { it.value }).toList()), "document")
                        })
                    DocumentCurationWire(ctx, curator, nlp, log, panels, destination).also { wire = it; it.attach() }
                } catch (failure: Throwable) {
                    withContext(NonCancellable) {
                        try {
                            wire?.let { it.drain() } ?: curator?.drain()
                        } finally { try { nlp.close() } finally { log.close() } }
                    }
                    if (failure is CancellationException) throw failure
                    ctx.routes.release("documents")
                    unavailable(ctx, "Document curator could not start: ${failure.message ?: failure::class.simpleName}")
                }
            }

        /** Reduced startup retains a read-only status surface, not a substitute curator or runner. */
        private fun unavailable(ctx: ModuleContext, reason: String): DocumentCurationWire? {
            ctx.blackboard.put("lcnc/readiness/${DocumentCurationLegos.CURATE}", mapOf(
                "available" to false, "reason" to reason,
                "prerequisites" to listOf("belief bag", "CoreNLP guest module", "configured reachable chat model")), "documents")
            for (path in s_["/api/documents", "/api/documents/curate"].view)
                ctx.routes.claim("documents", path) { method, target, _, _ ->
                    val read = method == "GET" && target.substringBefore('?') == "/api/documents"
                    val query = CouchHttpSurface.parseQuery(target.substringAfter('?', ""))
                    if (read && query["cid"] != null) {
                        val response = runCatching {
                            val cid = ContentId(query.getValue("cid"))
                            val record = DocumentCuratorCodec.decode(requireNotNull(ctx.casStore.get(cid)) { "Unknown retained curation" })
                            if (query["view"] == "sheet" && query.containsKey("cursor")) DocumentCurationLegos.sheet(cid, record, query)
                            else if (query["view"] == "sheet") DocumentCurationLegos.output(cid, record)["sheets"]
                            else DocumentCurationLegos.output(cid, record)
                        }
                        return@claim WireHttpResponse(if (response.isSuccess) 200 else 400,
                            jsonOf(response.getOrElse { mapOf("error" to it.message) }), "application/json; charset=utf-8")
                    }
                    val submit = method == "POST" && target.substringBefore('?') == "/api/documents/curate"
                    val status = when {
                        read && !CouchHttpSurface.parseQuery(target.substringAfter('?', "")).containsKey("cid") -> 200
                        read || submit -> 503
                        else -> 405
                    }
                    WireHttpResponse(status, jsonOf(mapOf("available" to false,
                        "error" to if (status == 405) "method_not_allowed" else reason)),
                        "application/json; charset=utf-8")
                }
            return null
        }
    }

    val available: Boolean get() = "corenlp" in GuestModules.installed()
    private val requests = HeadhunterRequests(CoroutineScope(ctx.scope.coroutineContext + ctx.muxContext + fileIoContext))
    private var runner: LcncNodeRunner? = null

    private suspend fun attach() {
        for (receipt in curator.receipts().view) publish(receipt.a, receipt.b)
        val bound = boundLcnc(curator) { owner, node, inputs ->
            val raw = inputs["source"] ?: inputs["source?"]
            val source = DocumentCurationLegos.retainSource(ctx.casStore, raw)
            val output = DocumentCurationLegos.curate(owner, DocumentCurationLegos::reference).execute(node, inputs + ("source" to source))
            val cid = ContentId(output.getValue("receiptCid") as String)
            val record = checkNotNull(owner.record(cid))
            publish(cid, record)
            output
        }
        runner = bound
        ctx.lcncRunners[DocumentCurationLegos.CURATE] = bound
        ctx.blackboard.put("lcnc/readiness/${DocumentCurationLegos.CURATE}", mapOf(
            "available" to true, "reason" to null, "prerequisites" to emptyList<String>()), "documents")
        for (path in s_["/api/documents", "/api/documents/curate"].view)
            ctx.routes.claim("documents", path, route = ::route)
        ensureProgram(program(PROGRAM))
        ensureProgram(program(PROFILE_PROGRAM, HeadhunterCuration.INSTRUCTIONS))
    }

    suspend fun curate(source: DocumentSource, instructions: String): DocumentCurationResult {
        val program = checkNotNull(ctx.programLoader(PROFILE_PROGRAM)) { "Curation composition is unavailable" }
        val node = program.nodes.view.singleOrNull { it.type == DocumentCurationLegos.CURATE }
        val input = program.nodes.view.singleOrNull { it.type == LcncContracts.SCOPE_IN && it.params["name"] == "source" }
        require(node != null && input != null && node.params["instructions"] == instructions &&
            program.wires.view.any { it.fromNode == input.id && it.toNode == node.id && it.toPort.removeSuffix("?") == "source" }) {
            "The curation composition was edited; run its selected source atoms from the canvas"
        }
        val cidAtAdmission = ContentId.of(LcncProgramConfix.toJson(program).encodeToByteArray()).value
        val run = run(PROFILE_PROGRAM, DocumentCuratorCodec.source(source), cidAtAdmission)
        val cid = ContentId((run["returns"] as Map<*, *>)["receiptCid"] as String)
        return DocumentCurationResult(cid, checkNotNull(curator.record(cid)), emptySeriesOf())
    }

    private suspend fun ensureProgram(program: LcncProgram) {
        val path = "/api/panels/${program.name}"
        if (panels.route("GET", path, "", null)?.status == 200) return
        check(panels.route("POST", path, LcncProgramConfix.toJson(program), null)?.status == 200) {
            "Could not persist ${program.name} composition"
        }
    }

    private suspend fun run(program: String, source: Map<String, Any?>, expectedProgramCid: String? = null): Map<String, Any?> {
        val route = ctx.routes.match("/api/lcnc/run") ?: error("LCNC execution is unavailable")
        val response = route.route("POST", "/api/lcnc/run", jsonOf(mapOf(
            "program" to program, "inputs" to mapOf("source" to (source - "text")), "timeoutMs" to 120_000,
            "expectProgramCid" to expectedProgramCid)), null)
            ?: error("LCNC returned no document run")
        val run = reifyMap(response.body)
        check(response.status == 200 && run["error"] == null) { run["error"]?.toString() ?: "Document composition failed" }
        return run
    }

    /**
     * Tuple projection and belief intake are siblings after grounding, not a fictitious serial edge.
     * The whole provenance of the record (proposals, stage receipts, rule candidates, the model
     * receipt) is one [DocumentCurationFacts.facts] series swapped under `curationCid`; RDF and
     * KIF follow from the plane. The board gets the summary key and one key per stage receipt.
     */
    private suspend fun publish(cid: ContentId, record: DocumentCurationRecord) {
        val sourceId = record.source.metadata["evidenceId"]?.singleOrNull()
        val sourceCid = record.source.metadata["evidenceCid"]?.singleOrNull()
        val head = DocumentCurationFacts.SourceHead(sourceId, sourceCid, sourceCurrent(sourceId, sourceCid))
        val facts = DocumentCurationFacts.facts(cid, record, head)
        ctx.rete.replace(BlackboardContext(DocumentCurationFacts.PARTITION), "curationCid" j cid.value) { facts }
        ctx.blackboard.putIf(DocumentCurationFacts.summaryKey(cid), DocumentCurationFacts.summary(cid, record),
            DocumentCurationFacts.ACTOR) { old -> old == null }
        for (receipt in record.toolReceipts.view) {
            val value = DocumentCurationFacts.stageValue(cid, record, receipt)
            ctx.blackboard.putIf(DocumentCurationFacts.stageKey(record, receipt), value, DocumentCurationFacts.ACTOR) { old ->
                (old as? Map<*, *>)?.get("receiptCid") != value["receiptCid"]
            }
        }
    }

    private fun sourceCurrent(id: String?, cid: String?): Boolean? {
        if (id == null) return null
        val head = ctx.blackboard.snapshot().values["headhunter/$id"] as? Map<*, *> ?: return false
        return head["cid"] == cid && (head["fields"] as? Map<*, *>)?.get("deleted") != true
    }

    /** Re-evaluate applicability from current source heads; historical tuples and receipts remain retained. */
    suspend fun sourceChanged(id: String) {
        for (fact in ctx.rete.query(BlackboardContext("documents"), "sourceRecordId" j id).view) {
            val next = fact.fields + ("sourceCurrent" to sourceCurrent(id, fact.fields["sourceRecordCid"] as? String))
            if (next != fact.fields) ctx.rete.modify(fact.factId, next, PlaneFacts.versionOf(next))
        }
    }

    suspend fun view(cid: ContentId, retained: DocumentCurationRecord? = null): Map<String, Any?> {
        val record = retained ?: curator.record(cid) ?: error("Unknown document curation receipt")
        record.source.metadata["evidenceId"]?.singleOrNull()?.let { sourceChanged(it) }
        val tuples = ctx.rete.query(BlackboardContext("documents"), "curationCid" j cid.value)
        val semantic = (record.submittedReceiptCids α { it.value }).view.toSet()
        val quotations = (record.quotationSubmittedReceiptCids α { it.value }).view.toSet()
        val submitted = semantic + quotations
        val beliefs = ctx.beliefBag!!.snapshot().values.filter { signal ->
            signal.provenanceCid in submitted
        }
        return DocumentCurationLegos.output(cid, record) + mapOf(
            "tuples" to (tuples α { it.fields + mapOf("factId" to listOf(it.factId.a, it.factId.b), "versionCid" to it.versionCid.value) }).toList(),
            "beliefs" to beliefs.map { signal -> mapOf("angular" to signal.angular.toString(),
                "subjectCid" to signal.subjectCid, "objectCid" to signal.objectCid,
                "provenanceCid" to signal.provenanceCid, "relation" to signal.relation.name,
                "admission" to if (signal.provenanceCid in quotations) "source-quotation" else "source-attributed-clause",
                "evidence" to mapOf("positive" to signal.evidence.positive, "negative" to signal.evidence.negative),
                "expression" to ctx.beliefBag.glossOf(signal.angular)) },
            "destination" to destination,
        )
    }

    private suspend fun route(method: String, path: String, text: String, respond: (suspend (ByteArray) -> Unit)?): WireHttpResponse {
        return try {
            requests.call {
                val query = CouchHttpSurface.parseQuery(path.substringAfter('?', ""))
                when {
                    method == "GET" && path.substringBefore('?') == "/api/documents" -> {
                        val cid = query["cid"]
                        json(if (cid != null && query["view"] == "sheet") {
                            val receipt = ContentId(cid)
                            val record = curator.record(receipt) ?: error("Unknown document curation receipt")
                            if (query.containsKey("cursor")) DocumentCurationLegos.sheet(receipt, record, query)
                            else DocumentCurationLegos.output(receipt, record)["sheets"]
                        } else if (cid != null) view(ContentId(cid)) else mapOf(
                            "available" to available, "destination" to destination,
                            "error" to if (available) null else "The CoreNLP guest module is not installed; document curation is unavailable.",
                            "records" to (curator.receipts() α { mapOf("receiptCid" to it.a.value,
                                "name" to it.b.source.name, "correlation" to it.b.source.correlation,
                                "originalCid" to it.b.source.originalCid.value) }).toList()))
                    }
                    method == "POST" && path.substringBefore('?') == "/api/documents/curate" -> {
                        require(text.length <= 4 * 1024 * 1024) { "Document request exceeds 4 MiB" }
                        val input = reifyMap(text.substringAfter("\r\n\r\n", text))
                        val source = DocumentCurationLegos.retainSource(ctx.casStore, input["source"])
                        val body = DocumentCuratorCodec.source(source)
                        val program = input["program"] as? String ?: PROGRAM
                        require(program in setOf(PROGRAM, PROFILE_PROGRAM)) { "Choose a document curation composition" }
                        val run = run(program, body)
                        val cid = ContentId((run["returns"] as Map<*, *>)["receiptCid"] as String)
                        json(view(cid) + mapOf("run" to run))
                    }
                    else -> json(mapOf("error" to "method_not_allowed"), 405)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: IllegalArgumentException) { json(mapOf("error" to failure.message), 400) }
        catch (failure: Exception) { json(mapOf("error" to (failure.message ?: "Document curation failed")), 503) }
    }

    suspend fun stopAdmission() {
        ctx.routes.release("documents")
        ctx.blackboard.put("lcnc/readiness/${DocumentCurationLegos.CURATE}", mapOf(
            "available" to false, "reason" to "Document curator is draining", "prerequisites" to listOf("running document curator")), "documents")
        requests.drain()
    }

    suspend fun drain() {
        stopAdmission()
        if (ctx.lcncRunners[DocumentCurationLegos.CURATE] === runner) ctx.lcncRunners.remove(DocumentCurationLegos.CURATE)
        curator.drain()
    }

    fun close() { try { nlp.close() } finally { log.close() } }

    private fun json(value: Any?, status: Int = 200) = WireHttpResponse(status, jsonOf(value), "application/json; charset=utf-8")
}
