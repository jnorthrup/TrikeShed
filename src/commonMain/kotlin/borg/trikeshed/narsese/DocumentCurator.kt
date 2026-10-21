package borg.trikeshed.narsese

import borg.trikeshed.job.ContentId
import borg.trikeshed.kif.KifExpr
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.modelmux.ModelResponse
import borg.trikeshed.modelmux.ModelResponseReceipt
import borg.trikeshed.nlp.NlpDocument

/** CIDs identify original bytes and exact UTF-8 extracted text; offsets below are UTF-16. */
data class DocumentSource(
    val originalCid: ContentId,
    val extractedTextCid: ContentId,
    val text: String,
    val name: String,
    val mediaType: String,
    val correlation: String,
    val metadata: Map<String, List<String>> = emptyMap(),
    val category: String = if (originalCid == extractedTextCid) "text" else "document",
    /** Attribution supplied by the source; authentication belongs to the host's review boundary. */
    val provenance: Map<String, String> = emptyMap(),
    val project: DocumentRevision? = null,
    val extraction: DocumentRevision? = null,
    val anchors: List<DocumentAnchor> = emptyList(),
    val unresolved: List<String> = listOf("page", "folio", "section", "table", "diagram", "coordinates"),
) {
    val isTextCorrection: Boolean get() = provenance["correctionKind"] == "manual-text" &&
        !provenance["correctedFrom"].isNullOrBlank() && extraction == null && anchors.isEmpty() &&
        unresolved.containsAll(listOf("page", "folio", "section", "table", "diagram", "coordinates"))

    /** Exact UTF-16 quotation plus every retained layout span it intersects. */
    fun quotation(begin: Int, end: Int): Map<String, Any?> {
        require(begin >= 0 && end > begin && end <= text.length) { "Quotation outside extracted text" }
        return mapOf("originalCid" to originalCid.value, "extractedTextCid" to extractedTextCid.value,
            "begin" to begin, "end" to end, "quote" to text.substring(begin, end),
            "anchors" to anchors.filter { it.begin < end && it.end > begin }.map(DocumentAnchor::toMap),
            "unresolved" to unresolved, "category" to category, "provenance" to provenance,
            "authentication" to "unverified",
            "quotationSource" to if (isTextCorrection) "manual-correction" else "extracted-text")
    }
}

/** The source and its extraction have separate store revisions as well as separate content identities. */
data class DocumentRevision(val project: String, val id: String, val cid: String, val rev: String, val seq: Long) {
    fun toMap(): Map<String, Any?> = mapOf("project" to project, "id" to id, "cid" to cid, "rev" to rev, "seq" to seq)
    companion object {
        fun fromMap(value: Any?): DocumentRevision? = (value as? Map<*, *>)?.let { m ->
            val seq = (m["seq"] as Number).toDouble()
            require(seq.isFinite() && seq >= 0 && seq <= 9_007_199_254_740_991.0 && seq % 1.0 == 0.0) { "Invalid source sequence" }
            DocumentRevision(m["project"] as String, m["id"] as String, m["cid"] as String,
                m["rev"] as String, seq.toLong())
        }
    }
}

/** A span of the retained extraction, with only layout actually supplied by the reader. */
data class DocumentAnchor(
    val begin: Int, val end: Int, val page: Int? = null, val folio: String? = null,
    val section: String? = null, val table: String? = null, val diagram: String? = null,
    val coordinates: List<Int>? = null,
    val unresolved: List<String> = listOf("table", "diagram"),
) {
    fun toMap(): Map<String, Any?> = mapOf("begin" to begin, "end" to end, "page" to page, "folio" to folio,
        "section" to section, "table" to table, "diagram" to diagram, "coordinates" to coordinates,
        "coordinateSystem" to coordinates?.let { "pixels-300dpi" }, "unresolved" to unresolved)
    companion object {
        fun fromMap(value: Any?): DocumentAnchor {
            val m = requireNotNull(value as? Map<*, *>) { "Source anchor must be an object" }
            fun integer(value: Any?): Int {
                val n = requireNotNull(value as? Number) { "Source coordinates must be integers" }.toDouble()
                require(n.isFinite() && n >= 0 && n <= Int.MAX_VALUE && n % 1.0 == 0.0) { "Invalid source coordinate" }
                return n.toInt()
            }
            return DocumentAnchor(integer(m["begin"]), integer(m["end"]),
                m["page"]?.let(::integer), m["folio"] as? String, m["section"] as? String,
                m["table"] as? String, m["diagram"] as? String,
                (m["coordinates"] as? List<*>)?.map(::integer),
                (m["unresolved"] as? List<*>)?.map { it as String } ?: listOf("table", "diagram"))
        }
    }
}

/** Raw JSON is retained even when no typed proposal can be recovered. */
data class DocumentProposal(
    val raw: String,
    val subject: String? = null,
    val predicate: String? = null,
    val obj: String? = null,
    val confidence: Double? = null,
    val quote: String? = null,
    val begin: Int? = null,
    val end: Int? = null,
    val polarity: Boolean? = null,
    val modality: String? = null,
    val reasons: Series<String> = emptySeriesOf(),
    val receiptCid: ContentId? = null,
    /** Source quotation only; [begin]/[end] retain the model's unmodified offsets. */
    val quotationReceiptCid: ContentId? = null,
    val quotationBegin: Int? = null,
    val quotationEnd: Int? = null,
)

