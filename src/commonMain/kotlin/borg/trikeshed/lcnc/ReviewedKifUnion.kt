package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.kif.*
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.DocumentQuotationCheck
import borg.trikeshed.parse.reifyMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A bounded union of independently approved, current KIF snapshots. */
object ReviewedKifUnion {
    const val CHAIN_SEGMENT_EDGES = 16
    const val CHAIN_SEGMENTS = 16
    private data class Row(val source: KifSourceAssertion, val occurrence: Map<String, Any?>,
                           val predicate: String, val subject: String, val obj: String)
    private data class Focus(val rows: List<Row>, val projection: String, val path: List<Row>? = null)

    /** Retrieve a candidate witness; the native solver still proves the submitted facts. */
    private fun focus(rows: List<Row>, predicate: String, subject: String, obj: String, search: Boolean,
                      check: () -> Unit): Focus {
        check()
        if (!search && rows.size <= ReviewedKifHornPlan.MAX_SELECTIONS) return Focus(rows, "complete-selection")
        if (predicate == "subclass" && subject == obj) {
            val witness = rows.firstOrNull {
                check()
                (it.predicate == "subclass" && (it.subject == subject || it.obj == subject)) ||
                    (it.predicate == "instance" && it.obj == subject)
            }
            if (witness != null) return Focus(_l[witness], "candidate-path", _l[witness])
        }
        val outgoing = mutableMapOf<String, MutableList<Int>>()
        for ((index, row) in rows.withIndex()) {
            check()
            if (row.predicate == "subclass") outgoing.getOrPut(row.subject) { mutableListOf() }.add(index)
        }
        val queue = ArrayDeque<String>()
        val previous = mutableMapOf<String, Join<String?, Int?>>()
        if (predicate == "subclass") {
            previous[subject] = null j null
            queue.addLast(subject)
        } else {
            for ((index, row) in rows.withIndex()) {
                check()
                if (row.predicate == "instance" && row.subject == subject && row.obj !in previous) {
                    previous[row.obj] = null j index
                    queue.addLast(row.obj)
                }
            }
        }
        while (queue.isNotEmpty() && obj !in previous) {
            check()
            val node = queue.removeFirst()
            for (index in outgoing[node].orEmpty()) {
                check()
                val next = rows[index].obj
                if (next !in previous) {
                    previous[next] = node j index
                    queue.addLast(next)
                }
            }
        }
        if (obj in previous && !(predicate == "subclass" && subject == obj)) {
            val path = mutableListOf<Row>()
            var node: String? = obj
            while (node != null) {
                check()
                val step = previous.getValue(node)
                step.b?.let { path.add(rows[it]) }
                node = step.a
            }
            path.reverse()
            // Leave room for the certificate checker's 64-level proof boundary.
            if (path.size <= 60) return Focus(path, "candidate-path", path)
            return Focus(rows.take(60), "candidate-path-exceeds-proof-depth", path)
        }
        return Focus(rows.take(60), "candidate-path-not-found")
    }

