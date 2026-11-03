package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.kif.*
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.DocumentQuotationCheck
import kotlinx.coroutines.currentCoroutineContext

/**
 * Authenticated current-book inspector. Existing book modalities are norms/presumptions,
 * never asserted taxonomy facts. A genuinely asserted taxonomy receipt is not implemented.
 * Consequently this first endpoint exports an empty positive-support plan and ALL exclusions.
 * No permit is consumed and no global fact, attention budget or evidence is changed.
 */
object ReviewedTaxonomyPlan {
    suspend fun report(review: CurationReview, cas: CasStore, snapshotCid: ContentId, revision: Long,
                       predicate: String, subject: String, obj: String,
                       offset: Int = 0, limit: Int = 64): Map<String, Any?> {
        require(predicate == "subclass" || predicate == "instance") { "Goal must be subclass or instance" }
        require(offset >= 0 && limit in 1..64) { "Taxonomy page requires a nonnegative offset and a limit of 1–64" }
        fun constant(value: String): Boolean {
            if (value.length !in 1..256) return false
            val parsed = runCatching { KifSemantic.decode("(goal $value)").formula }.getOrNull() as? KifFormula.Predicate
            return parsed?.arguments?.singleOrNull() == KifTerm.Constant(value)
        }
        require(constant(subject) && constant(obj)) { "Goal requires two exact ground KIF constants" }
        currentCoroutineContext()[ReviewPrincipal] ?: error("Authenticated reviewer required")
        val inspected = review.inspect(snapshotCid)
        val kind = (inspected["snapshot"] as? Map<*, *>)?.get("kind")
        if (kind is String && DocumentTaxonomyCandidate.isTaxonomy(kind))
            return asserted(review, cas, snapshotCid, revision, predicate, subject, obj, offset, limit)
        val reader = cas as? BoundedCasReader ?: error("Bounded CAS reads are required")
        val read = review.reviewedRead(snapshotCid, revision)
        val candidate = read.candidate
        val total = candidate.selections.size
        require(offset <= total) { "Taxonomy page offset exceeds the reviewed selection" }
        val end = minOf(total, offset + limit)
        val page = _m["offset" j offset, "limit" j limit, "total" j total, "returned" j (end - offset),
            "nextOffset" j (if (end < total) end else null)]
        var bytesRead = 0
        val texts = mutableMapOf<ContentId, String>()
        val selected = (offset until end).toSeries().view.map { index ->
            val selection = candidate.selections[index]
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
            "kind" to "reviewed-taxonomy-read-frame/2", "readCid" to read.readCid.value, "page" to page,
            "mapping" to "asserted-taxonomy-source-format-unavailable", "selected" to selected,
            "fragmentExportCid" to ContentId.of(export).value)))
        val checked = review.reviewedRead(snapshotCid, revision)
        require(checked.readCid == read.readCid && checked.decisionCid == read.decisionCid) { "Reviewed material changed during query" }
        return mapOf(
            "kind" to "reviewed-taxonomy-read/1", "status" to plan.status.name, "page" to page,
            "coverage" to "positive-reviewed-selected-subset-only", "semantics" to plan.semantics,
            "mappingStatus" to "asserted-taxonomy-source-format-unavailable", "positivePathProven" to false,
            "authority" to "reviewed-read-only-not-a-sink-permit", "snapshotCid" to snapshotCid.value,
            "revision" to revision, "approvalCid" to read.approvalCid.value, "decisionCid" to read.decisionCid.value,
            "readCid" to read.readCid.value, "readFrameCid" to readFrameCid.value, "asOf" to checked.asOf, "reviewer" to read.reviewer,
            "synthetic" to read.synthetic, "curationReceiptCid" to candidate.curationReceiptCid.value,
            "ontologyCid" to candidate.ontologyCid.value, "sourceRevision" to candidate.sourceRevision,
            "assessmentCid" to read.assessmentCid.value,
            "assessments" to read.assessment.assessments.filter { item -> selected.any { it["selectionId"] == item.selectionId } }.map { item -> mapOf("selectionId" to item.selectionId,
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

    /** The proof covers the complete approved selection; pagination only limits displayed evidence. */
    private suspend fun asserted(review: CurationReview, cas: CasStore, snapshotCid: ContentId, revision: Long,
                                 predicate: String, subject: String, obj: String,
                                 offset: Int, limit: Int): Map<String, Any?> {
        val read = review.taxonomyRead(snapshotCid, revision)
        val selections = read.candidate.selections
        require(selections.size <= DocumentTaxonomyCandidate.MAX_SELECTIONS && offset <= selections.size) { "Taxonomy page exceeds the reviewed selection" }
        val reader = cas as? BoundedCasReader ?: error("Bounded CAS reads are required")
        val texts = mutableMapOf<ContentId, String>()
        var bytesRead = 0
        val assertions = selections.view.map { selection ->
            val q = selection.quotation
            val source = texts.getOrPut(q.extractedTextCid) {
                val remaining = minOf(16 * 1024 * 1024, 32 * 1024 * 1024 - bytesRead)
                require(remaining > 0) { "Reviewed source byte limit" }
                val bytes = requireNotNull(reader.getBounded(q.extractedTextCid, remaining)) { "Selected text is unavailable" }
                require(ContentId.of(bytes) == q.extractedTextCid) { "Selected text identity differs" }
                bytesRead += bytes.size
                bytes.decodeToString(throwOnInvalidSequence = true)
            }
            require(DocumentQuotationCheck.exactSpan(source, q.begin, q.end, q.quote)) { "Selected source quotation differs" }
            require(selection.value["format"] == DocumentTaxonomyCandidate.POLICY) { "KIF source policy differs" }
            KifSourceAssertion(selection.id, q.extractedTextCid, read.candidate.sourceRevision, KifReview.APPROVED, q.quote)
        }
        val plan = KifTaxonomySnapshot.create(revision, assertions).activationPlan(predicate, subject, obj)
        require(plan.sources.view.all { it.exclusion == null }) { "Reviewed KIF selection is outside the taxonomy fragment" }
        val export = plan.export()
        val end = minOf(selections.size, offset + limit)
        val page = _m["offset" j offset, "limit" j limit, "total" j selections.size, "returned" j (end - offset),
            "nextOffset" j (if (end < selections.size) end else null)]
        val selected = (offset until end).toSeries().view.map { index ->
            val item = selections[index]; val q = item.quotation
            _m["selectionId" j item.id, "kind" j item.kind, "value" j item.value,
                "exclusionReasons" j emptyList<String>(), "quotation" j _m[
                    "originalCid" j q.originalCid.value, "extractedTextCid" j q.extractedTextCid.value,
                    "begin" j q.begin, "end" j q.end, "quote" j q.quote]]
        }
        val frame = ContentId.of(CanonicalCbor.encodeMap(_m["kind" j "reviewed-kif-read-frame/1",
            "readCid" j read.readCid.value, "page" j page, "fragmentExportCid" j ContentId.of(export).value]))
        val checked = review.taxonomyRead(snapshotCid, revision)
        require(checked.readCid == read.readCid && checked.decisionCid == read.decisionCid) { "Reviewed material changed during query" }
        return _m[
            "kind" j "reviewed-taxonomy-read/1", "status" j plan.status.name, "page" j page,
            "coverage" j "positive-reviewed-selected-snapshot", "semantics" j plan.semantics,
            "mappingStatus" j DocumentTaxonomyCandidate.POLICY,
            "positivePathProven" j (plan.status == KifExplanationStatus.SUPPORTED),
            "authority" j "reviewed-read-only-not-a-sink-permit", "snapshotCid" j snapshotCid.value,
            "revision" j revision, "approvalCid" j read.approvalCid.value, "decisionCid" j read.decisionCid.value,
            "readCid" j read.readCid.value, "readFrameCid" j frame.value, "asOf" j checked.asOf,
            "reviewer" j read.reviewer, "synthetic" j read.synthetic,
            "curationReceiptCid" j read.candidate.curationReceiptCid.value, "ontologyCid" j read.candidate.ontologyCid.value,
            "sourceRevision" j read.candidate.sourceRevision, "assessmentCid" j null, "assessments" j emptyList<Any>(),
            "selected" j selected, "goal" j _m["predicate" j predicate, "subject" j subject, "object" j obj],
            "plan" j _m["queryId" j plan.queryId.value, "frameId" j plan.frameId.value,
                "exportCid" j ContentId.of(export).value, "export" j export.decodeToString(),
                "status" j plan.status.name, "truncated" j plan.truncated],
            "counters" j _m["selected" j selected.size, "eligible" j selected.size, "excluded" j 0,
                "textBytesRead" j bytesRead, "steps" j plan.steps, "proofs" j plan.proofs.size]]
    }
}
