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
    private val store: MutableMap<String, MutableList<Pair<Long, BlockRowVec>>> = mutableMapOf()

    fun snapshot(): Snapshot = Snapshot(seq)

    fun put(key: String, block: BlockRowVec): Int {
        seq += 1
        val list = store.getOrPut(key) { mutableListOf() }
        list.add(seq to block)
        return list.size
    }

    fun listAt(snap: Snapshot, key: String): List<BlockRowVec> = store[key]
        ?.filter { it.first <= snap.seq }
        ?.map { it.second }
        ?: emptyList()

    // Simple cursor-like structure used only by the tests that inspect rows/values
    class SimpleRow(val values: List<Any?>)
    class SimpleCursor(private val rows: List<SimpleRow>) {
        val size: Int get() = rows.size
        fun row(i: Int): SimpleRow = rows[i]
    }

    fun scanAt(snap: Snapshot, key: String): SimpleCursor {
        val blocks = listAt(snap, key)
        val rows = mutableListOf<SimpleRow>()
        for (b in blocks) {
            val docs = try {
                b.getSealedRows()
            } catch (e: Throwable) {
                emptyList<DocRowVec>()
            }
            for (d in docs) {
                rows.add(SimpleRow(d.cells))
            }
        }
        return SimpleCursor(rows)
    }
}
