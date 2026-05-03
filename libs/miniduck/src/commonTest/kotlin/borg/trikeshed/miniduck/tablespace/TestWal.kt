package borg.trikeshed.miniduck.tablespace

import borg.trikeshed.lib.Join
import borg.trikeshed.miniduck.*

/**
 * Minimal in-memory WAL and BlockStore for tests.
 * Keeps the implementation tiny and deterministic for RED tests.
 * Join< /*seek*/Long,/*op*/  WalOp>
 */

typealias WalEntry= Join< /*seek*/Long,/*op*/  WalOp>

sealed class WalOp {
    data class Put(val collection: String, val id: String, val block: BlockRowVec) : WalOp()
    data class Remove(val collection: String, val id: String) : WalOp()
}

class InMemoryBlockWal {
    private val entries = mutableListOf<WalEntry>()
    private var seqCounter = 0L

    val headSequence: Long
        get() = seqCounter

    fun append(op: WalOp): Long {
        seqCounter += 1L
        val e = WalEntry(seqCounter, op)
        entries.add(e)
        return seqCounter
    }

    fun readRange(start: Long, end: Long): List<WalEntry> =
        entries.filter { it.seq >= start && it.seq < end }

    fun readFrom(start: Long): List<WalEntry> = entries.filter { it.seq >= start }

    fun compact(keepFromSeq: Long) {
        val idx = entries.indexOfFirst { it.seq >= keepFromSeq }
        if (idx > 0) {
            // remove earlier entries
            for (i in 0 until idx) entries.removeAt(0)
        }
    }

    fun replay(store: BlockStore) = replayTo(store, Long.MAX_VALUE)

    fun replayTo(store: BlockStore, uptoSeq: Long) {
        for (e in entries) {
            if (e.seq > uptoSeq) break
            applyToStore(e.op, store)
        }
    }

    private fun applyToStore(op: WalOp, store: BlockStore) {
        when (op) {
            is WalOp.Put -> {
                // Use the store's put method which returns the id
                val id = store.put(op.collection, op.block)
                // Note: The WAL stores explicit IDs, but BlockStore generates them
                // For WAL replay, we need to handle this mapping
            }
            is WalOp.Remove -> {
                // BlockStore doesn't have remove in the interface, skip for now
            }
        }
    }
}
