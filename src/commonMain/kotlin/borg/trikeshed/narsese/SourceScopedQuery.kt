package borg.trikeshed.narsese

import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.job.BoundedCasReader
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lcnc.CurationReview
import borg.trikeshed.lib.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Exact text fields, not guessed IRIs or inferred semantic equivalents. */
data class SourceQuery(val originalCid: ContentId, val extractedTextCid: ContentId, val graph: String,
    val subject: String, val predicate: String, val obj: String, val modality: String? = null, val polarity: Boolean? = null)

enum class SourceQueryStatus { MATCH, UNKNOWN, COLLISION }

/** A reviewed source selection, not a legal conclusion or a claim of original-document authenticity. */
data class SourceQueryMatch(val subject: String, val predicate: String, val obj: String, val graph: String,
    val graphOrigin: String, val originalCid: ContentId, val extractedTextCid: ContentId,
    val quote: String, val begin: Int, val end: Int, val selectionId: String,
    val propositionCid: ContentId, val occurrenceCid: ContentId, val conditionState: String, val modality: String, val polarity: Boolean,
    val snapshotCid: ContentId, val releaseCid: ContentId, val admissionCid: ContentId,
    val ontologyCid: ContentId, val sourceRevision: String, val reviewer: String, val synthetic: Boolean)

/** Coverage is limited to this release's supported selections and may be reduced by bag eviction. */
data class SourceQueryResult(val status: SourceQueryStatus, val matches: List<SourceQueryMatch>,
    val coverage: String = "selected-reviewed-subset-only")

/**
 * One-shot source-scoped lookup. [CurationReview] is supplied by trusted host code;
 * the caller cannot submit an approval map or declare its own review status. Only a
 * current STRICT_BOOK release with explicit unconditional assessments is supported.
 * Once a bounded reader is available, a release is consumed even for an absent query,
 * collision, or lost bag residency.
 * This API does not authenticate its review/CAS instances; a host must bind those
 * itself and never let guest graph or HTTP input choose them. Synthetic review is labeled.
 *
 * A fresh private bag holds zero-evidence attention hints, not propositions or legal
 * facts. Full source-scoped identities remain in this invocation's sidecar. Coordinates
 * never establish identity; any coordinate shared by distinct identities is quarantined.
 * Reviewed graph strings are exact labels only: this lookup does not establish RDF graph
 * membership. A derived extracted-text graph is labeled separately from a reviewed label.
 * Any unsupported selected relationship, bag eviction, rejection, or an absent selection
 * yields UNKNOWN, not absence. Explicit modality/polarity filters select reviewed rows;
 * they do not establish that no opposing row exists.
 * The final ledger check is an as-of observation, not an atomic guarantee against a later
 * correction. Exact extracted-text quotation does not authenticate the original, prove OCR
 * correctness, or establish legal truth. No live rete, training, or model is used.
 */
object SourceScopedQuery {
    suspend fun query(review: CurationReview, cas: CasStore, releaseCid: ContentId, request: SourceQuery): SourceQueryResult =
        query(review, cas, releaseCid, request) { id ->
            AngularCodec.encode(RelationKind.ATTRACTION, taxonomyKey = "source-scoped-query", subjectTerm = id.value)
        }

