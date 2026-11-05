package borg.trikeshed.forge.server

import borg.trikeshed.job.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.WireHttpResponse
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.narsese.SyntheticNalExperiment
import borg.trikeshed.parse.*
import borg.trikeshed.rdf.*
import borg.trikeshed.userspace.nio.file.spi.fileIoContext
import kotlinx.coroutines.*

/** One verified receipt is one isolated read-only dataset; the daemon's store and live Rete are never inputs. */
class EnglishObservationWire(private val ctx: ModuleContext, private val corpus: ProjectCorpus) {
    private val requests = HeadhunterRequests(CoroutineScope(ctx.scope.coroutineContext + ctx.muxContext.minusKey(Job) + fileIoContext))
    private val statistics = EnglishCorpusWire(ctx, corpus)
    private val legalPatterns = EnglishLegalPatternsWire(ctx, corpus)
    private val runner = LcncNodeRunner { node, inputs ->
        fun parameter(key: String): Any? = if (key in inputs) inputs[key] else node.params[key]?.let { raw ->
            if (key != "program") raw else {
                require(raw.encodeToByteArray().size <= 65_536) { "English query program limit" }; reifyStrict(raw)
            }
        }
        val request = _m["receiptCid" j parameter("receiptCid"), "mode" j parameter("mode"), "program" j parameter("program")]
        val reply = submit(jsonOf(request))
        val output = reifyStrict(reply.body) as? Map<*, *> ?: error("English observation query returned invalid JSON")
        require(reply.status == 200) { output["error"]?.toString() ?: "English observation query refused" }
        @Suppress("UNCHECKED_CAST")
        output as Map<String, Any?>
    }

    /** Retained source hypotheses only; the existing queue owns currentness and lifecycle. */
    private val nalRunner = LcncNodeRunner { node, inputs ->
        val keys = _s["receiptCid", "mode"]
        require(node.params.keys.all { it in keys } && inputs.keys.all { it in keys }) {
            "Retained English NAL accepts only receiptCid and explicit mode"
        }
        fun parameter(key: String): Any? = if (key in inputs) inputs[key] else node.params[key]
        val cid = ContentId(requireNotNull(parameter("receiptCid") as? String) { "Retained English inspection CID required" })
        val mode = requireNotNull(parameter("mode") as? String) { "Explicit retained English source mode required" }
        require(mode in _s["snapshot", "current"]) { "Retained English source mode must be snapshot or current" }
        requests.call {
            val snapshot = EnglishObservationGraph.read(ctx.casStore, cid)
            EnglishObservationCurrent.check(corpus, s_[snapshot], mode)
            val report = SyntheticNalExperiment.run(snapshot)
            val passages = report.premises.view.flatMap { premise -> premise.occurrences.view.map { occurrence ->
                val span = occurrence.span
                val quotation = snapshot.source.quotation(span.begin, span.end)
                require(quotation["quote"] == span.quote) { "Retained NAL occurrence differs from its source quotation" }
                quotation + _m["inspectionCid" j cid.value, "frameCid" j report.frameCid.value,
                    "occurrenceCid" j span.occurrenceCid.value, "propositionCid" j span.propositionCid.value,
                    "annotationCid" j span.annotationCid.value, "contributionCid" j premise.contribution.cid.value,
                    "sentenceIndex" j occurrence.sentenceIndex, "sentenceIri" j occurrence.sentenceIri,
                    "subjectNodeIri" j occurrence.subjectNodeIri, "predicateNodeIri" j occurrence.predicateNodeIri,
                    "objectNodeIri" j occurrence.objectNodeIri,
                    "meaning" j "same premise occurrence; presentation only, not another evidence unit"]
            } }.sortedBy { it["begin"] as Int }
            val output = _m["report" j report.toMap(), "frameCid" j report.frameCid.value,
                "inspectionCid" j cid.value, "status" j report.status.name, "passages" j passages,
                "source" j (snapshot.descriptor(mode) + _m["currentness" j
                    if (mode == "current") "checked-before-and-after-evaluation" else "not-checked",
                    "admission" j "not-performed", "evaluation" j "read-only retained-English hypotheses"])]
            require(jsonOf(output).encodeToByteArray().size <= MAX_NAL_RESPONSE) { "Retained English NAL output exceeds 4MiB" }
            EnglishObservationCurrent.check(corpus, s_[snapshot], mode)
            output
        }
    }

