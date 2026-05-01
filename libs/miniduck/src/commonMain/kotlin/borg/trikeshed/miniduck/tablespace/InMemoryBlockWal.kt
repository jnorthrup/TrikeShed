package borg.trikeshed.miniduck.tablespace

import borg.trikeshed.miniduck.BlockRowVec

/**
 * WAL operations — what the WAL records.
 *
 * Put: a sealed block was written to a collection.
 * Remove: a block was removed from a collection.
 */
sealed class WalOp {
    abstract val collection: String
    abstract val blockId: String

    data class Put(
        override val collection: String,
        override val blockId: String,
        val block: BlockRowVec,
    ) : WalOp()

    data class Remove(
        override val collection: String,
        override val blockId: String,
    ) : WalOp()
}

/**
 * A single WAL entry: a sequence number paired with an operation.
 */
data class WalEntry(
    val seq: Long,
    val op: WalOp,
)

/**
 * In-memory WAL for block-store operations.
 *
 * Every put/remove first appends to the WAL. On crash, replay the WAL
 * to reconstruct the BlockStore state. Sequence numbers are monotonic
 * and gap-free — they're the MVCC clock.
 *
 * Donor patterns: LSMRWal skeleton, CouchDB _changes feed, Raft log.
 */
class InMemoryBlockWal {

   val entries = mutableListOf<WalEntry>()
    var headSequence: Long = 0L
       set

    /**
     * Append an operation to the WAL. Returns the assigned sequence number.
     * Sequence numbers start at 1 and increment monotonically.
     */
    fun append(op: WalOp): Long {
        headSequence++
        entries.add(WalEntry(headSequence, op))
        return headSequence
    }

    /**
     * Read entries in the range [from, to) — inclusive of [from], exclusive of [to].
     */
    fun readRange(from: Long, to: Long): List<WalEntry> =
        entries.filter { it.seq >= from && it.seq < to }

    /**
     * Read all entries with seq >= [from].
     */
    fun readFrom(from: Long): List<WalEntry> =
        entries.filter { it.seq >= from }

    /**
     * Replay all WAL entries to reconstruct the state of the given BlockStore.
     * Idempotent: calling replay twice on an empty store produces the same result.
     */
    fun replay(store: BlockStore) {
        for (entry in entries) {
            applyOp(store, entry.op)
        }
    }

    /**
     * Replay WAL entries up to and including [toSeq].
     * Used for MVCC snapshot reconstruction.
     */
    fun replayTo(store: BlockStore, toSeq: Long) {
        for (entry in entries) {
            if (entry.seq > toSeq) break
            applyOp(store, entry.op)
        }
    }

    /**
     * Compact the WAL: remove all entries before [keepFromSeq].
     * After compaction, readFrom(1) will only return entries >= keepFromSeq.
     */
    fun compact(keepFromSeq: Long) {
        entries.removeAll { it.seq < keepFromSeq }
    }

   fun applyOp(store: BlockStore, op: WalOp) {
        when (op) {
            is WalOp.Put -> {
                // Use the blockId from the WAL entry, not the auto-generated one
                val collection = store
                collection.putWithId(op.collection, op.blockId, op.block)
            }
            is WalOp.Remove -> {
                store.remove(op.collection, op.blockId)
            }
        }
    }
}
