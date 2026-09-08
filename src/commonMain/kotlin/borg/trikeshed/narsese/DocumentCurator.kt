package borg.trikeshed.narsese

import borg.trikeshed.job.ContentId
import borg.trikeshed.kif.KifExpr
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.modelmux.ModelResponse
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
)

/** Only [expression]'s outer source attribution is submitted, never its quoted proposition. */
data class DocumentAttribution(
    val receiptCid: ContentId,
    val expression: KifExpr,
    val mapped: KgNalBridge.NalMapped,
    val signal: SemanticSignal,
    val evidenceBasis: EvidenceBasis,
)

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
)

data class DocumentCurationResult(
    val recordCid: ContentId,
    val record: DocumentCurationRecord,
    val attributions: Series<DocumentAttribution>,
) {
    val submittedReceiptCids: Series<ContentId> get() = record.submittedReceiptCids
    val acceptedReceiptCids: Series<ContentId> get() = submittedReceiptCids
    val pendingReceiptCids: Series<ContentId> get() {
        val candidates = record.proposals.values().filter { it.reasons.size > 0 }.mapNotNull { it.receiptCid }
        return (candidates + if (record.reasons.size > 0) listOf(recordCid) else emptyList()).distinct().toSeries()
    }
    val unresolvedReasons: Series<String> get() = (record.reasons.values() +
        record.proposals.values().flatMap { it.reasons.values() }).distinct().toSeries()
}

fun interface DocumentCuratorObserver {
    suspend fun boundary(name: String, correlation: String, refs: Series<ContentId>)
}
