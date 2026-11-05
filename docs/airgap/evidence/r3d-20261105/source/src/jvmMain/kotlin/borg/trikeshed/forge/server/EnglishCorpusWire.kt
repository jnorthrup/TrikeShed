package borg.trikeshed.forge.server

import borg.trikeshed.job.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.WireHttpResponse
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.nlp.*
import borg.trikeshed.parse.*
import borg.trikeshed.rdf.EnglishObservationGraph
import borg.trikeshed.userspace.nio.file.spi.fileIoContext
import kotlinx.coroutines.*

/** Source-pinned descriptive statistics. No shared last-corpus cache or reasoning writes. */
class EnglishCorpusWire(private val ctx: ModuleContext, private val corpus: ProjectCorpus) {
    private val requests = HeadhunterRequests(CoroutineScope(ctx.scope.coroutineContext + ctx.muxContext.minusKey(Job) + fileIoContext))
    private val runner = LcncNodeRunner { node, inputs ->
        fun queryParameter(): Any? {
            if ("query" in inputs) return inputs["query"]
            if ("query?" in inputs) return inputs["query?"]
            val raw = node.params["in:query"] ?: node.params["query"] ?: return null
            require(raw.encodeToByteArray().size <= 65_536) { "English corpus parameter limit" }
            return reifyStrict(raw)
        }
        fun parameter(key: String): Any? = if (key == "view") lcncInputValue(node, inputs, key)
        else if (key in inputs) inputs[key]
        else if (key == "topics" && "topics?" in inputs) inputs["topics?"] else node.params[key]?.let { raw ->
            if (key == "mode") raw else {
                require(raw.encodeToByteArray().size <= 65_536) { "English corpus parameter limit" }; reifyStrict(raw)
            }
        }
        val request = _m["receiptCids" j parameter("receiptCids"), "mode" j parameter("mode"), "topics" j parameter("topics")] +
            if ("view" in inputs || "view?" in inputs || "in:view" in node.params || "view" in node.params)
                _m["view" j parameter("view")] else emptyMap()
        val fullRequest = request + if ("query" in inputs || "query?" in inputs || "in:query" in node.params || "query" in node.params)
            _m["query" j queryParameter()] else emptyMap()
        val reply = submit(jsonOf(fullRequest))
        val output = reifyStrict(reply.body) as? Map<*, *> ?: error("Invalid English corpus result")
        require(reply.status == 200) { output["error"]?.toString() ?: "English corpus request refused" }
        @Suppress("UNCHECKED_CAST")
        output as Map<String, Any?>
    }

