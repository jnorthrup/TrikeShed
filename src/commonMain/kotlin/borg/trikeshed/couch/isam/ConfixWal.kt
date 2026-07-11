package borg.trikeshed.couch.isam

import borg.trikeshed.lib.Series
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.parse.confix.ConfixDoc

/**
 * Append-only Write-Ahead Log for Confix Documents.
 * Serves as the durability layer before compaction into the ISAM K-V Stringpool layout.
 */
class ConfixWal(
    val walFileLocation: String,
    val fileOps: FileOperations
) {
    private var sequenceNumber: Long = 0L

    /**
     * Appends a document mutation to the WAL.
     * @param id The document ID (e.g. CID or user key)
     * @param rev The document revision
     * @param doc The actual ConfixDoc to append
     * @return The monotonic sequence number of this mutation
     */
    fun append(id: String, rev: String, doc: ConfixDoc): Long {
        // In a true implementation, we serialize `doc` to bytes using ConfixSerialization
        // and write [Seq(Long) | IdLen | IdBytes | RevLen | RevBytes | DocLen | DocBytes]
        // to `fileOps.newByteChannel(walFileLocation, StandardOpenOption.APPEND)`

        sequenceNumber++
        return sequenceNumber
    }

    /**
     * Replays the WAL to rebuild the in-memory or on-disk MemTable/ISAM state.
     */
    fun replay(bridge: ConfixIsamCursorBridge) {
        // Reads from start of WAL.
        // For each entry, it would invoke:
        // val offset = bridge.stringpool.put(docBytes)
        // bridge.index.put(id, offset)
    }

    /**
     * Truncates the WAL after a successful compaction to ISAM disk.
     */
    fun checkpoint() {
        sequenceNumber = 0L
        // fileOps.delete(walFileLocation)
        // fileOps.createFile(walFileLocation)
    }
}