/** Only [expression]'s outer source attribution is submitted, never its quoted proposition. */
data class DocumentAttribution(
    val receiptCid: ContentId,
    val expression: KifExpr,
    val mapped: KgNalBridge.NalMapped,
    val signal: SemanticSignal,
    val evidenceBasis: EvidenceBasis,
) {
    /** Quotation occurrences are evidence, but are not term-bearing assertions for Rete. */
    val isSemantic: Boolean
        get() = (expression as? KifExpr.ListExpr)?.elements?.firstOrNull()
            ?.let { (it as? KifExpr.Atom)?.token == "states" } == true
}

/**
 * An immutable attempt, retained by CAS and a separate DurableAppendLog.
 * Reservations without a later submitted receipt mean uncertain delivery, not permission to retry.
 * A successful channel send acknowledges enqueueing, not the bag's independent WAL commit.
 */
data class DocumentCurationRecord(
    val source: DocumentSource,
    val nlp: NlpDocument?,
    val model: ModelResponse?,
    val modelId: String,
    val proposals: Series<DocumentProposal>,
    val reasons: Series<String>,
    val reservedReceiptCids: Series<ContentId> = emptySeriesOf(),
    val submittedReceiptCids: Series<ContentId> = emptySeriesOf(),
    val duplicateReceiptCids: Series<ContentId> = emptySeriesOf(),
    val observerFailures: Series<String> = emptySeriesOf(),
    val instructions: String = DocumentCuratorGrounding.instructions,
    /** Quotation occurrence is separate from admission of the model's semantic interpretation. */
    val quotationReservedReceiptCids: Series<ContentId> = emptySeriesOf(),
    val quotationSubmittedReceiptCids: Series<ContentId> = emptySeriesOf(),
    val quotationDuplicateReceiptCids: Series<ContentId> = emptySeriesOf(),
    /** Capability description used for this model proposal, never executable tool state. */
    val toolOntology: Series<String> = emptySeriesOf(),
    /** The EXACT per-call receipt ModelMux minted for [model] — success or failure. Null on records from before this field existed. */
    val receipt: ModelResponseReceipt? = null,
    /** One receipt per executed pipeline stage, in execution order. Empty on records from before receipts existed. */
    val toolReceipts: Series<DocumentToolReceipt> = emptySeriesOf(),
    /** Candidates exactly as [NlpcoreAxiomatics] produced them for this record. Null on records that predate persistence. */
    val axioms: Series<NlpcoreAxiom>? = null,
)

/**
 * Parser-derived causal/conditional candidates — never auto-admitted into a live rete.
 * Records that persist [DocumentCurationRecord.axioms] read them back exactly; older
 * records recompute from the retained [DocumentCurationRecord.nlp] under the same gate
 * [DocumentCurationIndexK.NlpStatus] applies to every other derived view: an invalid
 * parse (or a source that never verified against CAS) yields no candidates, though the
 * raw NLP stays inspectable on the record itself. Promoting one of these into an admitted
 * [EternalRule] is the separate, explicit `nal.rule.admit` operation.
 */
val DocumentCurationRecord.nlpAxioms: Series<NlpcoreAxiom>
    get() {
        val document = nlp ?: return emptySeriesOf()
        if (reasons.values().any { "source CID" in it }) return emptySeriesOf()
        if (curationIndex().facet(DocumentCurationIndexK.NlpStatus) != DocumentNlpStatus.AVAILABLE) return emptySeriesOf()
        // The gate above applies to persisted candidates exactly as to recomputed ones.
        axioms?.let { return it }
        return NlpcoreAxiomatics.recognize(document, source.originalCid.value)
    }

/**
 * The retained receipt cids of the stages that read the text (NLP, model, grounding), in stage
 * order: the evidence leaves every belief of this record cites beside the source cid. Empty on
 * records from before stage receipts existed, so their basis is unchanged on replay.
 */
val DocumentCurationRecord.readingReceiptCids: Series<ContentId>
    get() = toolReceipts.values()
        .filter { it.stage != DocumentToolStage.AXIOMATICS }
        .mapNotNull { it.receiptCid }
        .toSeries()

typealias DocumentCurationReceipt = Join<ContentId, DocumentCurationRecord>

data class DocumentCurationResult(
    val recordCid: ContentId,
    val record: DocumentCurationRecord,
    val attributions: Series<DocumentAttribution>,
    /** Includes late tap failures without requiring another business-ledger transaction. */
    val observerFailures: Series<String> = record.observerFailures,
) {
    val submittedReceiptCids: Series<ContentId> get() = record.submittedReceiptCids
    val acceptedReceiptCids: Series<ContentId> get() = submittedReceiptCids
    val pendingReceiptCids: Series<ContentId> get() {
        val candidates = record.proposals.values().filter { it.reasons.size > 0 }.mapNotNull { it.receiptCid }
        return (candidates + if (record.reasons.size > 0) listOf(recordCid) else emptyList()).distinct().toSeries()
    }
    val unresolvedReasons: Series<String> get() = (record.reasons.values() +
        record.proposals.values().flatMap { it.reasons.values() }).distinct().toSeries()
    /** See [DocumentCurationRecord.nlpAxioms] — retained candidates, never auto-admitted. */
    val nlpAxioms: Series<NlpcoreAxiom> get() = record.nlpAxioms
}

fun interface DocumentCuratorObserver {
    suspend fun boundary(name: String, correlation: String, refs: Series<ContentId>)
}
