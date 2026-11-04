package borg.trikeshed.forge.server

import borg.trikeshed.job.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
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

        private suspend fun englishCurrent(ctx: ModuleContext, corpus: ProjectCorpus,
            snapshot: borg.trikeshed.rdf.EnglishObservationGraph.Snapshot) {
            EnglishObservationCurrent.check(corpus, s_[snapshot], "current")
            val source = snapshot.source
            val project = requireNotNull(source.project) { "English origin project is unavailable" }
            require(source.provenance["notesCid"]?.let {
                corpus.document(project.project, project.id + ProjectNodes.NOTES_SUFFIX)?.cid == it
            } != false) { "English origin notes changed" }
            val evidenceId = source.metadata["evidenceId"]?.singleOrNull()
            val evidence = evidenceId?.let { ctx.blackboard.snapshot().values["headhunter/$it"] as? Map<*, *> }
            require(evidenceId == null || evidence?.get("cid") == source.metadata["evidenceCid"]?.singleOrNull() &&
                (evidence?.get("fields") as? Map<*, *>)?.get("deleted") != true) { "English origin evidence changed" }
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
                        DocumentTaxonomyCandidate.KIND, DocumentTaxonomyCandidate.PARTITION_KIND -> {
                            val record = DocumentTaxonomyCandidate.record(ctx.casStore, cid)
                            if (EnglishTaxonomyCarrier.marked(record)) EnglishTaxonomyCarrier.candidate(ctx.casStore, cid, ids, kind = kind)
                            else DocumentTaxonomyCandidate.candidate(ctx.casStore, cid, ids, kind = kind)
                        }
                        else -> error("Unknown curation kind")
                    } },
                    current = { candidate ->
                        val translated = if (DocumentTaxonomyCandidate.isTaxonomy(candidate.kind)) {
                            val record = DocumentTaxonomyCandidate.record(ctx.casStore, candidate.curationReceiptCid)
                            if (EnglishTaxonomyCarrier.marked(record)) EnglishTaxonomyCarrier.verify(ctx.casStore, candidate.curationReceiptCid) else null
                        } else null
                        val source = when (candidate.kind) {
                            "book" -> books.reviewSource(candidate.curationReceiptCid)
                            "document" -> DocumentReviewCandidate.record(ctx.casStore, candidate.curationReceiptCid).source
                            DocumentTaxonomyCandidate.KIND, DocumentTaxonomyCandidate.PARTITION_KIND ->
                                DocumentTaxonomyCandidate.record(ctx.casStore, candidate.curationReceiptCid).source
                            else -> error("Unknown curation kind")
                        }
                        val retained = if (candidate.kind == "book") books.reviewCurrent(candidate) else if (DocumentTaxonomyCandidate.isTaxonomy(candidate.kind)) {
                            val ids = candidate.selections.view.map { it.id }
                            val resolved = if (translated != null) EnglishTaxonomyCarrier.candidate(ctx.casStore, candidate.curationReceiptCid, ids,
                                retainPolicy = false, kind = candidate.kind)
                            else DocumentTaxonomyCandidate.candidate(ctx.casStore, candidate.curationReceiptCid, ids,
                                retainPolicy = false, kind = candidate.kind)
                            resolved.kind == candidate.kind && resolved.family == candidate.family &&
                                resolved.curationReceiptCid == candidate.curationReceiptCid && resolved.ontologyCid == candidate.ontologyCid &&
                                resolved.sourceRevision == candidate.sourceRevision && resolved.runReceiptCid == candidate.runReceiptCid &&
                                resolved.selections.view.toList() == candidate.selections.view.toList()
                        } else {
                            val record = DocumentReviewCandidate.record(ctx.casStore, candidate.curationReceiptCid)
                            candidate.sourceRevision == DocumentReviewCandidate.revision(record.source) &&
                                candidate.ontologyCid == ContentId.of(CanonicalCbor.encodeMap(DocumentReviewCandidate.ontology(record)))
                        }
                        val evidenceSource = translated?.built?.snapshot?.source ?: source
                        val evidenceId = evidenceSource.metadata["evidenceId"]?.singleOrNull()
                        val evidence = evidenceId?.let { ctx.blackboard.snapshot().values["headhunter/$it"] as? Map<*, *> }
                        val sourceCurrent = if (translated != null) {
                            englishCurrent(ctx, corpus, translated.built.snapshot); true
                        } else DocumentCurationLegos.current(corpus, source)
                        retained && sourceCurrent && (evidenceId == null ||
                            evidence?.get("cid") == evidenceSource.metadata["evidenceCid"]?.singleOrNull() &&
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
    private val english = DocumentEnglishWire(ctx, corpus)

    private fun attach() {
        pdfLinks.attach()
        english.attach()
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
        for (suffix in listOf("", "/session", "/snapshot", "/decision", "/release", "/assess", "/assessment", "/taxonomy-candidates", "/taxonomy-plan", "/taxonomy-query", "/taxonomy-union", "/taxonomy-chain", "/horn-input", "/horn-query", "/english-taxonomy-candidates", "/english-taxonomy"))
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
        val reasoningInput = endpoint.endsWith("/taxonomy-candidates") || endpoint.endsWith("/taxonomy-plan") || endpoint.endsWith("/taxonomy-query") || endpoint.endsWith("/taxonomy-union") || endpoint.endsWith("/taxonomy-chain") ||
            endpoint.endsWith("/horn-input") || endpoint.endsWith("/horn-query") ||
            endpoint.endsWith("/english-taxonomy-candidates") || endpoint.endsWith("/english-taxonomy")
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
                            endpoint.endsWith("/english-taxonomy-candidates") -> withContext(checkNotNull(actor)) {
                                fields(input, setOf("inspectionCid"))
                                val snapshot = borg.trikeshed.rdf.EnglishObservationGraph.read(ctx.casStore, cid("inspectionCid"))
                                englishCurrent(ctx, corpus, snapshot)
                                val result = EnglishTaxonomyCarrier.candidates(snapshot)
                                require(jsonOf(result).encodeToByteArray().size <= 1_048_576) { "English translation candidates exceed 1MiB" }
                                englishCurrent(ctx, corpus, snapshot)
                                result
                            }
                            endpoint.endsWith("/english-taxonomy") -> withContext(checkNotNull(actor)) {
                                fields(input, setOf("inspectionCid", "contextIri", "declarations"))
                                val snapshot = borg.trikeshed.rdf.EnglishObservationGraph.read(ctx.casStore, cid("inspectionCid"))
                                englishCurrent(ctx, corpus, snapshot)
                                val rows = input["declarations"] as? List<*> ?: error("Explicit translation declarations required")
                                require(rows.size in 1..EnglishTaxonomyCarrier.MAX_FORMS) { "Choose 1 to 32 English occurrences" }
                                val declarations = rows.map { raw ->
                                    val row = raw as? Map<*, *> ?: error("Translation declaration must be an object")
                                    fields(row, setOf("id", "relation", "subjectIri", "subjectSort", "objectIri", "objectSort"))
                                    row.entries.associate { (key, value) -> key as String to value }
                                }
                                val retained = EnglishTaxonomyCarrier.prepare(ctx.casStore, snapshot,
                                    input["contextIri"] as? String ?: error("Explicit source context IRI required"), declarations)
                                englishCurrent(ctx, corpus, snapshot)
                                EnglishTaxonomyCarrier.projection(retained).also {
                                    require(jsonOf(it).encodeToByteArray().size <= 1_048_576) { "English translation response exceeds 1MiB" }
                                }
                            }
                            endpoint.endsWith("/taxonomy-candidates") -> withContext(checkNotNull(actor)) {
                                fields(input, setOf("receiptCid", "offset", "limit"))
                                fun pageValue(key: String): Int = revision(input[key]).let {
                                    require(it <= Int.MAX_VALUE) { "KIF page value exceeds integer range" }; it.toInt()
                                }
                                val receipt = cid("receiptCid")
                                val source = DocumentTaxonomyCandidate.record(ctx.casStore, receipt).source
                                suspend fun current(): Boolean {
                                    val evidenceId = source.metadata["evidenceId"]?.singleOrNull()
                                    val evidence = evidenceId?.let { ctx.blackboard.snapshot().values["headhunter/$it"] as? Map<*, *> }
                                    return DocumentCurationLegos.current(corpus, source) && (evidenceId == null ||
                                        evidence?.get("cid") == source.metadata["evidenceCid"]?.singleOrNull() &&
                                            (evidence?.get("fields") as? Map<*, *>)?.get("deleted") != true)
                                }
                                require(current()) { "KIF source is no longer current" }
                                val reader = ctx.casStore as? BoundedCasReader ?: error("Bounded CAS reads are required")
                                val original = requireNotNull(reader.getBounded(source.originalCid, 16 * 1024 * 1024)) { "Source is unavailable" }
                                require(ContentId.of(original) == source.originalCid &&
                                    original.contentEquals(source.text.encodeToByteArray(throwOnInvalidSequence = true))) { "Source identity differs" }
                                val offset = pageValue("offset")
                                val limit = pageValue("limit")
                                require(limit in 1..64) { "KIF candidate page size must be 1 to 64" }
                                val (rows, total) = DocumentTaxonomyCandidate.page(source, offset, limit)
                                val report = _m["receiptCid" j receipt.value, "sourceRevision" j DocumentReviewCandidate.revision(source),
                                    "offset" j offset, "limit" j limit, "total" j total, "selections" j rows]
                                require(current()) { "KIF source changed while inspecting candidates" }
                                require(jsonOf(report).encodeToByteArray().size <= 1048576) { "KIF candidate page exceeds 1MiB; request fewer rows" }
                                report
                            }
                            endpoint.endsWith("/taxonomy-union") || endpoint.endsWith("/taxonomy-chain") -> withContext(checkNotNull(actor)) {
                                fields(input, setOf("references", "goal"))
                                val refs = input["references"] as? List<*> ?: error("Review references required")
                                require(refs.size in 1..16) { "Choose up to 16 reviewed snapshots" }
                                val references = refs.map { value ->
                                    val ref = value as? Map<*, *> ?: error("Review reference must be an object")
                                    fields(ref, setOf("snapshotCid", "expectedRevision"))
                                    CurationTaxonomyRef(ContentId(ref["snapshotCid"] as? String ?: error("snapshotCid required")),
                                        revision(ref["expectedRevision"]))
                                }
                                val goal = input["goal"] as? Map<*, *> ?: error("Taxonomy goal required")
                                fields(goal, setOf("predicate", "subject", "object"))
                                fun term(key: String) = goal[key] as? String ?: error("Goal $key must be text")
                                val result = (if (endpoint.endsWith("/taxonomy-chain"))
                                    nativeHorn?.taxonomyChain(review, ctx.casStore, references, term("predicate"), term("subject"), term("object"))
                                else nativeHorn?.taxonomyUnion(review, ctx.casStore, references,
                                    term("predicate"), term("subject"), term("object")))
                                    ?: _m["status" j "UNAVAILABLE", "reason" j "native-profile-unconfigured",
                                        "authority" j "reviewed-read-only-not-a-sink-permit"]
                                require(jsonOf(result).encodeToByteArray().size <= 1048576) { "Native taxonomy report exceeds 1MiB" }
                                result
                            }
                            endpoint.endsWith("/taxonomy-query") -> withContext(checkNotNull(actor)) {
                                fields(input, setOf("snapshotCid", "expectedRevision", "goal"))
                                val goal = input["goal"] as? Map<*, *> ?: error("Taxonomy goal required")
                                fields(goal, setOf("predicate", "subject", "object"))
                                fun term(key: String) = goal[key] as? String ?: error("Goal $key must be text")
                                val result = nativeHorn?.taxonomy(review, ctx.casStore, cid("snapshotCid"), revision(),
                                    term("predicate"), term("subject"), term("object"))
                                    ?: _m["status" j "UNAVAILABLE", "reason" j "native-profile-unconfigured",
                                        "authority" j "reviewed-read-only-not-a-sink-permit"]
                                require(jsonOf(result).encodeToByteArray().size <= 1048576) { "Native taxonomy report exceeds 1MiB" }
                                result
                            }
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
                                fields(input, setOf("snapshotCid", "expectedRevision", "goal"), setOf("offset", "limit"))
                                val goal = input["goal"] as? Map<*, *> ?: error("Taxonomy goal required")
                                fields(goal, setOf("predicate", "subject", "object"))
                                fun term(key: String) = goal[key] as? String ?: error("Goal $key must be text")
                                fun page(key: String, default: Int): Int = if (key !in input) default else revision(input[key]).let {
                                    require(it <= Int.MAX_VALUE) { "Taxonomy page value exceeds integer range" }; it.toInt()
                                }
                                val report = ReviewedTaxonomyPlan.report(review, ctx.casStore, cid("snapshotCid"), revision(),
                                    term("predicate"), term("subject"), term("object"), page("offset", 0), page("limit", 64))
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
        english.drain()
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
