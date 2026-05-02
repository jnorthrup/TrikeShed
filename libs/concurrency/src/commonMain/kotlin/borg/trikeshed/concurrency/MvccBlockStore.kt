package borg.trikeshed.concurrency

import borg.trikeshed.miniduck.BlockRowVec
import borg.trikeshed.miniduck.DocRowVec

/**
 * Minimal MVCC Block store stub used by tests. Not a full implementation — provides only
 * the small contract the tests require (snapshot numbering, put/listAt/scanAt behaviors).
 */
class MvccBlockStore {
    data class Snapshot(val seq: Long)

    private var seq: Long = 0L

    data class BlockEntry(var putSeq: Long, val block: Any?, var removed: Boolean = false, var removeSeq: Long? = null)

    private val store: MutableMap<String, MutableList<BlockEntry>> = mutableMapOf()

    // simple WAL stub
    data class WalEntry(val seq: Long)
    class Wal { val entries: MutableList<WalEntry> = mutableListOf() }
    val wal: Wal = Wal()

    // exposed block meta for tests
    data class BlockMeta(val putSeq: Long, val removed: Boolean, val removeSeq: Long?)
    val blocks: List<BlockMeta>
        get() = store.values.flatMap { list -> list.map { BlockMeta(it.putSeq, it.removed, it.removeSeq) } }

    fun snapshot(): Snapshot = Snapshot(seq)

    /**
     * Put a block into the named collection. Returns a stable 1-based insertion id for the key.
     */
    fun put(key: String, block: Any?): Int {
        seq += 1
        val list = store.getOrPut(key) { mutableListOf() }
        val entry = BlockEntry(seq, block)
        list.add(entry)
        wal.entries.add(WalEntry(seq))
        return list.size // id is index+1
    }

    fun remove(key: String, id: Int) {
        // id is 1-based insertion index for the key
        val list = store[key] ?: return
        val idx = id - 1
        if (idx in list.indices) {
            val e = list[idx]
            seq += 1
            e.removed = true
            e.removeSeq = seq
            wal.entries.add(WalEntry(seq))
        }
    }

    fun compact(upToSeq: Long) {
        // remove entries that are removed and have removeSeq < upToSeq
        for ((k, list) in store) {
            val retained = list.filter { !it.removed || (it.removeSeq != null && it.removeSeq!! >= upToSeq) || it.putSeq >= upToSeq }
            store[k] = retained.toMutableList()
        }
        // compact WAL as well
        wal.entries.removeAll { it.seq < upToSeq }
    }

    /**
     * Return the list of insertion ids visible at the snapshot for the given key.
     */
    fun listAt(snap: Snapshot, key: String): List<Int> = store[key]
        ?.mapIndexedNotNull { idx, entry ->
            if (entry.putSeq <= snap.seq && !(entry.removed && (entry.removeSeq ?: Long.MAX_VALUE) <= snap.seq)) idx + 1 else null
        }
        ?: emptyList()

    /**
     * Get the block at a particular insertion id if visible at the snapshot.
     */
    fun getAt(snap: Snapshot, key: String, id: Int): BlockRowVec? {
        val list = store[key] ?: return null
        val idx = id - 1
        if (idx !in list.indices) return null
        val entry = list[idx]
        if (entry.putSeq > snap.seq) return null
        if (entry.removed && (entry.removeSeq ?: Long.MAX_VALUE) <= snap.seq) return null
        return entry.block as? BlockRowVec
    }

    // Simple cursor-like structure used only by the tests that inspect rows/values
    class SimpleRow(val values: List<Any?>)
    class SimpleCursor(private val rows: List<SimpleRow>) {
        val size: Int get() = rows.size
        fun at(i: Int): SimpleRow = rows[i]
    }

    /**
     * Scan visible blocks at snapshot and produce a simple cursor of rows (each row is the cells of a DocRowVec)
     */
    fun scanAt(snap: Snapshot, key: String): SimpleCursor {
        val list = store[key] ?: return SimpleCursor(emptyList())
        val rows = mutableListOf<SimpleRow>()
        for (entry in list) {
            if (entry.putSeq > snap.seq) continue
            if (entry.removed && (entry.removeSeq ?: Long.MAX_VALUE) <= snap.seq) continue
            val block = entry.block
            when (block) {
                is BlockRowVec -> {
                    val docs = block.child ?: emptyList()
                    for (d in docs) {
                        rows.add(SimpleRow(d.cells))
                    }
                }
                else -> rows.add(SimpleRow(listOf(block)))
            }
        }
        return SimpleCursor(rows)
    }
}
