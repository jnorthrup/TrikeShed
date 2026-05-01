package borg.trikeshed.concurrency

import borg.trikeshed.miniduck.*
import borg.trikeshed.miniduck.tablespace.*
import borg.trikeshed.lib.*

/**
 * MVCC snapshot — a point-in-time view of the block store.
 *
 * Captures the WAL sequence at creation time. All reads at this snapshot
 * see only blocks that were put at or before this sequence.
 *
 * Donor: CouchDB MVCC (_rev), PostgreSQL MVCC (xmin/xmax).
 */
data class MvccSnapshot(
    val seq: Long,
)

/**
 * Metadata about a block's put operation — used to filter by snapshot sequence.
 */data class BlockMeta(
    val blockId: String,
    val collection: String,
    val putSeq: Long,
    val block: BlockRowVec,
    val removed: Boolean = false,
    val removeSeq: Long = 0L,
)

/**
 * Interface defining the MVCC BlockStore API.
 */
interface MvccStore {
    fun put(collection: String, block: BlockRowVec): String
    fun remove(collection: String, blockId: String): Boolean
    fun snapshot(): MvccSnapshot
    fun listAt(snapshot: MvccSnapshot, collection: String): List<String>
    fun getAt(snapshot: MvccSnapshot, collection: String, blockId: String): BlockRowVec?
    fun scanAt(snapshot: MvccSnapshot, collection: String): borg.trikeshed.cursor.Cursor
    fun compact(keepFromSeq: Long)
}

/**
 * MVCC BlockStore — wraps a WAL-backed BlockStore with snapshot isolation.
 *
 * Readers at a snapshot see only blocks that were sealed at or before the snapshot's sequence.
 * Writers don't block readers — each snapshot is a consistent point-in-time view.
 *
 * Donor: CouchDB MVCC, PostgreSQL MVCC, Raft snapshot.
 */
class MvccBlockStore(val wal: InMemoryBlockWal = InMemoryBlockWal(), val backingStore: BlockStore = InMemoryBlockStore()) : MvccStore {

   val blocks = mutableListOf<BlockMeta>()

    init {
        // Replay existing WAL into backing store and build BlockMeta index
        wal.entries.forEach { entry ->
            when (val op = entry.op) {
                is WalOp.Put -> {
                    blocks.add(BlockMeta(op.blockId, op.collection, entry.seq, op.block))
                    // apply to store as well
                    wal.applyOp(backingStore, op)
                }
                is WalOp.Remove -> {
                    val meta = blocks.lastOrNull { it.blockId == op.blockId && it.collection == op.collection && !it.removed }
                    if (meta != null) {
                        blocks.add(BlockMeta(op.blockId, op.collection, entry.seq, meta.block, removed = true, removeSeq = entry.seq))
                    }
                    wal.applyOp(backingStore, op)
                }
            }
        }
    }

    /**
     * Put a sealed block into [collection]. Returns the assigned blockId.
     * Increments the MVCC sequence via WAL append.
     */
    override fun put(collection: String, block: BlockRowVec): String {
        val blockId = "blk-${wal.headSequence + 1}"
        val seq = wal.append(WalOp.Put(collection, blockId, block))
        blocks.add(BlockMeta(blockId, collection, seq, block))
        // also put in backing store directly? applyOp handles putWithId for InMemoryBlockStore,
        // but for a general BlockStore, it needs to work. We'll let wal.applyOp handle it.
        wal.applyOp(backingStore, WalOp.Put(collection, blockId, block))
        return blockId
    }

    /**
     * Remove a block. Increments the MVCC sequence via WAL append.
     * The block is not physically removed from the index — it's marked as removed at the new sequence.
     */
    override fun remove(collection: String, blockId: String): Boolean {
        val meta = blocks.lastOrNull { it.blockId == blockId && it.collection == collection && !it.removed }
            ?: return false
        val seq = wal.append(WalOp.Remove(collection, blockId))
        // Mark as removed (we don't actually remove from the list, just record the removal)
        blocks.add(BlockMeta(blockId, collection, seq, meta.block, removed = true, removeSeq = seq))
        wal.applyOp(backingStore, WalOp.Remove(collection, blockId))
        return true
    }

    /**
     * Create a snapshot at the current sequence.
     */
    override fun snapshot(): MvccSnapshot = MvccSnapshot(wal.headSequence)

