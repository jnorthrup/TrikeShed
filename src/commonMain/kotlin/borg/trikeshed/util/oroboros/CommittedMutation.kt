package borg.trikeshed.util.oroboros

import borg.trikeshed.job.ContentId

/**
 * Represents a successfully coordinated mutation applied across all lanes.
 */
data class CommittedMutation(
    val seq: Long,
    val agent: String,
    val path: String,
    val cid: ContentId?,
    val action: String, // e.g. "Upsert" or "Delete"
    val revision: String
)
