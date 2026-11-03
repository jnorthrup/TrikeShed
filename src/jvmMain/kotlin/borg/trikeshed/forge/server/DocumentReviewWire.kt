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
    private val nativeHorn: NativeHornQuery?,
) {
    companion object {
        fun revision(value: Any?): Long {
            val number = (value as? Number)?.toDouble()
            require(number != null && number.isFinite() && number >= 0 && number <= 9_007_199_254_740_991.0 && number % 1 == 0.0) {
                "Review revision must be a nonnegative whole number"
            }
            return number.toLong()
        }

        /** reifyStrict retains duplicate entries; reject them recursively before map lookup. */
        internal fun uniqueJson(value: Any?): Boolean = when (value) {
            is Map<*, *> -> {
                val keys = HashSet<Any?>()
                value.entries.all { it.key is String && keys.add(it.key) && uniqueJson(it.key) && uniqueJson(it.value) }
            }
            is List<*> -> value.all(::uniqueJson)
            is Array<*> -> value.all(::uniqueJson)
            is String -> value.encodeToByteArray().decodeToString(throwOnInvalidSequence = true) == value
            else -> true
        }

        internal fun request(body: String): Map<*, *> {
            require(body.length <= 262144) { "Review request exceeds 256 KiB" }
            require(body.encodeToByteArray().decodeToString(throwOnInvalidSequence = true) == body) { "Invalid Unicode in review request" }
            val value = reifyStrict(body) as? Map<*, *> ?: error("Review request must be an object")
            require(uniqueJson(value)) { "Duplicate field or invalid Unicode in review request" }
            require("reviewer" !in value && "synthetic" !in value) { "Reviewer identity comes from authentication" }
            return value
        }

        internal fun taxonomyRequest(body: String): Map<*, *> {
            require(body.length <= 65536 && body.encodeToByteArray().size <= 65536) { "Taxonomy request exceeds 64KiB" }
            var depth = 0
            var string = false
            var escaped = false
            for (c in body) {
                if (string) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> string = false
                    }
                } else when (c) {
                    '"' -> string = true
                    '{', '[' -> { depth++; require(depth <= 16) { "Taxonomy request nesting limit" } }
                    '}', ']' -> { depth--; require(depth >= 0) { "Malformed taxonomy request" } }
                }
            }
            require(depth == 0 && !string) { "Malformed taxonomy request" }
            return request(body)
        }

        private fun fields(value: Map<*, *>, required: Set<String>, optional: Set<String> = emptySet()) {
            require(value.keys.all { it is String && it in required + optional } && required.all { it in value }) {
                "Unexpected or missing review fields"
            }
        }

        internal fun assessments(snapshot: ContentId, value: Any?): List<ConditionAssessment> {
            require(uniqueJson(value)) { "Duplicate field or invalid Unicode in assessments" }
            val rows = value as? List<*> ?: error("Assessments must be an array")
            require(rows.size <= 256) { "Assessment selection limit" }
            fun int(value: Any?): Int {
                val n = revision(value)
                require(n <= Int.MAX_VALUE) { "Assessment offset exceeds integer range" }
                return n.toInt()
            }
            return rows.map { raw ->
                val row = raw as? Map<*, *> ?: error("Assessment must be an object")
                fields(row, setOf("selectionId", "state", "spans"))
                val selectionId = row["selectionId"] as? String ?: error("Assessment selectionId required")
                val state = (row["state"] as? String)?.let { text ->
                    ConditionAssessmentState.entries.singleOrNull { it.name.lowercase() == text }
                } ?: error("Assessment state must be unresolved, unconditional, or conditional")
                val spans = row["spans"] as? List<*> ?: error("Assessment spans must be an array")
                require(spans.size <= 16) { "Assessment span limit" }
                ConditionAssessment(snapshot, selectionId, state, spans.map { rawSpan ->
                    val span = rawSpan as? Map<*, *> ?: error("Assessment span must be an object")
                    fields(span, setOf("textCid", "begin", "end", "quote"))
                    ConditionSpan(ContentId(span["textCid"] as? String ?: error("Assessment textCid required")),
                        int(span["begin"]), int(span["end"]), span["quote"] as? String ?: error("Assessment quote required"))
                })
            }
        }
        suspend fun open(ctx: ModuleContext, corpus: ProjectCorpus, books: ConstellationNodes, nativeHorn: NativeHornQuery? = null): DocumentReviewWire =
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
                    }, clock = ctx.clock, conditionPolicy = ConditionReviewPolicy.STRICT_BOOK)
                try {
                    review.open(CoroutineScope(ctx.scope.coroutineContext + fileIoContext))
                    DocumentReviewWire(ctx, log, review, principals, corpus, nativeHorn).also {
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
    private val pdfLinks = DocumentPdfLinksWire(ctx, corpus)

    private fun attach() {
        pdfLinks.attach()
        bindings[DocumentCurationLegos.SOURCE] = DocumentCurationLegos.source(ctx.casStore, corpus)
        bindings[DocumentCurationLegos.SHEET] = DocumentCurationLegos.sheet(ctx.casStore)
        bindings["document.reviewSnapshot"] = LcncNodeRunner { node, inputs ->
            val cid = ContentId((inputs["receiptCid"] ?: node.params["receiptCid"]) as String)
            val ids = (inputs["selection"] as? List<*>)?.map { it as String }
                ?: (reifyStrict(node.params["selection"] ?: "[]") as List<*>).map { it as String }
            review.snapshot(node.params["kind"] ?: "book", cid, ids)
        }
        bindings["document.reviewDecision"] = LcncNodeRunner { node, inputs ->
            require(inputs["expectedAssessmentCid"] == null || inputs["expectedAssessmentCid"] is String) { "Assessment CID must be text" }
            review.decide(ContentId(inputs["snapshotCid"] as String), revision(inputs["expectedRevision"]),
                node.params["action"] ?: "draft", node.params["note"].orEmpty(),
                (inputs["expectedAssessmentCid"] as? String)?.let(::ContentId))
        }
        bindings["document.reviewAssess"] = LcncNodeRunner { _, inputs ->
            val snapshot = ContentId(inputs["snapshotCid"] as String)
            review.assess(snapshot, revision(inputs["expectedRevision"]), assessments(snapshot, inputs["assessments"]))
        }
        bindings["document.reviewAssessment"] = LcncNodeRunner { _, inputs ->
            review.assessment(ContentId(inputs["snapshotCid"] as String), revision(inputs["expectedRevision"]))
        }
        bindings["document.reviewRelease"] = LcncNodeRunner { _, inputs ->
            review.release(ContentId(inputs["snapshotCid"] as String), ContentId(inputs["approvalCid"] as String),
                revision(inputs["expectedRevision"]))
        }
        ctx.lcncRunners.putAll(bindings)
        ctx.blackboard.put("lcnc/readiness/document.reviewDecision", mapOf(
            "available" to principals.isNotEmpty(), "reason" to if (principals.isEmpty()) "No analyst principal is configured" else null,
            "prerequisites" to listOf("authenticated analyst session")), "documents")
        for (suffix in listOf("", "/session", "/snapshot", "/decision", "/release", "/assess", "/assessment", "/taxonomy-plan", "/horn-input", "/horn-query"))
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
        val reasoningInput = endpoint.endsWith("/taxonomy-plan") || endpoint.endsWith("/horn-input") || endpoint.endsWith("/horn-query")
        if ((endpoint.endsWith("/decision") || endpoint.endsWith("/session") || endpoint.endsWith("/release") ||
                endpoint.endsWith("/assess") || endpoint.endsWith("/assessment") || reasoningInput) && actor == null)
            return json(mapOf("error" to "authenticated_reviewer_required"), 401)
        if (reasoningInput && method != "POST")
            return json(mapOf("error" to "method_not_allowed"), 405)
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
                        val input = if (reasoningInput) taxonomyRequest(body) else request(body)
                        fun cid(key: String) = ContentId(input[key] as? String ?: error("$key required"))
                        fun revision() = revision(input["expectedRevision"])
                        json(when {
                            endpoint.endsWith("/horn-query") -> withContext(checkNotNull(actor)) {
                                fields(input, setOf("snapshotCid", "expectedRevision", "goal"))
                                val goal = input["goal"] as? String ?: error("Review observation goal must be text")
                                require(goal in setOf("containsMandatorySelection", "needsMapping")) { "Unsupported review observation goal" }
                                nativeHorn?.report(review, ctx.casStore, cid("snapshotCid"), revision(), goal)
                                    ?: mapOf("status" to "UNAVAILABLE", "reason" to "native-profile-unconfigured",
                                        "worldObligationApplicability" to "UNKNOWN", "authority" to "reviewed-read-only-not-a-sink-permit")
                            }
                            endpoint.endsWith("/horn-input") -> withContext(checkNotNull(actor)) {
                                fields(input, setOf("snapshotCid", "expectedRevision", "goal"))
                                val goal = input["goal"] as? String ?: error("Review observation goal must be text")
                                val report = ReviewedHornPlan.report(review, ctx.casStore, cid("snapshotCid"), revision(), goal)
                                require(jsonOf(report).encodeToByteArray().size <= 1048576) { "Horn input report exceeds 1MiB" }
                                report
                            }
                            endpoint.endsWith("/taxonomy-plan") -> withContext(checkNotNull(actor)) {
                                fields(input, setOf("snapshotCid", "expectedRevision", "goal"))
                                val goal = input["goal"] as? Map<*, *> ?: error("Taxonomy goal required")
                                fields(goal, setOf("predicate", "subject", "object"))
                                fun term(key: String) = goal[key] as? String ?: error("Goal $key must be text")
                                val report = ReviewedTaxonomyPlan.report(review, ctx.casStore, cid("snapshotCid"), revision(),
                                    term("predicate"), term("subject"), term("object"))
                                require(jsonOf(report).encodeToByteArray().size <= 1048576) { "Taxonomy report exceeds 1MiB" }
                                report
                            }
                            endpoint.endsWith("/snapshot") -> review.snapshot(input["kind"] as String, cid("receiptCid"),
                                (input["selection"] as List<*>).map { it as String })
                            endpoint.endsWith("/assess") -> withContext(checkNotNull(actor)) {
                                fields(input, setOf("snapshotCid", "expectedRevision", "assessments"))
                                val snapshot = cid("snapshotCid")
                                review.assess(snapshot, revision(), assessments(snapshot, input["assessments"]))
                            }
                            endpoint.endsWith("/assessment") -> withContext(checkNotNull(actor)) {
                                fields(input, setOf("snapshotCid", "expectedRevision"))
                                review.assessment(cid("snapshotCid"), revision())
                            }
                            endpoint.endsWith("/decision") -> withContext(checkNotNull(actor)) {
                                fields(input, setOf("snapshotCid", "expectedRevision", "action"), setOf("note", "expectedAssessmentCid"))
                                require(input["note"] == null || input["note"] is String) { "Review note must be text" }
                                require(input["expectedAssessmentCid"] == null || input["expectedAssessmentCid"] is String) { "Assessment CID must be text" }
                                review.decide(cid("snapshotCid"), revision(), input["action"] as String, input["note"] as? String ?: "",
                                    (input["expectedAssessmentCid"] as? String)?.let(::ContentId))
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
        pdfLinks.drain()
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
