package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.*

/** Review projects the retained attempt; it never calls NLP or a model to repair missing evidence. */
object DocumentReviewCandidate {
    fun record(cas: CasStore, cid: ContentId): DocumentCurationRecord {
        val bytes = requireNotNull(cas.get(cid)) { "Document curation receipt is unavailable" }
        require(ContentId.of(bytes) == cid) { "Document curation receipt identity differs" }
        return DocumentCuratorCodec.decode(bytes)
    }

    fun revision(source: DocumentSource): String = ContentId.of(CanonicalCbor.encodeMap(
        DocumentCuratorCodec.source(source) - "text")).value

    fun ontology(record: DocumentCurationRecord): Map<String, Any?> = mapOf(
        "kind" to "document-tool-ontology", "version" to 1, "entries" to record.toolOntology.view.toList())

    fun candidate(cas: CasStore, cid: ContentId, ids: List<String>): CurationReviewCandidate {
        require(ids.isNotEmpty() && ids.distinct().size == ids.size) { "Choose distinct document selections" }
        val record = record(cas, cid)
        val source = record.source
        require(cas.get(source.originalCid) != null && cas.get(source.extractedTextCid)?.contentEquals(source.text.encodeToByteArray()) == true) {
            "Document source is not retained in CAS"
        }
        val choices = values(record).associateBy { it.id }
        val selected = ids.map { choices[it] ?: error("Unknown or ungrounded document selection: $it") }.toSeries()
        val ontologyCid = cas.put(CanonicalCbor.encodeMap(ontology(record)))
        val sourceIdentity = source.project?.let { "${it.project}/${it.id}" }
            ?: source.metadata["evidenceId"]?.singleOrNull() ?: source.originalCid.value
        return CurationReviewCandidate("document", "document/$sourceIdentity", cid, ontologyCid,
            revision(source), selected)
    }

    /** UI labels are projections only; candidate() independently re-resolves every selected ordinal. */
    fun selections(record: DocumentCurationRecord): List<Map<String, Any?>> = values(record).map { item -> mapOf(
        "id" to item.id, "kind" to item.kind,
        "label" to if (item.kind == "relationship")
            listOf(item.value["subject"], item.value["predicate"], item.value["object"]).joinToString(" ")
            else "${item.value["antecedent"]} ${item.value["copula"]} ${item.value["consequent"]}",
        "quotation" to record.source.quotation(item.quotation.begin, item.quotation.end)) }

    private fun values(record: DocumentCurationRecord): List<CurationReviewSelection> {
        val source = record.source
        fun quote(begin: Int, end: Int): CurationReviewQuotation {
            require(begin >= 0 && end > begin && end <= source.text.length) { "Document quotation offsets are invalid" }
            return CurationReviewQuotation(source.originalCid, source.extractedTextCid, begin, end, source.text.substring(begin, end))
        }
        return buildList {
            record.proposals.view.forEachIndexed { index, proposal ->
                if (proposal.subject.isNullOrBlank() || proposal.predicate.isNullOrBlank() || proposal.obj.isNullOrBlank()) return@forEachIndexed
                val grounded = DocumentCuratorGrounding.quotation(source, proposal)
                val begin = grounded.quotationBegin ?: return@forEachIndexed
                val end = grounded.quotationEnd ?: return@forEachIndexed
                if (begin < 0 || end <= begin || end > source.text.length ||
                    source.text.substring(begin, end) != proposal.quote) return@forEachIndexed
                add(CurationReviewSelection("proposal:$index", "relationship", DocumentCuratorCodec.proposal(proposal) +
                    ("sourceEvidence" to source.quotation(begin, end)), quote(begin, end)))
            }
            DocumentCurationFacts.ruleCandidates(record).view.forEachIndexed { index, rule ->
                val begin = (rule["begin"] as Number).toInt(); val end = (rule["end"] as Number).toInt()
                add(CurationReviewSelection("rule:$index", "rule", rule + ("sourceEvidence" to source.quotation(begin, end)), quote(begin, end)))
            }
        }
    }
}