    fun attach() { ctx.routes.claim(OWNER, PATH, route = ::route); ctx.lcncRunners[TYPE] = runner }
    suspend fun drain() {
        ctx.routes.release(OWNER)
        if (ctx.lcncRunners[TYPE] === runner) ctx.lcncRunners.remove(TYPE)
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
        val required = _s["receiptCids", "mode", "topics"]
        require(request.keys.containsAll(required) && request.keys.all { it in required || it == "view" || it == "query" }) {
            "English corpus requires receiptCids, mode and topics, with optional view and query"
        }
        val view = if ("view" in request) EnglishCorpusView.parse(request["view"]) else EnglishCorpusView.ALL_LEXICAL
        val ids = request["receiptCids"] as? List<*> ?: error("English receiptCids array required")
        require(ids.size in 1..MAX_RECEIPTS && ids.all { it is String } && ids.toSet().size == ids.size) { "Select 1..32 distinct English receipts" }
        val mode = request["mode"] as? String ?: error("English corpus mode required")
        require(mode in _s["snapshot", "current"]) { "English corpus mode must be snapshot or current" }
        val config = request["topics"]?.let(::topics)
        val lexicalQuery = if ("query" in request) query(request["query"]) else null
        val snapshots = ids.map { ContentId(it as String) }.sortedBy { it.value }.map { EnglishObservationGraph.read(ctx.casStore, it) }.toSeries()
        EnglishObservationCurrent.check(corpus, snapshots, mode)
        val core = EnglishCorpus.build(snapshots α { observation(it) }, view.policy())
        val model = config?.let(core::lda)
        val coreMap = core.toMap(); val matrix = core.matrix(); val topicMap = model?.toMap()
        val payload = coreMap + _m["corpusCid" j core.cid.value,
            "receiptCids" j (snapshots α { it.receiptCid.value }).toList(), "topics" j topicMap, "topicCid" j model?.cid?.value,
            "source" j _m["selected" j "english-receipt-corpus", "mode" j mode, "historical" j (mode == "snapshot"),
                "currentness" j if (mode == "current") "checked-before-and-after-build" else "not-checked",
                "reviewStatus" j "unreviewed", "parserExecution" j "not-reverified", "producerAuthority" j "not-established",
                "sources" j (snapshots α { it.descriptor(mode) }).toList()]] +
            if ("view" in request) _m["view" j view.wire] else emptyMap()
        val result = payload + if (lexicalQuery != null) _m["search" j _m[
            "schema" j "trikeshed.english-corpus-search.v1", "dataset" j "english-selected-corpus-retrieval",
            "corpusCid" j core.cid.value, "matrixCid" j core.matrixCid.value,
            "receiptCids" j payload["receiptCids"], "policy" j core.policy.toMap(),
            "query" j _m["terms" j lexicalQuery.a, "limit" j lexicalQuery.b], "retrieval" j coreMap["retrieval"],
            "source" j payload["source"], "authority" j "lexical-match-only", "countsAreEvidenceConfidence" j false,
            "hits" j (core.search(lexicalQuery.a.toSeries(), lexicalQuery.b) α { it.toMap() }).toList()]] else emptyMap()
        val encoded = CanonicalCbor.encodeMap(result)
        require(encoded.size <= MAX_RESPONSE) { "English corpus exceeds 4MiB; select fewer or shorter ranges" }
        val receiptCid = ContentId.of(encoded)
        val response = jsonOf(result + _m["receiptCid" j receiptCid.value])
        require(response.encodeToByteArray().size <= MAX_RESPONSE) { "English corpus JSON exceeds 4MiB; select fewer or shorter ranges" }
        EnglishObservationCurrent.check(corpus, snapshots, mode)
        retain(CanonicalCbor.encodeMap(matrix), core.matrixCid)
        retain(CanonicalCbor.encodeMap(coreMap), core.cid)
        if (model != null) retain(CanonicalCbor.encodeMap(requireNotNull(topicMap)), model.cid)
        retain(encoded, receiptCid)
        EnglishObservationCurrent.check(corpus, snapshots, mode)
        WireHttpResponse(200, response, "application/json; charset=utf-8")
    } } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: UnsupportedOperationException) { json(_m["error" j failure.message], 503) }
    catch (failure: Exception) { json(_m["error" j (failure.message ?: "English corpus request refused")], 409) }

    private fun retain(bytes: ByteArray, expected: ContentId) {
        require(bytes.size <= MAX_RESPONSE && ContentId.of(bytes) == expected) { "Derived English receipt identity or size mismatch" }
        require(ctx.casStore.put(bytes) == expected) { "Derived English receipt retention failed" }; ctx.casStore.sync()
        val reader = ctx.casStore as? BoundedCasReader ?: throw UnsupportedOperationException("Bounded English corpus readback unavailable")
        require(reader.getBounded(expected, MAX_RESPONSE)?.contentEquals(bytes) == true) { "Derived English receipt readback failed" }
    }

    internal fun observation(snapshot: EnglishObservationGraph.Snapshot): EnglishObservationReceipt {
        val range = EnglishObservationGraph.map(snapshot.inventory["range"])
        val rows = EnglishObservationGraph.list(snapshot.inventory["sentences"]).toSeries()
        return EnglishObservationReceipt(snapshot.receiptCid, snapshot.source.originalCid, snapshot.source.extractedTextCid,
            EnglishObservationGraph.integer(range["begin"]), EnglishObservationGraph.integer(range["end"]),
            EnglishObservationGraph.integer(range["totalLength"]), snapshot.selection,
            rows α { raw -> val sentence = EnglishObservationGraph.map(raw)
                val tokens = EnglishObservationGraph.list(sentence["tokens"]).toSeries()
                EnglishObservationSentence(EnglishObservationGraph.integer(sentence["index"]), EnglishObservationGraph.integer(sentence["begin"]),
                    EnglishObservationGraph.integer(sentence["end"]), EnglishObservationGraph.string(sentence["quote"]),
                    tokens α { rawToken -> val token = EnglishObservationGraph.map(rawToken)
                        EnglishObservationToken(EnglishObservationGraph.integer(token["index"]), EnglishObservationGraph.integer(token["begin"]),
                            EnglishObservationGraph.integer(token["end"]), EnglishObservationGraph.string(token["word"]),
                            EnglishObservationGraph.string(token["lemma"]), EnglishObservationGraph.string(token["pos"]))
                    })
            })
    }

    private fun query(value: Any?): Join<List<String>, Int> {
        val fields = value as? Map<*, *> ?: error("English corpus query requires terms and limit; explicit null is invalid")
        require(fields.keys == _s["terms", "limit"]) { "English corpus query requires exactly terms and limit" }
        val raw = fields["terms"] as? List<*> ?: error("English corpus query terms array required")
        require(raw.size in 1..64 && raw.all { it is String && it.isNotBlank() }) { "Select 1..64 nonblank reported lemma strings" }
        val terms = raw.map { it as String }
        terms.forEach(EnglishCorpus::field)
        val limit = (fields["limit"] as? Number)?.toDouble() ?: error("English corpus query limit integer required")
        require(limit.isFinite() && limit % 1.0 == 0.0 && limit in 1.0..256.0) { "English corpus query limit must be 1..256" }
        return terms j limit.toInt()
    }

    private fun topics(value: Any): EnglishLdaConfig {
        val fields = value as? Map<*, *> ?: error("English topics must be null or an explicit configuration")
        require(fields.keys == _s["topics", "iterations", "seed", "alpha", "beta"]) { "English topics requires topics, iterations, seed, alpha and beta" }
        fun integer(key: String): Int {
            val n = (fields[key] as? Number)?.toDouble() ?: error("LDA $key integer required")
            require(n.isFinite() && n >= Int.MIN_VALUE && n <= Int.MAX_VALUE && n % 1.0 == 0.0) { "Invalid LDA integer" }
            return n.toInt()
        }
        fun decimal(key: String) = (fields[key] as? Number)?.toDouble() ?: error("LDA $key number required")
        return EnglishLdaConfig(integer("topics"), integer("iterations"), integer("seed"), decimal("alpha"), decimal("beta"))
    }
    companion object {
        const val TYPE = "document.english.corpus"
        const val OWNER = "document-english-corpus"
        const val PATH = "/api/documents/english/corpus"
        const val MAX_RECEIPTS = 32
        const val MAX_RESPONSE = 4 * 1024 * 1024
    }
}
