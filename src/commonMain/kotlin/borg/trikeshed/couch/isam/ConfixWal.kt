package borg.trikeshed.couch.isam

import borg.trikeshed.parse.confix.ConfixDoc

/**
 * Append-only Write-Ahead Log for Confix Documents.
 * Re-added to fix compiler errors in ConfixDocStore, but we don't actually use it for JobRepository.
 * We can provide a basic implementation to keep the code compiling.
 */
class ConfixWal(
    val walFileLocation: String,
    // The previous implementation used fileOps, but we can just use the new DurableAppendLog
    val log: DurableAppendLog? = null
) {
    private var sequenceNumber: Long = 0L

    fun append(id: String, rev: String, doc: ConfixDoc): Long {
        sequenceNumber++
        // Log to DurableAppendLog if provided
        log?.append(sequenceNumber, "$id:$rev".encodeToByteArray())
        return sequenceNumber
    }

    fun checkpoint() {
        sequenceNumber = 0L
    }
}
