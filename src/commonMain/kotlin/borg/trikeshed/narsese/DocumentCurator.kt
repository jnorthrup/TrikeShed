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
)

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
    val toolOntology: borg.trikeshed.modelmux.ToolOntologyScaffold = emptySeriesOf(),
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