    /** Read-only relation observations share this owner's bounded queue and source checks. */
    private val relationRunner = LcncNodeRunner { node, inputs ->
        val keys = _s["receiptCid", "mode", "query", "query?"]
        require(node.params.keys.all { it in _s["receiptCid", "mode", "query", "in:query"] } && inputs.keys.all { it in keys }) {
            "Retained English relations accept receiptCid, mode and optional exact query only"
        }
        require(!("query" in inputs && "query?" in inputs)) { "Relation query supplied twice" }
        fun parameter(key: String): Any? = if (key in inputs) inputs[key] else node.params[key]
        val cid = ContentId(requireNotNull(parameter("receiptCid") as? String) { "Retained inspection CID required" })
        val mode = requireNotNull(parameter("mode") as? String) { "Explicit relation source mode required" }
        require(mode in _s["snapshot", "current"])
        val hasQuery = "query" in inputs || "query?" in inputs || "query" in node.params || "in:query" in node.params
        val query = if (hasQuery) EnglishRelationFrames.request(lcncInputValue(node, inputs, "query")) else emptyMap()
        requests.call {
            val snapshot = EnglishObservationGraph.read(ctx.casStore, cid)
            EnglishObservationCurrent.check(corpus, s_[snapshot], mode)
            val report = EnglishRelationFrames.build(snapshot)
            val matches = EnglishRelationFrames.query(report, query)
            val output = _m["report" j report.value, "matches" j matches,
                "passages" j EnglishRelationFrames.passages(report, matches), "inspectionCid" j cid.value,
                "frameCid" j report.cid.value, "status" j report.value["status"],
                "source" j (snapshot.descriptor(mode) + _m["currentness" j
                    if (mode == "current") "checked-before-and-after-query" else "not-checked",
                    "admission" j "not-performed", "roleAuthority" j "recorded-parser-roles-only"])]
            require(jsonOf(output).encodeToByteArray().size <= EnglishRelationFrames.MAX_BYTES) { "Relation output limit" }
            EnglishObservationCurrent.check(corpus, s_[snapshot], mode)
            output
        }
    }

    fun attach() {
        ctx.routes.claim(OWNER, PATH, route = ::route)
        ctx.lcncRunners[TYPE] = runner
        ctx.lcncRunners[EnglishNalContract.TYPE] = nalRunner
        ctx.lcncRunners[EnglishRelationContract.TYPE] = relationRunner
        statistics.attach()
        legalPatterns.attach()
    }

    suspend fun drain() {
        ctx.routes.release(OWNER)
        statistics.drain()
        legalPatterns.drain()
        if (ctx.lcncRunners[TYPE] === runner) ctx.lcncRunners.remove(TYPE)
        if (ctx.lcncRunners[EnglishNalContract.TYPE] === nalRunner) ctx.lcncRunners.remove(EnglishNalContract.TYPE)
        if (ctx.lcncRunners[EnglishRelationContract.TYPE] === relationRunner) ctx.lcncRunners.remove(EnglishRelationContract.TYPE)
        requests.drain()
    }

    private fun json(value: Any?, status: Int = 200) = WireHttpResponse(status, jsonOf(value), "application/json; charset=utf-8")

    internal suspend fun route(method: String, path: String, text: String, respond: (suspend (ByteArray) -> Unit)?): WireHttpResponse {
        if (path.substringBefore('?') != PATH) return json(_m["error" j "not_found"], 404)
        if (method != "POST") return json(_m["error" j "method_not_allowed"], 405)
        return submit(text.substringAfter("\r\n\r\n", text))
    }

    private suspend fun submit(body: String): WireHttpResponse = try { requests.call {
        val request = DocumentReviewWire.taxonomyRequest(body)
        require(request.keys == _s["receiptCid", "mode", "program"]) { "English query requires exactly receiptCid, mode and program" }
        val cid = ContentId(request["receiptCid"] as? String ?: error("English query receiptCid required"))
        val mode = request["mode"] as? String ?: error("English query mode required")
        require(mode in _s["snapshot", "current"]) { "English query mode must be snapshot or current" }
        val program = request["program"] as? Map<*, *> ?: error("English query visual program required")
        val snapshot = EnglishObservationGraph.read(ctx.casStore, cid)
        EnglishObservationCurrent.check(corpus, s_[snapshot], mode)
        val store = EnglishObservationGraph.project(snapshot)
        // This fresh wire has no live overlay, external dataset callback or update route exposed.
        val visual = SparqlWire(SparqlSurface(store))
        val reply = requireNotNull(visual.route("POST", "/sparql/visual", "POST /sparql/visual HTTP/1.1\r\n\r\n" +
            jsonOf(_m["dataset" j "quad-store", "program" j program]), null))
        if (reply.status != 200) return@call reply
        EnglishObservationCurrent.check(corpus, s_[snapshot], mode)
        val output = reifyStrict(reply.body) as? Map<*, *> ?: error("Invalid bounded visual query result")
        val result = output + _m["dataset" j "english-receipt-observations", "source" j
            (snapshot.descriptor(mode) + _m["quads" j store.size, "currentness" j if (mode == "current") "checked-before-and-after-query" else "not-checked"])]
        val response = jsonOf(result)
        require(response.encodeToByteArray().size <= 900_000) { "English query response limit" }
        WireHttpResponse(200, response, "application/json; charset=utf-8")
    } } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: UnsupportedOperationException) { json(_m["error" j failure.message], 503) }
    catch (failure: Exception) { json(_m["error" j (failure.message ?: "English observation query refused")], 409) }

    companion object {
        const val MAX_NAL_RESPONSE = 4 * 1024 * 1024
        const val TYPE = "document.english.query"
        const val OWNER = "document-english-observations"
        const val PATH = "/api/documents/english/query"
    }
}
