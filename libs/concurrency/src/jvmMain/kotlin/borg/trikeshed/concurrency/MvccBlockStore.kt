package borg.trikeshed.miniduck.mvcc

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
 * MVCC BlockStore — wraps a WAL-backed BlockStore with snapshot isolation.
 *
 * Readers at a snapshot see only blocks that were sealed at or before the snapshot's sequence.
 * Writers don't block readers — each snapshot is a consistent point-in-time view.
 *
 * Donor: CouchDB MVCC, PostgreSQL MVCC, Raft snapshot.
 */
class MvccBlockStore {

   var sequence = 0L
   val blocks = mutableListOf<BlockMeta>()

    /**
     * Put a sealed block into [collection]. Returns the assigned blockId.
     * Increments the MVCC sequence.
     */
    fun put(collection: String, block: BlockRowVec): String {
        sequence++
        val blockId = "blk-$sequence"
        blocks.add(BlockMeta(blockId, collection, sequence, block))
        return blockId
    }

    /**
     * Remove a block. Increments the MVCC sequence.
     * The block is not physically removed — it's marked as removed at the new sequence.
     */
    fun remove(collection: String, blockId: String): Boolean {
        sequence++
        val meta = blocks.lastOrNull { it.blockId == blockId && it.collection == collection && !it.removed }
            ?: return false
        // Mark as removed (we don't actually remove from the list, just record the removal)
        blocks.add(BlockMeta(blockId, collection, sequence, meta.block, removed = true, removeSeq = sequence))
        return true
    }

    /**
     * Create a snapshot at the current sequence.
     */
    fun snapshot(): MvccSnapshot = MvccSnapshot(sequence)

    /**
     * List blockIds visible at the given snapshot in [collection].
     * A block is visible if it was put at or before the snapshot's sequence
     * and not removed before or at the snapshot's sequence.
     */
    fun listAt(snapshot: MvccSnapshot, collection: String): List<String> {
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
    fun getAt(snapshot: MvccSnapshot, collection: String, blockId: String): BlockRowVec? {
        val visibleIds = listAt(snapshot, collection)
        if (blockId !in visibleIds) return null
        return blocks.lastOrNull {
            it.blockId == blockId && it.collection == collection && !it.removed && it.putSeq <= snapshot.seq
        }?.block
    }

    /**
     * Scan all blocks in [collection] at the given snapshot, returning a merged MiniCursor.
     */
    fun scanAt(snapshot: MvccSnapshot, collection: String): MiniCursor {
        val rows = mutableListOf<MiniRowVec>()
        for (blockId in listAt(snapshot, collection)) {
            val block = getAt(snapshot, collection, blockId) ?: continue
            if (block.state != BlockRowVec.State.SEALED) continue
            val childSeries = block.child as? Series<MiniRowVec> ?: continue
            for (i in 0 until childSeries.size) {
                rows.add(childSeries[i])
            }
        }
        return rows.size j { rows[it] }
    }
}
