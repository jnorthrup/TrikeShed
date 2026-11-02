package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.kif.*
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.DocumentQuotationCheck

/** Reified observations about an authenticated review record, never world assertions or permits. */
object ReviewedHornPlan {
    suspend fun report(review: CurationReview, cas: CasStore, snapshotCid: ContentId, revision: Long,
                       goal: String): Map<String, Any?> {
        require(goal in _s["containsMandatorySelection", "needsMapping"])
        val read = review.reviewedRead(snapshotCid, revision)
        val reader = cas as? BoundedCasReader ?: error("Bounded CAS reads are required")
        require(read.candidate.selections.size <= 12) { "Select at most 12 review observations" }
        val texts = mutableMapOf<ContentId, String>()
        var total = 0
        for (selection in read.candidate.selections.view) {
            val q = selection.quotation
            val text = texts.getOrPut(q.extractedTextCid) {
                val limit = minOf(16 * 1024 * 1024, 32 * 1024 * 1024 - total)
                require(limit > 0)
                val bytes = requireNotNull(reader.getBounded(q.extractedTextCid, limit)) { "Selected text unavailable" }
                require(bytes.size <= limit && ContentId.of(bytes) == q.extractedTextCid) { "Selected text identity differs" }
                total += bytes.size
                bytes.decodeToString(throwOnInvalidSequence = true)
            }
            require(DocumentQuotationCheck.exactSpan(text, q.begin, q.end, q.quote)) { "Selected source quotation differs" }
        }
        val observations = artifacts(read, goal)
        val checked = review.reviewedRead(snapshotCid, revision)
        require(checked.readCid == read.readCid && checked.decisionCid == read.decisionCid) { "Reviewed material changed during export" }
        return observations + _m["snapshotCid" j snapshotCid.value, "approvalCid" j read.approvalCid.value,
            "decisionCid" j read.decisionCid.value, "readCid" j read.readCid.value, "asOf" j checked.asOf,
            "textBytesRead" j total, "synthetic" j read.synthetic, "reviewer" j read.reviewer]
    }

    /** Called after report's authenticated read and exact quotation checks; internal for artifact tests. */
    internal fun artifacts(read: CurationReviewedRead, goal: String): Map<String, Any?> {
        require(goal in _s["containsMandatorySelection", "needsMapping"])
        val lexical = linkedMapOf<String, Any?>()
        fun token(role: String, value: Any?): String {
            // Retained JSON values must not acquire lossy replacement identities.
            value?.toString()?.encodeToByteArray(throwOnInvalidSequence = true)
            val entry = _m["role" j role, "value" j value]
            val id = "t" + ContentId.of(CanonicalCbor.encodeMap(entry)).hex
            lexical[id] = entry
            return id
        }
        val record = token("review-record", read.readCid.value)
        val must = token("modality", "must")
        val unmapped = token("world-mapping-status", "not-asserted-world-taxonomy")
        val sources = mutableListOf<KifSourceAssertion>()
        fun assertion(id: String, cid: ContentId, text: String) {
            sources.add(KifSourceAssertion(id, cid, read.revision.toString(), KifReview.APPROVED, text))
        }
        for (selection in read.candidate.selections.view) {
            val q = selection.quotation
            val provenance = linkedMapOf<String, Any?>("readCid" to read.readCid.value, "selectionId" to selection.id,
                "originalCid" to q.originalCid.value, "textCid" to q.extractedTextCid.value,
                "begin" to q.begin, "end" to q.end, "quote" to q.quote,
                "ontologyCid" to read.candidate.ontologyCid.value, "sourceRevision" to read.candidate.sourceRevision)
            val occurrenceCid = ContentId.of(CanonicalCbor.encodeMap(provenance))
            val selected = token("review-selection", provenance)
            fun fact(name: String, value: String) = assertion("${selection.id}:$name", occurrenceCid, "($name $selected $value)")
            assertion("${selection.id}:reviewSelection", occurrenceCid, "(reviewSelection $record $selected)")
            fact("selectionKind", token("selection-kind", selection.kind))
            fact("selectionMappingStatus", unmapped)
            for (field in s_["subject", "predicate", "object", "modality", "polarity", "condition"].view)
                fact("selection" + field.replaceFirstChar { it.uppercase() }, token(field, selection.value[field]))
        }
        val rules = s_[
            "(=> (and (reviewSelection ?r ?s) (selectionModality ?s $must)) (containsMandatorySelection ?r))",
            "(=> (and (reviewSelection ?r ?s) (selectionMappingStatus ?s $unmapped)) (needsMapping ?r))"]
        for ((i, text) in rules.view.withIndex()) assertion("review-record-rule:${i + 1}",
            ContentId.of(text.encodeToByteArray(throwOnInvalidSequence = true)), text)
        val exported = SumoNixBridge.compile(read.revision, sources, "($goal $record)", "review-record-observations")
        return _m["kind" j "review-record-horn-export/1", "scope" j exported.scope,
            "status" j "NOT_EXECUTED", "worldObligationApplicability" j "UNKNOWN",
            "authority" j "reviewed-read-only-not-a-sink-permit", "goal" j goal,
            "frameId" j exported.frameId.value, "inputCid" j exported.inputCid.value,
            "inputJson" j exported.inputJson, "mlir" j exported.mlir, "lexical" j lexical,
            "firstProofOnly" j true, "processBoundsRequired" j true]
    }
}
