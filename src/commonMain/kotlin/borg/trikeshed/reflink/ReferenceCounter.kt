package borg.trikeshed.reflink

import borg.trikeshed.job.ContentId

/**
 * Interface for tracking reference counts of ContentIds.
 */
interface ReferenceCounter {
    fun increment(cid: ContentId)
    fun decrement(cid: ContentId)
    fun getCount(cid: ContentId): Long
}

/**
 * In-memory implementation of ReferenceCounter.
 */
class InMemoryReferenceCounter : ReferenceCounter {
    private val counts = mutableMapOf<ContentId, Long>()

    override fun increment(cid: ContentId) {
        counts[cid] = (counts[cid] ?: 0L) + 1L
    }

    override fun decrement(cid: ContentId) {
        val count = counts[cid] ?: 0L
        if (count > 1) {
            counts[cid] = count - 1L
        } else {
            counts.remove(cid)
        }
    }

    override fun getCount(cid: ContentId): Long {
        return counts[cid] ?: 0L
    }
}
