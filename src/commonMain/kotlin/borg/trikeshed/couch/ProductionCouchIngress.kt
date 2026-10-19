package borg.trikeshed.couch

import borg.trikeshed.job.ContentId
import borg.trikeshed.context.*

/**
 * Production Ingress that generates CAS-based revisions and submits to an injected
 * commit boundary, ensuring no pre-commit visibility and avoiding direct projection mutation.
 */
/** CouchDB 1.x deterministic winner: higher generation, then lexicographically greater hash. */
fun revWins(candidate: String, incumbent: String): Boolean {
    val cg = candidate.substringBefore("-").toLongOrNull() ?: 0L
    val ig = incumbent.substringBefore("-").toLongOrNull() ?: 0L
    if (cg != ig) return cg > ig
    return candidate.substringAfter("-") > incumbent.substringAfter("-")
}

class ProductionCouchIngress(
    private val head: CouchHeadProjection,
    private val commitBoundary: (CouchCommittedFrame) -> Unit,
    private val contentIdFn: (Document) -> ContentId,
    initialSequence: Long = 0,
    val synchronization: Synchronization = Synchronization.MONITOR,
) : CouchIngress {
    private var sequence: Long = initialSequence

    // Shared writers serialize revision selection, sequence allocation and publication together.
    // NONE requires the caller to own that same boundary, including all replicated mutations.
    private val commitLock = synchronization.lock()

    override fun putIntent(doc: Document, expectedRev: String?): Boolean = synchronize(commitLock) {
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

    override fun putReplicated(doc: Document?, docId: String, rev: String, deleted: Boolean): Boolean = synchronize(commitLock) {
        val existingRev = head.getRev(docId)
        if (existingRev == rev) return true // already the head: idempotent
        if (existingRev != null && !revWins(rev, existingRev)) return false
        require(deleted || (doc != null && doc.id == docId)) { "replicated frame needs a document" }
        val frame = CouchCommittedFrame(sequence++, docId, rev, deleted, if (deleted) null else doc)
        commitBoundary(frame)
        return true
    }

    override fun deleteIntent(docId: String, expectedRev: String?): Boolean = synchronize(commitLock) {
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
