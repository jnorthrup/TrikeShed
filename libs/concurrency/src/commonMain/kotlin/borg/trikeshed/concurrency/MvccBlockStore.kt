package borg.trikeshed.concurrency

import borg.trikeshed.cursor.j
import borg.trikeshed.lib.j

/**
 * Minimal MVCC Block store stub used by tests. Not a full implementation — provides only
 * the small contract the tests require (snapshot numbering, put/listAt/scanAt behaviors).
 */
class MvccBlockStore {
    data class Snapshot(val seq: Long)

    private var seq: Long = 0L

    data class BlockEntry(var putSeq: Long, val block: Any?, var removed: Boolean = false, var removeSeq: Long? = null)

    private val store: LinkedHashMap<CharSequence, LinkedList<BlockEntry>> = linkedMapOf()

    // simple WAL stub
    data class WalEntry(val seq: Long)
    class Wal { val entries: LinkedList<WalEntry> = LinkedList() }
    val wal: Wal = Wal()

    // exposed block meta for tests (removeSeq is normalized to a non-nullable long for tests)
    data class BlockMeta(val putSeq: Long, val removed: Boolean, val removeSeq: Long)
    val blocks: List<BlockMeta>
        get() = store.values.flatMap { list -> list.map { BlockMeta(it.putSeq, it.removed, it.removeSeq ?: Long.MIN_VALUE) } }

    fun snapshot(): Snapshot = Snapshot(seq)

    /**
     * Put a block into the named collection. Returns a stable 1-based insertion id for the key.
     */
    fun put(key: CharSequence, block: Any?): Int {
        seq += 1
        val list = store.getOrPut(key) { mutableListOf() }
        // Store the original block object so scans can inspect elements when needed
        val storedBlock: Any? = block
        val entry = BlockEntry(seq, storedBlock)
        list.add(entry)
        wal.entries.add(WalEntry(seq))
        return list.size // id is index+1
    }

    fun remove(key: CharSequence, id: Int) {
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
            store[k] = retained.toCollection(LinkedList())
        }
        // compact WAL as well
        wal.entries.removeAll { it.seq < upToSeq }
    }

    /**
     * Return the list of insertion ids visible at the snapshot for the given key.
     */
    fun listAt(snap: Snapshot, key: CharSequence): List<Int> = store[key]
        ?.mapIndexedNotNull { idx, entry ->
            if (entry.putSeq <= snap.seq && !(entry.removed && (entry.removeSeq ?: Long.MAX_VALUE) <= snap.seq)) idx + 1 else null
        }
        ?: emptyList()

    /**
     * Get the block at a particular insertion id if visible at the snapshot.
     */
    fun getAt(snap: Snapshot, key: CharSequence, id: Int): BlockLike? {
        val list = store[key] ?: return null
        val idx = id - 1
        if (idx !in list.indices) return null
        val entry = list[idx]
        if (entry.putSeq > snap.seq) return null
        if (entry.removed && (entry.removeSeq ?: Long.MAX_VALUE) <= snap.seq) return null
        return when (val block = entry.block) {
            is BlockLike -> block
            is Collection<*> -> StoredBlockLike(block.size)
            is Array<*> -> StoredBlockLike(block.size)
            else -> null
        }
    }

    // Scan visible blocks at snapshot and produce a Cursor of RowVec rows used by tests.
    fun scanAt(snap: Snapshot, key: CharSequence): borg.trikeshed.cursor.Cursor {
        val list = store[key] ?: return borg.trikeshed.lib.Join.emptySeriesOf()
        val rowVecs: LinkedList<borg.trikeshed.cursor.RowVec> = LinkedList()
        for (entry in list) {
            if (entry.putSeq > snap.seq) continue
            if (entry.removed && (entry.removeSeq ?: Long.MAX_VALUE) <= snap.seq) continue
            val block = entry.block
            if (block is Collection<*>) {
                for (elem in block) {
                    val cells: List<Any?> = when (elem) {
                        is Collection<*> -> elem.toList()
                        is Array<*> -> elem.toList()
                        else -> listOf(elem)
                    }
                    val values: borg.trikeshed.lib.Series<Any?> = cells.size j { idx -> cells[idx] }
                    val meta: borg.trikeshed.lib.Series<() -> borg.trikeshed.cursor.ColumnMeta> = cells.size j { idx -> { borg.trikeshed.cursor.ColumnMeta("col$idx", borg.trikeshed.isam.meta.IOMemento.IoString) } }
                    rowVecs.add(values j meta)
                }
            } else if (block is Array<*>) {
                val cells: List<Any?> = block.toList()
                val values: borg.trikeshed.lib.Series<Any?> = cells.size j { idx -> cells[idx] }
                val meta: borg.trikeshed.lib.Series<() -> borg.trikeshed.cursor.ColumnMeta> = cells.size j { idx -> { borg.trikeshed.cursor.ColumnMeta("col$idx", borg.trikeshed.isam.meta.IOMemento.IoString) } }
                rowVecs.add(values j meta)
            } else {
                val cells = listOf(block)
                val values: borg.trikeshed.lib.Series<Any?> = cells.size j { idx -> cells[idx] }
                val meta: borg.trikeshed.lib.Series<() -> borg.trikeshed.cursor.ColumnMeta> = cells.size j { idx -> { borg.trikeshed.cursor.ColumnMeta("col$idx", borg.trikeshed.isam.meta.IOMemento.IoString) } }
                rowVecs.add(values j meta)
            }
        }
        return rowVecs.size j { r -> rowVecs[r] }
    }

    private class StoredBlockLike(private val rowCountValue: Int) : BlockLike {
        override val rowCount: Int get() = rowCountValue
    }
}
