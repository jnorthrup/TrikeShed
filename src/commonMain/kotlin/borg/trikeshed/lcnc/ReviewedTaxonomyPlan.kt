package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.kif.*
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.DocumentQuotationCheck

/**
 * Authenticated current-book inspector. Existing book modalities are norms/presumptions,
 * never asserted taxonomy facts. A genuinely asserted taxonomy receipt is not implemented.
 * Consequently this first endpoint exports an empty positive-support plan and ALL exclusions.
 * No permit is consumed and no global fact, attention budget or evidence is changed.
 */
object ReviewedTaxonomyPlan {
    suspend fun report(review: CurationReview, cas: CasStore, snapshotCid: ContentId, revision: Long,
                       predicate: String, subject: String, obj: String): Map<String, Any?> {
        require(predicate == "subclass" || predicate == "instance") { "Goal must be subclass or instance" }
        fun constant(value: String): Boolean {
            if (value.length !in 1..256) return false
            val parsed = runCatching { KifSemantic.decode("(goal $value)").formula }.getOrNull() as? KifFormula.Predicate
            return parsed?.arguments?.singleOrNull() == KifTerm.Constant(value)
        }
        require(constant(subject) && constant(obj)) { "Goal requires two exact ground KIF constants" }
        val reader = cas as? BoundedCasReader ?: error("Bounded CAS reads are required")
        val read = review.reviewedRead(snapshotCid, revision)
        val candidate = read.candidate
        require(candidate.selections.size <= 64) { "Reviewed taxonomy report selection limit" }
        var bytesRead = 0
        val texts = mutableMapOf<ContentId, String>()
        val selected = candidate.selections.view.map { selection ->
            val q = selection.quotation
            val source = texts.getOrPut(q.extractedTextCid) {
                val limit = minOf(16 * 1024 * 1024, 32 * 1024 * 1024 - bytesRead)
                require(limit > 0) { "Reviewed source byte limit" }
                val bytes = requireNotNull(reader.getBounded(q.extractedTextCid, limit)) { "Selected text is unavailable" }
                require(bytes.size <= limit && ContentId.of(bytes) == q.extractedTextCid) { "Selected text identity differs" }
                bytesRead += bytes.size
                bytes.decodeToString(throwOnInvalidSequence = true)
            }
            require(DocumentQuotationCheck.exactSpan(source, q.begin, q.end, q.quote)) { "Selected source quotation differs" }
            val reasons = buildList {
                if (selection.kind != "relationship") add("Selection is not a relationship")
                if (selection.value["predicate"] !in setOf("subclass", "instance")) add("Predicate is outside exact taxonomy mapping")
                if (selection.value["polarity"] != true) add("Selected polarity is not positive")
                if (selection.value["condition"] != null) add("Conditional statement is not executable taxonomy")
                add("Existing book receipt modalities are not asserted taxonomy; no asserted taxonomy receipt format is available")
            }
            val occurrenceCid = ContentId.of(CanonicalCbor.encodeMap(mapOf(
                "kind" to "reviewed-taxonomy-occurrence/1", "snapshotCid" to snapshotCid.value,
                "approvalCid" to read.approvalCid.value, "selectionId" to selection.id,
                "originalCid" to q.originalCid.value, "textCid" to q.extractedTextCid.value,
                "begin" to q.begin, "end" to q.end, "quote" to q.quote)))
            mapOf("selectionId" to selection.id, "kind" to selection.kind, "value" to selection.value,
                "occurrenceCid" to occurrenceCid.value, "exclusionReasons" to reasons,
                "quotation" to mapOf("originalCid" to q.originalCid.value,
                    "extractedTextCid" to q.extractedTextCid.value, "begin" to q.begin, "end" to q.end, "quote" to q.quote))
        }.toList()
        // There are deliberately no APPROVED KifSourceAssertions made from norm modalities.
        val plan = KifTaxonomySnapshot.create(revision, emptyList()).activationPlan(predicate, subject, obj)
        val export = plan.export()
        val readFrameCid = ContentId.of(CanonicalCbor.encodeMap(mapOf(
            "kind" to "reviewed-taxonomy-read-frame/1", "readCid" to read.readCid.value,
            "mapping" to "asserted-taxonomy-source-format-unavailable", "selected" to selected,
            "fragmentExportCid" to ContentId.of(export).value)))
        val checked = review.reviewedRead(snapshotCid, revision)
        require(checked.readCid == read.readCid && checked.decisionCid == read.decisionCid) { "Reviewed material changed during query" }
        return mapOf(
            "kind" to "reviewed-taxonomy-read/1", "status" to plan.status.name,
            "coverage" to "positive-reviewed-selected-subset-only", "semantics" to plan.semantics,
            "mappingStatus" to "asserted-taxonomy-source-format-unavailable", "positivePathProven" to false,
            "authority" to "reviewed-read-only-not-a-sink-permit", "snapshotCid" to snapshotCid.value,
            "revision" to revision, "approvalCid" to read.approvalCid.value, "decisionCid" to read.decisionCid.value,
            "readCid" to read.readCid.value, "readFrameCid" to readFrameCid.value, "asOf" to checked.asOf, "reviewer" to read.reviewer,
            "synthetic" to read.synthetic, "curationReceiptCid" to candidate.curationReceiptCid.value,
            "ontologyCid" to candidate.ontologyCid.value, "sourceRevision" to candidate.sourceRevision,
            "assessmentCid" to read.assessmentCid.value,
            "assessments" to read.assessment.assessments.map { item -> mapOf("selectionId" to item.selectionId,
                "state" to item.state.name.lowercase(), "spans" to item.spans.map { span -> mapOf(
                    "textCid" to span.textCid.value, "begin" to span.begin, "end" to span.end, "quote" to span.quote) }) },
            "selected" to selected,
            "goal" to mapOf("predicate" to predicate, "subject" to subject, "object" to obj),
            "plan" to mapOf("queryId" to plan.queryId.value, "frameId" to plan.frameId.value,
                "exportCid" to ContentId.of(export).value, "export" to export.decodeToString(),
                "status" to plan.status.name, "truncated" to plan.truncated),
            "counters" to mapOf("selected" to selected.size, "eligible" to 0, "excluded" to selected.size,
                "textBytesRead" to bytesRead, "steps" to plan.steps, "proofs" to plan.proofs.size),
        )
    }
}
