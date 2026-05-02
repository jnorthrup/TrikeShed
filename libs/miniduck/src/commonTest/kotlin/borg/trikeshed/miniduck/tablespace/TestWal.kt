package borg.trikeshed.miniduck.tablespace

import borg.trikeshed.miniduck.*

/**
 * Minimal in-memory WAL and BlockStore for tests.
 * Keeps the implementation tiny and deterministic for RED tests.
 */

data class WalEntry(val seq: Long, val op: WalOp)

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

    fun replay(store: InMemoryBlockStore) = replayTo(store, Long.MAX_VALUE)

    fun replayTo(store: InMemoryBlockStore, uptoSeq: Long) {
        for (e in entries) {
            if (e.seq > uptoSeq) break
            applyToStore(e.op, store)
        }
    }

    private fun applyToStore(op: WalOp, store: InMemoryBlockStore) {
        when (op) {
            is WalOp.Put -> store.put(op.collection, op.id, op.block)
            is WalOp.Remove -> store.remove(op.collection, op.id)
        }
    }
}

class InMemoryBlockStore {
    private val data = mutableMapOf<String, MutableMap<String, BlockRowVec>>()

    fun put(collection: String, id: String, block: BlockRowVec) {
        val col = data.getOrPut(collection) { mutableMapOf() }
        col[id] = block
    }

    fun get(collection: String, id: String): BlockRowVec? = data[collection]?.get(id)

    fun list(collection: String): List<BlockRowVec> = data[collection]?.values?.toList() ?: emptyList()

    fun remove(collection: String, id: String) { data[collection]?.remove(id) }
}
