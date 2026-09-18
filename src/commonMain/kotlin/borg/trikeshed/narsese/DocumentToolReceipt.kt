package borg.trikeshed.narsese

import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf

/** The stages the curator pipeline actually executes. The toolOntology roster is a description, never a stage. */
enum class DocumentToolStage { NLP, MODEL, GROUNDING, AXIOMATICS }

enum class DocumentToolStatus { SUCCESS, FAILURE, SKIPPED }

/**
 * Provenance of one stage execution: which tool ran, under which implementation and
 * configuration, over which exact CAS artifacts, when, and how it ended. Retained on
 * success, failure and skip alike. Times are epoch milliseconds observed around the actual
 * call; a null time means the stage never started. Records written before this type existed
 * carry no receipts, and none are invented for them.
 */
data class DocumentToolReceipt(
    val stage: DocumentToolStage,
    val tool: String,
    val implementation: String,
    val status: DocumentToolStatus,
    val configuration: Map<String, String> = emptyMap(),
    val inputCids: Series<ContentId> = emptySeriesOf(),
    val outputCids: Series<ContentId> = emptySeriesOf(),
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val error: String? = null,
    /** Identity of this receipt's own canonical bytes in CAS; excluded from those bytes. Null until retained. */
    val receiptCid: ContentId? = null,
) {
    companion object {
        /** Host stage implementations are named by qualified class and this constant; no git hash is claimed. */
        const val HOST_VERSION = 1

        fun host(qualifiedClass: String): String = "$qualifiedClass#$HOST_VERSION"
    }
}

internal fun epochMillis(): Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()

/** Retains the exact canonical bytes of a stage artifact and returns their identity. */
internal fun stageArtifact(cas: CasStore, fields: Map<String, Any?>): ContentId =
    putVerified(cas, CanonicalCbor.encodeMap(fields))

/** Retains the receipt's canonical bytes; [DocumentToolReceipt.receiptCid] names them and is excluded from them. */
internal fun DocumentToolReceipt.retain(cas: CasStore): DocumentToolReceipt =
    copy(receiptCid = stageArtifact(cas, DocumentCuratorCodec.toolReceipt(copy(receiptCid = null))))