    /**
     * List blockIds visible at the given snapshot in [collection].
     * A block is visible if it was put at or before the snapshot's sequence
     * and not removed before or at the snapshot's sequence.
     */
    override fun listAt(snapshot: MvccSnapshot, collection: String): List<String> {
        // Track the latest state of each blockId at or before snapshot.seq
        val visible = mutableMapOf<String, BlockMeta>()

        for (meta in blocks) {
            if (meta.collection != collection) continue
            if (meta.putSeq > snapshot.seq) continue

            if (meta.removed) {
                // Removal at or before snapshot → block is not visible
                if (meta.removeSeq <= snapshot.seq) {
                    visible.remove(meta.blockId)
                }
            } else {
                // Put at or before snapshot → block is visible (unless later removed)
                visible[meta.blockId] = meta
            }
        }

        return visible.keys.toList()
    }

    /**
     * Get a block at the given snapshot. Returns null if not visible.
     */
    override fun getAt(snapshot: MvccSnapshot, collection: String, blockId: String): BlockRowVec? {
        val visibleIds = listAt(snapshot, collection)
        if (blockId !in visibleIds) return null
        return blocks.lastOrNull {
            it.blockId == blockId && it.collection == collection && !it.removed && it.putSeq <= snapshot.seq
        }?.block
    }

    /**
     * Scan all blocks in [collection] at the given snapshot, returning a merged MiniCursor.
     * Uses a lazy iterator rather than materializing all rows upfront.
     */
    override fun scanAt(snapshot: MvccSnapshot, collection: String): borg.trikeshed.cursor.Cursor {
        val visibleIds = listAt(snapshot, collection)
        val visibleBlocks = visibleIds.mapNotNull { blockId ->
            getAt(snapshot, collection, blockId)
        }.filter { it.state == BlockRowVec.State.SEALED }

        // Create a lazy cursor over the blocks
        var totalSize = 0
        val blockOffsets = mutableListOf<Int>()
        val blockSizes = mutableListOf<Int>()
        val blockSeriesList = mutableListOf<Series<MiniRowVec>>()

        for (block in visibleBlocks) {
            val childSeries = block.child as? Series<MiniRowVec> ?: continue
            blockOffsets.add(totalSize)
            blockSizes.add(childSeries.size)
            blockSeriesList.add(childSeries)
            totalSize += childSeries.size
        }

        return totalSize j { index: Int ->
            // Binary search to find which block this index belongs to
            var low = 0
            var high = blockOffsets.size - 1
            var blockIdx = -1

            while (low <= high) {
                val mid = (low + high) / 2
                val offset = blockOffsets[mid]
                if (index < offset) {
                    high = mid - 1
                } else if (index >= offset + blockSizes[mid]) {
                    low = mid + 1
                } else {
                    blockIdx = mid
                    break
                }
            }

            if (blockIdx == -1) {
                throw IndexOutOfBoundsException("Index $index out of bounds for lazy scan cursor of size $totalSize")
            }

            val relativeIndex = index - blockOffsets[blockIdx]
            val row = blockSeriesList[blockIdx][relativeIndex]
            row.toRowVec()
        }
    }

    /**
     * Compact the MVCC history: removes metadata entries that are older than [keepFromSeq]
     * and no longer active. Compacting the underlying WAL is also performed.
     */
    override fun compact(keepFromSeq: Long) {
        val keepBlocks = mutableListOf<BlockMeta>()

        // We only keep the latest state of each block that was modified before keepFromSeq,
        // and all states after keepFromSeq.
        val latestBefore = mutableMapOf<Pair<String, String>, BlockMeta>()

        for (meta in blocks) {
            if (meta.putSeq < keepFromSeq || (meta.removed && meta.removeSeq < keepFromSeq)) {
                latestBefore[Pair(meta.collection, meta.blockId)] = meta
            } else {
                keepBlocks.add(meta)
            }
        }

        // Add back the latest state before keepFromSeq if it wasn't removed
        for ((_, meta) in latestBefore) {
            if (!meta.removed || meta.removeSeq >= keepFromSeq) {
                 keepBlocks.add(meta)
            }
        }

        blocks.clear()
        blocks.addAll(keepBlocks.sortedBy { it.putSeq })

        wal.compact(keepFromSeq)
    }
}
