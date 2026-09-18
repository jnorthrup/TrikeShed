package borg.trikeshed.forge.server

import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.dag.PlaneFacts
import borg.trikeshed.graal.subvm.CoreNlpRuntime
import borg.trikeshed.graal.subvm.CamelCatalog
import borg.trikeshed.graal.subvm.GuestModules
import borg.trikeshed.job.ContentId
import borg.trikeshed.jules.BrainClient
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.WireHttpResponse
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.narsese.*
import borg.trikeshed.parse.json.JsonSupport
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
            nodes.add(LcncNode("source", LcncContracts.SCOPE_IN, mapOf("name" to "source", "kind" to "json"), 40.0, 80.0))
            nodes.add(LcncNode("curate", DocumentCurationLegos.CURATE, instructions?.let { mapOf("instructions" to it) }.orEmpty(), 350.0, 80.0))
            wires.add(LcncWire("source", "value", "curate", "source"))
            if (instructions != null) {
                nodes.add(LcncNode("profile", "headhunter.profile", x = 660.0, y = 80.0))
                nodes.add(LcncNode("knowledge", LcncContracts.SCOPE_OUT, mapOf("name" to "profile", "kind" to "json"), 970.0, 80.0))
                wires.add(LcncWire("curate", "receiptCid", "profile", "receiptCid"))
                wires.add(LcncWire("profile", "profile", "knowledge", "value"))
            }
            for ((index, port) in s_["receiptCid", "record", "sheet", "sheets"].view.withIndex()) {
                nodes.add(LcncNode(port, LcncContracts.SCOPE_OUT, mapOf("name" to port, "kind" to if (port == "receiptCid") "id" else "json"), 970.0, 240.0 + index * 160))
                wires.add(LcncWire("curate", port, port, "value"))
            }
            return LcncProgram(name, nodes.drain(), wires.drain())
        }

        suspend fun open(ctx: ModuleContext, brain: BrainClient, panels: PatchWire): DocumentCurationWire? =
            withContext(ctx.muxContext + fileIoContext) {
                val bag = ctx.beliefBag ?: return@withContext null
                val mux = brain.modelMux()
                val model = mux.defaultModel ?: brain.lastModel() ?: brain.endpointSummaries().firstOrNull()?.model
                    ?: return@withContext null
                val session = mux.session(model).getOrThrow()
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
                    DocumentCurationWire(ctx, curator, nlp, log, panels, destination).also { it.attach() }
                } catch (failure: Throwable) {
                    withContext(NonCancellable) {
                        try { curator?.drain() } finally { try { nlp.close() } finally { log.close() } }
                    }
                    throw failure
                }
            }
    }

    val available: Boolean get() = "corenlp" in GuestModules.installed()
    private val requests = HeadhunterRequests(CoroutineScope(ctx.scope.coroutineContext + ctx.muxContext + fileIoContext))

    private suspend fun attach() {
        for (receipt in curator.receipts().view) publish(receipt.a, receipt.b)
        ctx.lcncRunners[DocumentCurationLegos.CURATE] = boundLcnc(curator) { owner, node, inputs ->
            val raw = inputs["source"] ?: inputs["source?"]
            val source = (raw as? Map<*, *>)?.let { fields ->
                if (fields["text"] != null) fields else {
                    val cid = ContentId(fields["extractedTextCid"] as? String ?: error("Extracted text CID is required"))
                    val bytes = ctx.casStore.get(cid) ?: error("Retained extracted text is unavailable")
                    check(ContentId.of(bytes) == cid) { "Extracted text CID mismatch" }
                    fields + ("text" to bytes.decodeToString())
                }
            } ?: raw
            val output = DocumentCurationLegos.curate(owner, DocumentCurationLegos::reference).execute(node, inputs + ("source" to source))
            val cid = ContentId(output.getValue("receiptCid") as String)
            val record = checkNotNull(owner.record(cid))
            publish(cid, record)
            output
        }
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
        val response = route.route("POST", "/api/lcnc/run", JsonSupport.stringify(mapOf(
            "program" to program, "inputs" to mapOf("source" to (source - "text")), "timeoutMs" to 120_000,
            "expectProgramCid" to expectedProgramCid)), null)
            ?: error("LCNC returned no document run")
        val run = JsonSupport.parseMap(response.body)
        check(response.status == 200 && run["error"] == null) { run["error"]?.toString() ?: "Document composition failed" }
        return run
    }

    /** Tuple projection and belief intake are siblings after grounding, not a fictitious serial edge. */
    private suspend fun publish(cid: ContentId, record: DocumentCurationRecord) {
        val tuples = record.proposals.filter { it.subject != null && it.predicate != null && it.obj != null }
        val sourceId = record.source.metadata["evidenceId"]?.singleOrNull()
        val sourceCid = record.source.metadata["evidenceCid"]?.singleOrNull()
        val facts = tuples.size j { ordinal: Int ->
            val proposal = tuples[ordinal]
            val tuple: Join<String, Twin<String>> = proposal.predicate!! j (proposal.subject!! j proposal.obj!!)
            val values = mapOf("kind" to "document-proposal", "curationCid" to cid.value,
                "originalCid" to record.source.originalCid.value, "textCid" to record.source.extractedTextCid.value,
                "sourceRecordId" to sourceId, "sourceRecordCid" to sourceCid,
                "sourceCurrent" to sourceCurrent(sourceId, sourceCid),
                "proposalCid" to proposal.receiptCid?.value, "predicate" to tuple.a,
                "subject" to tuple.b.a, "object" to tuple.b.b, "quote" to proposal.quote,
                "begin" to proposal.begin, "end" to proposal.end,
                "quotationReceiptCid" to proposal.quotationReceiptCid?.value,
                "quotationBegin" to proposal.quotationBegin, "quotationEnd" to proposal.quotationEnd,
                "groundingReasons" to proposal.reasons.toList(),
                "submitted" to (proposal.receiptCid in record.submittedReceiptCids.view),
                "quotationSubmitted" to (proposal.quotationReceiptCid in record.quotationSubmittedReceiptCids.view))
            PlaneFacts.fact("documents", "${cid.hex}/$ordinal", values)
        }
        ctx.rete.replace(BlackboardContext("documents"), "curationCid" j cid.value) { facts }
        ctx.blackboard.putIf("document/curation/${cid.hex}", mapOf("receiptCid" to cid.value,
            "name" to record.source.name, "originalCid" to record.source.originalCid.value,
            "textCid" to record.source.extractedTextCid.value, "model" to record.model?.modelId,
            "proposals" to record.proposals.size, "submitted" to record.submittedReceiptCids.size,
            "quotationsSubmitted" to record.quotationSubmittedReceiptCids.size,
            "reasons" to record.reasons.toList()), "document") { old -> old == null }
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
                            DocumentCurationLegos.output(receipt, record)["sheets"]
                        } else if (cid != null) view(ContentId(cid)) else mapOf(
                            "available" to available, "destination" to destination,
                            "records" to (curator.receipts() α { mapOf("receiptCid" to it.a.value,
                                "name" to it.b.source.name, "correlation" to it.b.source.correlation,
                                "originalCid" to it.b.source.originalCid.value) }).toList()))
                    }
                    method == "POST" && path.substringBefore('?') == "/api/documents/curate" -> {
                        require(text.length <= 4 * 1024 * 1024) { "Document request exceeds 4 MiB" }
                        val input = JsonSupport.parseMap(text.substringAfter("\r\n\r\n", text))
                        val source = input["source"] as? Map<*, *> ?: error("source is required")
                        val body = source.entries.associate { it.key as String to it.value }.toMutableMap()
                        // New text is an original; retained documents must supply both matching CIDs.
                        if (body["originalCid"] == null) {
                            val bytes = (body["text"] as? String ?: error("source.text is required")).encodeToByteArray()
                            val cid = ctx.casStore.put(bytes).value
                            body["originalCid"] = cid; body["extractedTextCid"] = cid
                        }
                        body.putIfAbsent("correlation", java.util.UUID.randomUUID().toString())
                        body.putIfAbsent("mediaType", "text/plain")
                        body.putIfAbsent("metadata", emptyMap<String, Any?>())
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
        catch (failure: Exception) { json(mapOf("error" to (failure.message ?: "Document curation failed")), 503) }
    }

    suspend fun stopAdmission() {
        ctx.routes.release("documents")
        requests.drain()
    }

    suspend fun drain() {
        stopAdmission()
        curator.drain()
    }

    fun close() { try { nlp.close() } finally { log.close() } }

    private fun json(value: Any?, status: Int = 200) = WireHttpResponse(status, JsonSupport.stringify(value), "application/json; charset=utf-8")
}
