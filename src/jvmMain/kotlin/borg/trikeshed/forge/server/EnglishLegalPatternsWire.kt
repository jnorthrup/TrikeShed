package borg.trikeshed.forge.server

import borg.trikeshed.job.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.WireHttpResponse
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.nlp.EnglishLegalPatterns
import borg.trikeshed.parse.*
import borg.trikeshed.rdf.EnglishObservationGraph
import borg.trikeshed.userspace.nio.file.spi.fileIoContext
import kotlinx.coroutines.*

/** Explicit inspection of retained parser roles and number language, never legal applicability or admission. */
class EnglishLegalPatternsWire(private val ctx: ModuleContext, private val corpus: ProjectCorpus) {
    private val requests = HeadhunterRequests(CoroutineScope(ctx.scope.coroutineContext + ctx.muxContext.minusKey(Job) + fileIoContext))
    private val queries = EnglishLegalQueryWire(ctx, corpus, this)

    class Retained internal constructor(val snapshot: EnglishObservationGraph.Snapshot,
        val cid: ContentId, val payload: Map<String, Any?>, val response: String)
    private val runner = LcncNodeRunner { node, inputs ->
        fun parameter(key: String): Any? = if (key in inputs) inputs[key] else node.params[key]
        val reply = submit(jsonOf(_m["receiptCid" j parameter("receiptCid"), "mode" j parameter("mode")]))
        val result = reifyStrict(reply.body) as? Map<*, *> ?: error("Invalid legal language candidate response")
        require(reply.status == 200) { result["error"]?.toString() ?: "Legal language inspection refused" }
        @Suppress("UNCHECKED_CAST")
        result as Map<String, Any?>
    }

    fun attach() { ctx.routes.claim(OWNER, PATH, route = ::route); ctx.lcncRunners[TYPE] = runner; queries.attach() }
    suspend fun drain() {
        ctx.routes.release(OWNER)
        if (ctx.lcncRunners[TYPE] === runner) ctx.lcncRunners.remove(TYPE)
        // Accepted query work may still need this owner's admission; drain the child first.
        queries.drain()
        requests.drain()
    }
    private fun json(value: Any?, status: Int = 200) = WireHttpResponse(status, jsonOf(value), "application/json; charset=utf-8")
    internal suspend fun route(method: String, path: String, text: String, respond: (suspend (ByteArray) -> Unit)?): WireHttpResponse {
        if (path.substringBefore('?') != PATH) return json(_m["error" j "not_found"], 404)
        if (method != "POST") return json(_m["error" j "method_not_allowed"], 405)
        return submit(text.substringAfter("\r\n\r\n", text))
    }
    private suspend fun submit(body: String): WireHttpResponse = try {
        val request = DocumentReviewWire.taxonomyRequest(body)
        require(request.keys == _s["receiptCid", "mode"]) { "Legal language inspection requires exactly receiptCid and mode" }
        val cid = ContentId(request["receiptCid"] as? String ?: error("English inspection receiptCid required"))
        val mode = request["mode"] as? String ?: error("Explicit legal language source mode required")
        val retained = inspect(cid, mode)
        WireHttpResponse(200, retained.response, "application/json; charset=utf-8")
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: UnsupportedOperationException) { json(_m["error" j failure.message], 503) }
    catch (failure: Exception) { json(_m["error" j (failure.message ?: "Legal language inspection refused")], 409) }

    /** Exactly one admission. The query owner has a separate channel and never recursively submits here. */
    suspend fun inspect(cid: ContentId, mode: String): Retained = requests.call { compute(cid, mode) }

    private suspend fun compute(cid: ContentId, mode: String): Retained {
        require(mode in _s["snapshot", "current"]) { "Legal language source mode must be snapshot or current" }
        val snapshot = EnglishObservationGraph.read(ctx.casStore, cid)
        EnglishObservationCurrent.check(corpus, s_[snapshot], mode)
        val range = EnglishObservationGraph.map(snapshot.inventory["range"])
        val patterns = EnglishLegalPatterns.inspect(snapshot.source, snapshot.parsed,
            EnglishObservationGraph.integer(range["begin"]), EnglishObservationGraph.integer(range["end"]))
        val unresolved = mutableListOf("parser-execution-not-reverified", "producer-authority-not-established", "legal-meaning-not-established")
        if (snapshot.legacyIdentity || (EnglishObservationGraph.map(snapshot.inventory["coverage"])["parserNodeIdentityReported"] != true))
            unresolved.add("parser-dependency-node-identity-unreported")
        val payload = patterns + _m["inspectionCid" j cid.value, "parserExecution" j "not-reverified",
            "producerAuthority" j "not-established", "countsAreEvidenceConfidence" j false, "unresolved" j unresolved,
            "source" j (snapshot.descriptor(mode) + _m["currentness" j
                if (mode == "current") "checked-before-and-after-inspection" else "not-checked"])]
        val bytes = CanonicalCbor.encodeMap(payload)
        require(bytes.size <= MAX_RESPONSE) { "Legal language candidate receipt exceeds 4MiB; inspect a smaller text range" }
        val receiptCid = ContentId.of(bytes)
        val response = jsonOf(payload + _m["receiptCid" j receiptCid.value])
        require(response.encodeToByteArray().size <= MAX_RESPONSE) { "Legal language candidate JSON exceeds 4MiB; inspect a smaller text range" }
        EnglishObservationCurrent.check(corpus, s_[snapshot], mode)
        require(ctx.casStore.put(bytes) == receiptCid) { "Legal language candidate retention failed" }; ctx.casStore.sync()
        val readback = (ctx.casStore as? BoundedCasReader)?.getBounded(receiptCid, MAX_RESPONSE)
            ?: throw UnsupportedOperationException("Bounded legal language candidate readback unavailable")
        require(readback.contentEquals(bytes) && ContentId.of(readback) == receiptCid) { "Legal language candidate receipt identity mismatch" }
        EnglishObservationCurrent.check(corpus, s_[snapshot], mode)
        return Retained(snapshot, receiptCid, payload, response)
    }

    companion object {
        const val TYPE = EnglishLegalPatternsContract.TYPE
        const val OWNER = "document-english-legal-patterns"
        const val PATH = "/api/documents/english/legal-patterns"
        const val MAX_RESPONSE = 4 * 1024 * 1024
    }
}
