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
    data class Put(val collection: CharSequence, val id: CharSequence, val block: BlockRowVec) : WalOp()
    data class Remove(val collection: CharSequence, val id: CharSequence) : WalOp()
}

class InMemoryBlockWal {
    private val entries = LongSeries.build { it += <WalEntry>() })
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
        entries.filter { it.a >= start && it.a < end }

    fun readFrom(start: Long): List<WalEntry> = entries.filter { it.a >= start }

    fun compact(keepFromSeq: Long) {
        val idx = entries.indexOfFirst { it.a >= keepFromSeq }
        if (idx > 0) {
            for (i in 0 until idx) entries.removeAt(0)
        }
    }

    fun replay(store: BlockStore) = replayTo(store, Long.MAX_VALUE)

    fun replayTo(store: BlockStore, uptoSeq: Long) {
        for (e in entries) {
            if (e.a > uptoSeq) break
            applyToStore(e.b, store)
        }
    }

    private fun applyToStore(op: WalOp, store: BlockStore) {
        when (op) {
            is WalOp.Put -> store.putWithId(op.collection, op.id, op.block)
            is WalOp.Remove -> store.remove(op.collection, op.id)
        }
    }
}
