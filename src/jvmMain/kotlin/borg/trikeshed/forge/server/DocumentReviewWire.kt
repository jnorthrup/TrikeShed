package borg.trikeshed.forge.server

import borg.trikeshed.job.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.narsese.*
import borg.trikeshed.parse.*
import borg.trikeshed.litebike.WireHttpResponse
import borg.trikeshed.relaxfactory.CouchHttpSurface
import borg.trikeshed.userspace.nio.file.spi.*
import kotlinx.coroutines.*

/** Host-owned review authority and retained evidence access; neither requires a model session. */
class DocumentReviewWire private constructor(
    private val ctx: ModuleContext,
    private val log: DocumentAppendLog,
    val review: CurationReview,
    private val principals: Map<String, ReviewPrincipal>,
    private val corpus: ProjectCorpus,
) {
    companion object {
        fun revision(value: Any?): Long {
            val number = (value as? Number)?.toDouble()
            require(number != null && number.isFinite() && number >= 0 && number <= 9_007_199_254_740_991.0 && number % 1 == 0.0) {
                "Review revision must be a nonnegative whole number"
            }
            return number.toLong()
        }
        suspend fun open(ctx: ModuleContext, corpus: ProjectCorpus, books: ConstellationNodes): DocumentReviewWire =
            withContext(fileIoContext) {
                val files = checkNotNull(ctx.scope.coroutineContext[FileOperations])
                val path = files.resolvePath(ctx.stateDir.absolutePath, "documents")
                files.mkdirs(path)
                val config = files.resolvePath(path, "review-principals.json")
                val principals = if (!files.exists(config)) emptyMap() else {
                    val entries = reifyMap(files.readString(config))["principals"] as? List<*>
                        ?: error("review-principals.json requires principals")
                    entries.associate { entry ->
                        val value = entry as Map<*, *>
                        val hash = value["tokenSha256"] as String
                        require(hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid reviewer token hash" }
                        hash to ReviewPrincipal(value["id"] as String, value["synthetic"] == true)
                    }.also { require(it.size == entries.size) { "Duplicate reviewer token hash" } }
                }
                val log = DocumentAppendLog.open(files.resolvePath(path, "review.wal"), path)
                val review = CurationReview(ctx.casStore, log,
                    resolve = { kind, cid, ids -> when (kind) {
                        "book" -> books.reviewCandidate(cid, ids)
                        "document" -> DocumentReviewCandidate.candidate(ctx.casStore, cid, ids)
                        else -> error("Unknown curation kind")
                    } },
                    current = { candidate ->
                        val source = when (candidate.kind) {
                            "book" -> books.reviewSource(candidate.curationReceiptCid)
                            "document" -> DocumentReviewCandidate.record(ctx.casStore, candidate.curationReceiptCid).source
                            else -> error("Unknown curation kind")
                        }
                        val retained = if (candidate.kind == "book") books.reviewCurrent(candidate) else {
                            val record = DocumentReviewCandidate.record(ctx.casStore, candidate.curationReceiptCid)
                            candidate.sourceRevision == DocumentReviewCandidate.revision(record.source) &&
                                candidate.ontologyCid == ContentId.of(CanonicalCbor.encodeMap(DocumentReviewCandidate.ontology(record)))
                        }
                        val evidenceId = source.metadata["evidenceId"]?.singleOrNull()
                        val evidence = evidenceId?.let { ctx.blackboard.snapshot().values["headhunter/$it"] as? Map<*, *> }
                        retained && DocumentCurationLegos.current(corpus, source) && (evidenceId == null ||
                            evidence?.get("cid") == source.metadata["evidenceCid"]?.singleOrNull() &&
                                (evidence?.get("fields") as? Map<*, *>)?.get("deleted") != true)
                    }, clock = ctx.clock)
                try {
                    review.open(CoroutineScope(ctx.scope.coroutineContext + fileIoContext))
                    DocumentReviewWire(ctx, log, review, principals, corpus).also {
                        it.attach()
                        books.reviewRelease = review::consume
                        ctx.documentReview = review
                    }
                } catch (failure: Throwable) {
                    withContext(NonCancellable) { review.drain() }
                    log.close()
                    throw failure
                }
            }
    }

    private val requests = HeadhunterRequests(CoroutineScope(ctx.scope.coroutineContext + fileIoContext))
    private val bindings = linkedMapOf<String, LcncNodeRunner>()

    private fun attach() {
        bindings[DocumentCurationLegos.SOURCE] = DocumentCurationLegos.source(ctx.casStore, corpus)
        bindings[DocumentCurationLegos.SHEET] = DocumentCurationLegos.sheet(ctx.casStore)
        bindings["document.reviewSnapshot"] = LcncNodeRunner { node, inputs ->
            val cid = ContentId((inputs["receiptCid"] ?: node.params["receiptCid"]) as String)
            val ids = (inputs["selection"] as? List<*>)?.map { it as String }
                ?: (reifyStrict(node.params["selection"] ?: "[]") as List<*>).map { it as String }
            review.snapshot(node.params["kind"] ?: "book", cid, ids)
        }
        bindings["document.reviewDecision"] = LcncNodeRunner { node, inputs ->
            review.decide(ContentId(inputs["snapshotCid"] as String), revision(inputs["expectedRevision"]),
                node.params["action"] ?: "draft", node.params["note"].orEmpty())
        }
        bindings["document.reviewRelease"] = LcncNodeRunner { _, inputs ->
            review.release(ContentId(inputs["snapshotCid"] as String), ContentId(inputs["approvalCid"] as String),
                revision(inputs["expectedRevision"]))
        }
        ctx.lcncRunners.putAll(bindings)
        ctx.blackboard.put("lcnc/readiness/document.reviewDecision", mapOf(
            "available" to principals.isNotEmpty(), "reason" to if (principals.isEmpty()) "No analyst principal is configured" else null,
            "prerequisites" to listOf("authenticated analyst session")), "documents")
        for (suffix in listOf("", "/session", "/snapshot", "/decision", "/release"))
            ctx.routes.claim("document-review", "/api/documents/review$suffix", route = ::route)
    }

    /** Only the HTTP header is credential input. JSON fields cannot establish a reviewer. */
    private fun principal(request: String): ReviewPrincipal? {
        val headers = request.substringBefore("\r\n\r\n", "").split("\r\n").drop(1)
        val authorization = headers.filter { it.substringBefore(':').equals("Authorization", true) }
        if (authorization.size != 1) return null
        val value = authorization.single().substringAfter(':').trim()
        if (!value.startsWith("Bearer ") || value.length > 2048) return null
        return principals[ContentId.of(value.removePrefix("Bearer ").encodeToByteArray()).hex]
    }

    private suspend fun route(method: String, path: String, text: String, respond: (suspend (ByteArray) -> Unit)?): WireHttpResponse {
        val endpoint = path.substringBefore('?')
        val actor = principal(text)
        if ((endpoint.endsWith("/decision") || endpoint.endsWith("/session") || endpoint.endsWith("/release")) && actor == null)
            return json(mapOf("error" to "authenticated_reviewer_required"), 401)
        return try {
            requests.call {
                when {
                    method == "GET" && endpoint.endsWith("/session") -> json(mapOf("reviewer" to actor!!.id, "synthetic" to actor.synthetic))
                    method == "GET" && endpoint == "/api/documents/review" -> {
                        val query = CouchHttpSurface.parseQuery(path.substringAfter('?', ""))
                        json(review.inspect(ContentId(query.getValue("snapshotCid"))))
                    }
                    method == "POST" -> {
                        val body = text.substringAfter("\r\n\r\n", text)
                        require(body.length <= 262144) { "Review request exceeds 256 KiB" }
                        val input = reifyMap(body)
                        require("reviewer" !in input && "synthetic" !in input) { "Reviewer identity comes from authentication" }
                        fun cid(key: String) = ContentId(input[key] as String)
                        fun revision() = revision(input["expectedRevision"])
                        json(when {
                            endpoint.endsWith("/snapshot") -> review.snapshot(input["kind"] as String, cid("receiptCid"),
                                (input["selection"] as List<*>).map { it as String })
                            endpoint.endsWith("/decision") -> withContext(checkNotNull(actor)) {
                                review.decide(cid("snapshotCid"), revision(), input["action"] as String, input["note"] as? String ?: "")
                            }
                            endpoint.endsWith("/release") -> review.release(cid("snapshotCid"), cid("approvalCid"), revision())
                            else -> error("Unknown review endpoint")
                        })
                    }
                    else -> json(mapOf("error" to "method_not_allowed"), 405)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { json(mapOf("error" to failure.message), 409) }
    }

    suspend fun stopAdmission() {
        ctx.routes.release("document-review")
        requests.drain()
    }

    suspend fun drain() {
        stopAdmission()
        for ((type, runner) in bindings) if (ctx.lcncRunners[type] === runner) ctx.lcncRunners.remove(type)
        review.drain()
        ctx.documentReview = null
    }

    fun close() = log.close()
    private fun json(body: Any?, status: Int = 200) = WireHttpResponse(status, jsonOf(body), "application/json; charset=utf-8")
}