    suspend fun report(review: CurationReview, cas: CasStore, references: List<CurationTaxonomyRef>,
                       predicate: String, subject: String, obj: String, search: Boolean = false): Map<String, Any?> {
        val context = currentCoroutineContext()
        fun check() = context.ensureActive()
        check()
        require(predicate in _s["subclass", "instance"]) { "Goal must be subclass or instance" }
        fun constant(value: String): Boolean {
            if (value.length !in 1..256) return false
            val parsed = runCatching { KifSemantic.decode("(goal $value)").formula }.getOrNull() as? KifFormula.Predicate
            return parsed?.arguments?.singleOrNull() == KifTerm.Constant(value)
        }
        require(constant(subject) && constant(obj)) { "Goal requires two exact ground KIF constants" }
        val reads = review.taxonomyReads(references)
        check()
        require(reads.none { EnglishTaxonomyCarrier.isCarrier(it.candidate) }) {
            "English taxonomy carriers are not supported by KIF union export"
        }
        val count = reads.sumOf { it.candidate.selections.size }
        require(count <= 4096) { "Reviewed KIF selection limit" }
        val reader = cas as? BoundedCasReader ?: error("Bounded CAS reads are required")
        val texts = mutableMapOf<ContentId, String>()
        val occurrences = mutableSetOf<String>()
        val rows = mutableListOf<Row>()
        var bytesRead = 0
        for (read in reads) for (item in read.candidate.selections.view) {
            check()
            val q = item.quotation
            require(q.originalCid == q.extractedTextCid) { "Selected source identity differs" }
            require(occurrences.add("${q.originalCid.value}:${q.begin}:${q.end}")) {
                "The same source occurrence appears in more than one approval"
            }
            val source = texts.getOrPut(q.extractedTextCid) {
                val remaining = minOf(16 * 1024 * 1024, 32 * 1024 * 1024 - bytesRead)
                require(remaining > 0) { "Reviewed source byte limit" }
                val bytes = requireNotNull(reader.getBounded(q.extractedTextCid, remaining)) { "Selected text is unavailable" }
                check()
                require(ContentId.of(bytes) == q.extractedTextCid) { "Selected source identity differs" }
                check()
                bytesRead += bytes.size
                bytes.decodeToString(throwOnInvalidSequence = true).also { check() }
            }
            require(item.value["format"] == DocumentTaxonomyCandidate.POLICY &&
                DocumentQuotationCheck.exactSpan(source, q.begin, q.end, q.quote)) { "Selected KIF evidence differs" }
            val occurrence = _m["kind" j "reviewed-kif-native-occurrence/1", "readCid" j read.readCid.value,
                "snapshotCid" j read.snapshotCid.value, "approvalCid" j read.approvalCid.value, "selectionId" j item.id,
                "originalCid" j q.originalCid.value, "textCid" j q.extractedTextCid.value,
                "begin" j q.begin, "end" j q.end, "quote" j q.quote]
            val id = "selection:" + ContentId.of(CanonicalCbor.encodeMap(occurrence)).hex
            val formula = KifSemantic.decode(q.quote).formula as? KifFormula.Predicate
                ?: error("Selected KIF is not a predicate")
            require(formula.name in _s["subclass", "instance"] && formula.arguments.size == 2 &&
                formula.arguments.all { it is KifTerm.Constant }) { "Selected KIF is not a ground taxonomy assertion" }
            rows.add(Row(KifSourceAssertion(id, q.extractedTextCid, read.candidate.sourceRevision, KifReview.APPROVED, q.quote),
                occurrence, formula.name, (formula.arguments[0] as KifTerm.Constant).token,
                (formula.arguments[1] as KifTerm.Constant).token))
        }
        val focus = focus(rows, predicate, subject, obj, search, ::check)
        val focused = focus.rows
        val plan = if (!search) null else focus.path?.let { path ->
            if (path.size > CHAIN_SEGMENT_EDGES * CHAIN_SEGMENTS) null
            else if (predicate == "subclass" && subject == obj) _l[_m["predicate" j predicate, "subject" j subject, "object" j obj]]
            else path.chunked(CHAIN_SEGMENT_EDGES).map { chunk -> _m[
                "predicate" j chunk.first().predicate, "subject" j chunk.first().subject, "object" j chunk.last().obj] }
        }
        val evidence = linkedMapOf<String, Any?>()
        val sources = mutableListOf<KifSourceAssertion>()
        for (row in focused) { evidence[row.source.id] = row.occurrence; sources.add(row.source) }
        for ((index, text) in ReviewedKifHornPlan.rules.withIndex()) {
            val cid = ContentId.of(text.encodeToByteArray(throwOnInvalidSequence = true))
            sources.add(KifSourceAssertion("host-rule:${index + 1}", cid, ReviewedKifHornPlan.POLICY, KifReview.APPROVED, text))
        }
        // The occurrence identities include every individual read; no synthetic review revision is asserted.
        val exported = SumoNixBridge.compile(0, sources, "($predicate $subject $obj)")
        check()
        val input = reifyMap(exported.inputJson)
        require((input["excluded"] as? List<*>)?.isEmpty() == true &&
            (input["facts"] as? List<*>)?.size == focused.size &&
            (input["rules"] as? List<*>)?.size == ReviewedKifHornPlan.rules.size) { "Reviewed KIF is outside the native fragment" }
        val checked = review.taxonomyReads(references)
        check()
        require(checked.indices.all { checked[it].readCid == reads[it].readCid && checked[it].decisionCid == reads[it].decisionCid }) {
            "Reviewed material changed during export"
        }
        val heads = reads.map { _m["snapshotCid" j it.snapshotCid.value, "revision" j it.revision,
            "readCid" j it.readCid.value, "approvalCid" j it.approvalCid.value, "decisionCid" j it.decisionCid.value,
            "reviewer" j it.reviewer, "synthetic" j it.synthetic, "sourceRevision" j it.candidate.sourceRevision,
            "curationReceiptCid" j it.candidate.curationReceiptCid.value] }
        return _m["kind" j "reviewed-kif-union-input/1", "scope" j exported.scope,
            "inputJson" j exported.inputJson, "inputCid" j exported.inputCid.value, "frameId" j exported.frameId.value,
            "reads" j heads, "asOf" j checked.maxOf { it.asOf }, "selectionCount" j focused.size,
            "reviewedSelectionCount" j count, "projection" j focus.projection, "chainPlan" j plan,
            "selectionLimit" j ReviewedKifHornPlan.MAX_SELECTIONS, "textBytesRead" j bytesRead,
            "policy" j ReviewedKifHornPlan.POLICY, "sourceEvidence" j evidence, "hostRules" j ReviewedKifHornPlan.rules,
            "authority" j "reviewed-read-only-not-a-sink-permit"]
    }
}
