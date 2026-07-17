package borg.trikeshed.couch

import borg.trikeshed.job.ContentId

/**
 * Production Ingress that generates CAS-based revisions and submits to an injected
 * commit boundary, ensuring no pre-commit visibility and avoiding direct projection mutation.
 */
class ProductionCouchIngress(
    private val head: CouchHeadProjection,
    private val commitBoundary: (CouchCommittedFrame) -> Unit,
    private val contentIdFn: (Document) -> ContentId
) : CouchIngress {
    private var sequence: Long = 0

    override fun putIntent(doc: Document, expectedRev: String?): Boolean {
        val existingRev = head.getRev(doc.id)
        val isDeleted = head.isDeleted(doc.id)

        // CouchDB semantics: If doc exists and is not deleted, an update MUST provide the current rev.
        // If expectedRev is null but doc exists and is not deleted, it's a conflict.
        if (existingRev != null && !isDeleted) {
            if (expectedRev != existingRev) {
                return false // reject stale or null rev on existing doc
            }
        } else if (expectedRev != null) {
            // Document doesn't exist (or is deleted), but a specific rev was expected
            if (existingRev != expectedRev) {
                return false
            }
        }

        val gen = existingRev?.substringBefore("-")?.toIntOrNull() ?: 0
        val nextGen = gen + 1

        val cid = contentIdFn(doc)
        val newRev = "$nextGen-${cid.value}"

        val frame = CouchCommittedFrame(sequence++, doc.id, newRev, false, doc)
        commitBoundary(frame)

        return true // Success for both inserts and updates
    }

    override fun deleteIntent(docId: String, expectedRev: String?): Boolean {
        val existingRev = head.getRev(docId) ?: return false
        val isDeleted = head.isDeleted(docId)
        if (isDeleted) return false

        if (expectedRev != null && existingRev != expectedRev) {
            return false // reject stale
        }

        val gen = existingRev.substringBefore("-").toIntOrNull() ?: 0
        val nextGen = gen + 1
        val newRev = "$nextGen-deleted"

        val frame = CouchCommittedFrame(sequence++, docId, newRev, true, null)
        commitBoundary(frame)

        return true
    }
}