    /** Host-only coordinate seam permits a deterministic collision test; never selected by a graph input. */
    internal suspend fun query(review: CurationReview, cas: CasStore, releaseCid: ContentId, request: SourceQuery,
        coordinate: (ContentId) -> Long): SourceQueryResult {
        val reader = cas as? BoundedCasReader ?: return SourceQueryResult(SourceQueryStatus.UNKNOWN, emptyList())
        val admitted = review.consume(releaseCid)
        if (admitted["kind"] != "book") return SourceQueryResult(SourceQueryStatus.UNKNOWN, emptyList())
        fun cid(value: Any?): ContentId? = try { (value as? String)?.let(::ContentId) } catch (_: IllegalArgumentException) { null }
        if (cid(admitted["releaseCid"]) != releaseCid) return SourceQueryResult(SourceQueryStatus.UNKNOWN, emptyList())
        val snapshot = cid(admitted["snapshotCid"]) ?: return SourceQueryResult(SourceQueryStatus.UNKNOWN, emptyList())
        val admission = cid(admitted["admissionCid"]) ?: return SourceQueryResult(SourceQueryStatus.UNKNOWN, emptyList())
        val ontology = cid(admitted["ontologyCid"]) ?: return SourceQueryResult(SourceQueryStatus.UNKNOWN, emptyList())
        val receipt = cid(admitted["curationReceiptCid"]) ?: return SourceQueryResult(SourceQueryStatus.UNKNOWN, emptyList())
        val sourceRevision = (admitted["sourceRevision"] as? String)?.takeIf { it.isNotBlank() }
            ?: return SourceQueryResult(SourceQueryStatus.UNKNOWN, emptyList())
        val reviewer = (admitted["reviewer"] as? String)?.takeIf { it.isNotBlank() }
            ?: return SourceQueryResult(SourceQueryStatus.UNKNOWN, emptyList())
        val synthetic = admitted["synthetic"] as? Boolean ?: return SourceQueryResult(SourceQueryStatus.UNKNOWN, emptyList())
        val relationships = admitted["relationships"] as? List<*> ?: return SourceQueryResult(SourceQueryStatus.UNKNOWN, emptyList())
        if (relationships.size > 64) return SourceQueryResult(SourceQueryStatus.UNKNOWN, emptyList())
        val texts = HashMap<ContentId, String?>()
        var totalTextBytes = 0
        fun text(id: ContentId): String? {
            if (texts.containsKey(id)) return texts[id]
            val limit = minOf(16 * 1024 * 1024, 32 * 1024 * 1024 - totalTextBytes)
            val input = try { reader.getBounded(id, limit) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            val bytes = if (input == null || input.size > limit) null else input.copyOf()
            val result = if (bytes == null) null else {
                totalTextBytes += bytes.size
                if (ContentId.of(bytes) != id) null
                else try { bytes.decodeToString(throwOnInvalidSequence = true) } catch (_: Exception) { null }
            }
            texts[id] = result
            return result
        }
        fun offset(raw: Any?): Int? {
            val n = (raw as? Number)?.toDouble() ?: return null
            return if (n.isFinite() && n >= 0 && n <= Int.MAX_VALUE && n % 1.0 == 0.0) n.toInt() else null
        }
        fun validText(s: String): Boolean = runCatching { s.encodeToByteArray(throwOnInvalidSequence = true) }.isSuccess
        fun unknown() = SourceQueryResult(SourceQueryStatus.UNKNOWN, emptyList())
        val entries = ArrayList<SourceQueryMatch>()
        for (raw in relationships) {
            // Do not silently discard an unsupported selected relationship: it might be
            // the opposing force, condition, or source needed to interpret another row.
            val item = raw as? Map<*, *> ?: return unknown()
            val value = item["value"] as? Map<*, *> ?: return unknown()
            val condition = item["conditionReview"] as? Map<*, *> ?: return unknown()
            if (condition["policy"] != "strict-book/1" || condition["state"] != "unconditional" || value["condition"] != null) return unknown()
            val q = item["quotation"] as? Map<*, *> ?: return unknown()
            val original = cid(q["originalCid"]) ?: return unknown()
            val extracted = cid(q["extractedTextCid"]) ?: return unknown()
            val begin = offset(q["begin"]) ?: return unknown()
            val end = offset(q["end"]) ?: return unknown()
            val quote = (q["quote"] as? String)?.takeIf { it.isNotBlank() && it.length <= 32_768 } ?: return unknown()
            val source = text(extracted) ?: return unknown()
            if (!DocumentQuotationCheck.exactSpan(source, begin, end, quote)) return unknown()
            val subject = (value["subject"] as? String)?.takeIf { it.isNotBlank() && it.length <= 8192 } ?: return unknown()
            val predicate = (value["predicate"] as? String)?.takeIf { it.isNotBlank() && it.length <= 8192 } ?: return unknown()
            val obj = (value["object"] as? String)?.takeIf { it.isNotBlank() && it.length <= 8192 } ?: return unknown()
            val modality = (value["modality"] as? String)?.takeIf { it.isNotBlank() && it.length <= 128 } ?: return unknown()
            val polarity = value["polarity"] as? Boolean ?: return unknown()
            val id = (item["id"] as? String)?.takeIf { it.isNotBlank() && it.length <= 256 } ?: return unknown()
            val explicitGraph = value["graph"]
            if (explicitGraph != null && (explicitGraph !is String || explicitGraph.isBlank() || explicitGraph.length > 8192)) return unknown()
            val graph = explicitGraph as? String ?: "urn:trikeshed:source-text:${extracted.value}"
            val graphOrigin = if (explicitGraph == null) "derived-extracted-text" else "reviewed-selection"
            if (!(_l[subject, predicate, obj, modality, id, graph, sourceRevision, reviewer].all(::validText))) return unknown()
            val canonical = CanonicalCbor.encodeMap(_m[
                "kind" j "source-scoped-selection/1", "snapshotCid" j snapshot.value,
                "curationReceiptCid" j receipt.value, "ontologyCid" j ontology.value, "sourceRevision" j sourceRevision,
                "originalCid" j original.value, "textCid" j extracted.value, "graph" j graph, "graphOrigin" j graphOrigin,
                "subject" j subject, "predicate" j predicate, "object" j obj, "modality" j modality, "polarity" j polarity,
                "conditionState" j "unconditional",
            ])
            val proposition = ContentId.of(canonical)
            val occurrence = ContentId.of(CanonicalCbor.encodeMap(_m[
                "kind" j "source-scoped-occurrence/1", "snapshotCid" j snapshot.value,
                "selectionId" j id, "propositionCid" j proposition.value, "originalCid" j original.value,
                "textCid" j extracted.value, "begin" j begin, "end" j end, "quote" j quote,
            ]))
            entries.add(SourceQueryMatch(subject, predicate, obj, graph, graphOrigin, original, extracted,
                quote, begin, end, id, proposition, occurrence, "unconditional", modality, polarity,
                snapshot, releaseCid, admission, ontology, sourceRevision, reviewer, synthetic))
        }
        val byId = entries.groupBy { it.propositionCid }
        // Freeze the host-provided coordinate once; identity never depends on a later recomputation.
        val coordinates = byId.keys.associateWith(coordinate)
        val byCoordinate = byId.keys.groupBy { coordinates.getValue(it) }
        val collisions = byCoordinate.filterValues { it.size > 1 }.keys
        val matching = entries.filter { it.originalCid == request.originalCid && it.extractedTextCid == request.extractedTextCid &&
            it.graph == request.graph && it.subject == request.subject && it.predicate == request.predicate && it.obj == request.obj &&
            (request.modality == null || it.modality == request.modality) && (request.polarity == null || it.polarity == request.polarity) }
        val bag = BeliefBagElement(capacity = 64)
        bag.open()
        try {
            for ((angular, ids) in byCoordinate) {
                if (angular in collisions) continue
                val id = ids.single()
                val row = byId.getValue(id).first()
                val signal = SemanticSignal(angular, EvidenceCoord.EMPTY, RelationKind.ATTRACTION,
                    row.originalCid.value, provenanceCid = admission.value)
                bag.submit(BeliefIntake.Mint(signal, BudgetCoord(0.5f, 0.4f, 0.5f), receiptCid = id))
            }
            bag.awaitProcessed()
            val present = bag.snapshot().entries.groupBy { it.key.a }
            val checked = review.inspect(snapshot)
            if (checked["current"] != true || checked["state"] != "consume" || checked["decisionCid"] != admission.value)
                return SourceQueryResult(SourceQueryStatus.UNKNOWN, emptyList())
            // A wildcard must not present opposing force, polarity, or graph provenance as one answer.
            if (matching.map { Triple(it.modality, it.polarity, it.graphOrigin) }.distinct().size > 1)
                return SourceQueryResult(SourceQueryStatus.UNKNOWN, emptyList())
            if (matching.any { coordinates.getValue(it.propositionCid) in collisions })
                return SourceQueryResult(SourceQueryStatus.COLLISION, emptyList())
            val matches = matching.filter { row ->
                val residents = present[coordinates.getValue(row.propositionCid)].orEmpty()
                residents.size == 1 && residents.single().value.let { signal ->
                    signal.relation == RelationKind.ATTRACTION && signal.subjectCid == row.originalCid.value &&
                        signal.provenanceCid == admission.value && signal.evidence == EvidenceCoord.EMPTY
                }
            }
            return if (matches.isEmpty()) SourceQueryResult(SourceQueryStatus.UNKNOWN, emptyList())
            else SourceQueryResult(SourceQueryStatus.MATCH, matches)
        } finally { withContext(NonCancellable) { bag.drain() } }
    }
}
