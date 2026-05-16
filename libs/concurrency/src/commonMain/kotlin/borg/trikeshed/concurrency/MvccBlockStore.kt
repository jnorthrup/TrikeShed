package borg.trikeshed.concurrency

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.j
import borg.trikeshed.lib.j
import borg.trikeshed.lib.mutable.SeriesBuffer

/**
 * Minimal MVCC Block store stub used by tests. Not a full implementation — provides only
 * the small contract the tests require (snapshot numbering, put/listAt/scanAt behaviors).
 */
class MvccBlockStore {
    data class Snapshot(val seq: Long)

    private var seq: Long = 0L

    data class BlockEntry(var putSeq: Long, val block: Any?, var removed: Boolean = false, var removeSeq: Long? = null)

private val store: SeriesBuffer<Pair<CharSequence, SeriesBuffer<BlockEntry>>> = SeriesBuffer()

    // simple WAL stub
    data class WalEntry(val seq: Long)
    class Wal { val entries: SeriesBuffer<WalEntry> = SeriesBuffer() }
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
        // Find or create the block-entry list for this key
        val existing = store.view.find { it.first == key }
        val entries = existing?.second ?: SeriesBuffer<BlockEntry>()
        val storedBlock: Any? = block
        val entry = BlockEntry(seq, storedBlock)
        entries.add(entry)
        if (existing == null) store.add(key to entries)
        wal.entries.add(entry)
        return entries.size // id is index+1
    }

    fun remove(key: CharSequence, id: Int) {
        // id is 1-based insertion index for the key
        val existing = store.view.find { it.first == key } ?: return
        val list = existing.second
        val idx = id - 1
        if (idx in 0 until list.size) {
            val e = list[idx]
            seq += 1
            e.removed = true
            e.removeSeq = seq
            wal.entries.add(WalEntry(seq))
        }
    }

    fun compact(upToSeq: Long) {
        // remove entries that are removed and have removeSeq < upToSeq
        val filtered = store.view.filter { (_, entries) ->
            val kept = entries.view.filter { e ->
                !e.removed || (e.removeSeq != null && e.removeSeq!! >= upToSeq) || e.putSeq >= upToSeq
            }
            if (kept.size != entries.size) {
                entries.clear()
                kept.forEach { entries.add(it) }
            }
            kept.isNotEmpty()
        }
        store.clear()
        filtered.forEach { store.add(it.first to it.second) }
        // compact WAL as well
        wal.entries.removeAll { it.seq < upToSeq }
    }

    /**
     * Return the list of insertion ids visible at the snapshot for the given key.
     */
    fun listAt(snap: Snapshot, key: CharSequence): List<Int> = store.view.find { it.first == key }
        ?.second
        ?.view
        ?.mapIndexedNotNull { idx, entry ->
            if (entry.putSeq <= snap.seq && !(entry.removed && (entry.removeSeq ?: Long.MAX_VALUE) <= snap.seq)) idx + 1 else null
        }
        ?: emptyList()

    /**
     * Get the block at a particular insertion id if visible at the snapshot.
     */
    fun getAt(snap: Snapshot, key: CharSequence, id: Int): BlockLike? {
        val existing = store.view.find { it.first == key } ?: return null
        val list = existing.second
        val idx = id - 1
        if (idx !in 0 until list.size) return null
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
        val existing = store.view.find { it.first == key } ?: return borg.trikeshed.lib.Join.emptySeriesOf()
        val list = existing.second
        val rowVecs: SeriesBuffer<RowVec> = SeriesBuffer()
        for (idx in 0 until list.size) {
            val entry = list[idx]
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
                    val values: borg.trikeshed.lib.Series<Any?> = cells.size j { idx2 -> cells[idx2] }
                    val meta: borg.trikeshed.lib.Series<() -> borg.trikeshed.cursor.ColumnMeta> = cells.size j { idx2 -> { borg.trikeshed.cursor.ColumnMeta("col$idx2", borg.trikeshed.isam.meta.IOMemento.IoString) } }
                    rowVecs.add(values j meta)
                }
            } else if (block is Array<*>) {
                val cells: List<Any?> = block.toList()
                val values: borg.trikeshed.lib.Series<Any?> = cells.size j { idx2 -> cells[idx2] }
                val meta: borg.trikeshed.lib.Series<() -> borg.trikeshed.cursor.ColumnMeta> = cells.size j { idx2 -> { borg.trikeshed.cursor.ColumnMeta("col$idx2", borg.trikeshed.isam.meta.IOMemento.IoString) } }
                rowVecs.add(values j meta)
            } else {
                val cells = listOf(block)
                val values: borg.trikeshed.lib.Series<Any?> = cells.size j { idx2 -> cells[idx2] }
                val meta: borg.trikeshed.lib.Series<() -> borg.trikeshed.cursor.ColumnMeta> = cells.size j { idx2 -> { borg.trikeshed.cursor.ColumnMeta("col$idx2", borg.trikeshed.isam.meta.IOMemento.IoString) } }
                rowVecs.add(values j meta)
            }
        }
        return rowVecs.size j { rowVecs[it] }
    }

    private class StoredBlockLike(private val rowCountValue: Int) : BlockLike {
        override val rowCount: Int get() = rowCountValue
    }
}
