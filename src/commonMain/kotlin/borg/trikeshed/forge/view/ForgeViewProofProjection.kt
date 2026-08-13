package borg.trikeshed.forge.view

import borg.trikeshed.job.ContentId
import borg.trikeshed.viewserver.MapReduceProofReceipt

/** Verdict produced by replay verification before a receipt becomes Forge-observable. */
enum class ViewProofVerification {
    Verified,
    Rejected,
}

/**
 * Immutable Forge row for a completed, replay-verified map/reduce execution.
 *
 * This is observation only: receipt validation remains at the execution boundary.
 */
data class ForgeViewProofProjection(
    val viewDefinitionCid: ContentId,
    val receiptCid: ContentId,
    val outputCid: ContentId,
    val inputCidCount: Int,
    val reducerName: String,
    val reducerVersion: String,
    val verification: ViewProofVerification,
)

/** Exposes only verified receipt provenance to Forge. */
fun MapReduceProofReceipt.projectToForge(
    verification: ViewProofVerification,
): ForgeViewProofProjection? {
    if (verification != ViewProofVerification.Verified) return null
    return ForgeViewProofProjection(
        viewDefinitionCid = ContentId.of(viewDefinition.canonicalBytes),
        receiptCid = contentId,
        outputCid = ContentId.of(outputBytes),
        inputCidCount = sourceDocumentCids.size,
        reducerName = reducer.name,
        reducerVersion = reducer.version,
        verification = verification,
    )
}
