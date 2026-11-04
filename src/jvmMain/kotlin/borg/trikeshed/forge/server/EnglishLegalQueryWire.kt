package borg.trikeshed.forge.server

import borg.trikeshed.job.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.WireHttpResponse
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.parse.*
import borg.trikeshed.rdf.*
import borg.trikeshed.userspace.nio.file.spi.fileIoContext
import kotlinx.coroutines.*

/** Source-linked candidate queries only; no world facts, global graph writes or review authority. */
class EnglishLegalQueryWire(private val ctx: ModuleContext, private val corpus: ProjectCorpus,
    private val patterns: EnglishLegalPatternsWire) {
    private val requests = HeadhunterRequests(CoroutineScope(ctx.scope.coroutineContext + ctx.muxContext.minusKey(Job) + fileIoContext))
    private val runner = LcncNodeRunner { node, inputs ->
        fun parameter(key: String): Any? = if (key in inputs) inputs[key] else node.params[key]?.let { raw ->
            if (key != "program") raw else {
                require(raw.encodeToByteArray().size <= 65_536) { "Legal query program limit" }; reifyStrict(raw)
            }
        }
        val reply = submit(jsonOf(_m["inspectionCid" j parameter("inspectionCid"), "mode" j parameter("mode"), "program" j parameter("program")]))
        val result = reifyStrict(reply.body) as? Map<*, *> ?: error("Invalid legal candidate query response")
        require(reply.status == 200) { result["error"]?.toString() ?: "Legal candidate query refused" }
        @Suppress("UNCHECKED_CAST")
        result as Map<String, Any?>
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
        require(request.keys == _s["inspectionCid", "mode", "program"]) { "Legal query requires exactly inspectionCid, mode and program" }
        val cid = ContentId(request["inspectionCid"] as? String ?: error("Parent inspectionCid required"))
        val mode = request["mode"] as? String ?: error("Explicit legal query source mode required")
        require(mode in _s["snapshot", "current"]) { "Legal query source mode must be snapshot or current" }
        val program = request["program"] as? Map<*, *> ?: error("Legal query visual program required")
        val retained = patterns.inspect(cid, mode)
        EnglishObservationCurrent.check(corpus, s_[retained.snapshot], mode)
        val store = EnglishLegalGraph.project(retained.cid, cid, retained.payload, retained.snapshot.source)
        val bounded = SparqlWire(SparqlSurface(store))
        val reply = requireNotNull(bounded.route("POST", "/sparql/visual", "POST /sparql/visual HTTP/1.1\r\n\r\n" +
            jsonOf(_m["dataset" j "quad-store", "program" j program]), null))
        if (reply.status != 200) return@call reply
        EnglishObservationCurrent.check(corpus, s_[retained.snapshot], mode)
        val result = reifyStrict(reply.body) as? Map<*, *> ?: error("Invalid bounded legal query result")
        val source = retained.snapshot.descriptor(mode) + _m["selected" j "english-legal-candidate-observations",
            "receiptCid" j retained.cid.value, "inspectionCid" j cid.value, "candidateCid" j retained.cid.value,
            "namedGraph" j EnglishLegalGraph.graph(retained.cid),
            "quads" j store.size, "scope" j "recorded-parser-role-and-quantity-candidates",
            "bindingProvenance" j "candidate-receipt-resources-with-parent-inspection-and-exact-source-spans",
            "sameSentenceJoin" j "cooccurrence-only-not-applicability", "integerValueMeaning" j "bounded-lexical-reading-only",
            "missingIntegerValue" j "unresolved-or-unrepresented-not-false",
            "currentness" j if (mode == "current") "checked-before-and-after-query" else "not-checked"]
        val response = jsonOf(result + _m["dataset" j "english-legal-candidate-observations", "source" j source])
        require(response.encodeToByteArray().size <= 900_000) { "Legal candidate query response limit" }
        WireHttpResponse(200, response, "application/json; charset=utf-8")
    } } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: UnsupportedOperationException) { json(_m["error" j failure.message], 503) }
    catch (failure: Exception) { json(_m["error" j (failure.message ?: "Legal candidate query refused")], 409) }

    companion object {
        const val TYPE = EnglishLegalQueryContract.TYPE
        const val OWNER = "document-english-legal-query"
        const val PATH = "/api/documents/english/legal-patterns/query"
    }
}
