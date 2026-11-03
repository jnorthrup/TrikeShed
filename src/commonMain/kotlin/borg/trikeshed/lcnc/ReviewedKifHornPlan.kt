package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.kif.*
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.DocumentQuotationCheck
import borg.trikeshed.parse.reifyMap

/** Host-authenticated KIF facts and fixed taxonomy rules for the pinned native solver. */
object ReviewedKifHornPlan {
    const val POLICY = "reviewed-kif-positive-taxonomy/1"
    const val MAX_SELECTIONS = 123 // The pinned adapter admits at most 128 facts plus rules.

    internal val rules = _l[
        "(=> (and (subclass ?x ?y) (subclass ?y ?z)) (subclass ?x ?z))",
        "(=> (and (instance ?x ?y) (subclass ?y ?z)) (instance ?x ?z))",
        "(=> (subclass ?x ?y) (subclass ?x ?x))",
        "(=> (subclass ?x ?y) (subclass ?y ?y))",
        "(=> (instance ?x ?y) (subclass ?y ?y))",
    ]

    /** The returned export is not a permit and has not yet been executed. */
    suspend fun report(review: CurationReview, cas: CasStore, snapshotCid: ContentId, revision: Long,
                       predicate: String, subject: String, obj: String): Map<String, Any?> {
        require(predicate in _s["subclass", "instance"]) { "Goal must be subclass or instance" }
        fun constant(value: String): Boolean {
            if (value.length !in 1..256) return false
            val parsed = runCatching { KifSemantic.decode("(goal $value)").formula }.getOrNull() as? KifFormula.Predicate
            return parsed?.arguments?.singleOrNull() == KifTerm.Constant(value)
        }
        require(constant(subject) && constant(obj)) { "Goal requires two exact ground KIF constants" }
        val read = review.taxonomyRead(snapshotCid, revision)
        val selections = read.candidate.selections
        require(selections.size <= MAX_SELECTIONS) { "Native KIF query supports at most $MAX_SELECTIONS reviewed assertions" }
        val reader = cas as? BoundedCasReader ?: error("Bounded CAS reads are required")
        val texts = mutableMapOf<ContentId, String>()
        var bytesRead = 0
        val sidecar = linkedMapOf<String, Any?>()
        val sources = mutableListOf<KifSourceAssertion>()
        for (item in selections.view) {
            val q = item.quotation
            val source = texts.getOrPut(q.extractedTextCid) {
                val remaining = minOf(16 * 1024 * 1024, 32 * 1024 * 1024 - bytesRead)
                require(remaining > 0) { "Reviewed source byte limit" }
                val bytes = requireNotNull(reader.getBounded(q.extractedTextCid, remaining)) { "Selected text is unavailable" }
                require(ContentId.of(bytes) == q.extractedTextCid && q.originalCid == q.extractedTextCid) { "Selected source identity differs" }
                bytesRead += bytes.size
                bytes.decodeToString(throwOnInvalidSequence = true)
            }
            require(item.value["format"] == DocumentTaxonomyCandidate.POLICY &&
                DocumentQuotationCheck.exactSpan(source, q.begin, q.end, q.quote)) { "Selected KIF evidence differs" }
            val occurrence = _m["kind" j "reviewed-kif-native-occurrence/1", "readCid" j read.readCid.value,
                "snapshotCid" j snapshotCid.value, "approvalCid" j read.approvalCid.value, "selectionId" j item.id,
                "originalCid" j q.originalCid.value, "textCid" j q.extractedTextCid.value,
                "begin" j q.begin, "end" j q.end, "quote" j q.quote]
            val id = "selection:" + ContentId.of(CanonicalCbor.encodeMap(occurrence)).hex
            sidecar[id] = occurrence
            sources.add(KifSourceAssertion(id, q.extractedTextCid, read.candidate.sourceRevision, KifReview.APPROVED, q.quote))
        }
        for ((index, text) in rules.withIndex()) {
            val cid = ContentId.of(text.encodeToByteArray(throwOnInvalidSequence = true))
            sources.add(KifSourceAssertion("host-rule:${index + 1}", cid, POLICY, KifReview.APPROVED, text))
        }
        val exported = SumoNixBridge.compile(revision, sources, "($predicate $subject $obj)")
        val input = reifyMap(exported.inputJson)
        require((input["excluded"] as? List<*>)?.isEmpty() == true &&
            (input["facts"] as? List<*>)?.size == selections.size &&
            (input["rules"] as? List<*>)?.size == rules.size) { "Reviewed KIF is outside the native fragment" }
        val checked = review.taxonomyRead(snapshotCid, revision)
        require(checked.readCid == read.readCid && checked.decisionCid == read.decisionCid) { "Reviewed material changed during export" }
        return _m["kind" j "reviewed-kif-native-input/1", "scope" j exported.scope,
            "inputJson" j exported.inputJson, "inputCid" j exported.inputCid.value, "frameId" j exported.frameId.value,
            "snapshotCid" j snapshotCid.value, "revision" j revision, "readCid" j read.readCid.value,
            "approvalCid" j read.approvalCid.value, "decisionCid" j read.decisionCid.value,
            "asOf" j checked.asOf, "reviewer" j read.reviewer, "synthetic" j read.synthetic,
            "selectionCount" j selections.size, "selectionLimit" j MAX_SELECTIONS, "textBytesRead" j bytesRead,
            "policy" j POLICY, "sourceEvidence" j sidecar, "hostRules" j rules,
            "authority" j "reviewed-read-only-not-a-sink-permit"]
    }
}
