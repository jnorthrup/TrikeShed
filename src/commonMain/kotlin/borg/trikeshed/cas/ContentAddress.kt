package borg.trikeshed.cas

import borg.trikeshed.job.ContentId

/**
 * Common Content Address interface for CAS implementations.
 */
interface ContentAddress {
    val id: ContentId
    val size: Long
}
