package borg.trikeshed.torrent

import java.util.LinkedList
/**
 * PriorityQueue — weighted priority heap for dynamic block scheduling.
 *
 * A min-heap of PriorityBlock ordered by score(). The heap supports:
 *   - O(log N) push/pop with score-based ordering
 *   - O(1) peek for the next block to fetch
 *   - Dynamic weight updates (reposition after a block's priority changes)
 *   - Duplicate prevention (already-pending blocks are skipped)
 *
 * Used by: TorrentPeer (bitTorrent wire), IpfsElement (DHT fetch),
 *          StreamingTorrent (cursor-aware piece picker).
 */
class PriorityQueue {

    private val heap = LinkedList<PriorityBlock>()
    private val pendingKeys = LinkedHashSet<ByteArray>()

    val size: Int get() = heap.size
    val isEmpty: Boolean get() = heap.isEmpty()

    fun push(block: PriorityBlock) {
        if (block.isPending) return
        if (block.isComplete) return
        // Check against pending keys
        if (pendingKeys.any { it.contentEquals(block.key) }) return
        pendingKeys.add(block.key.copyOf())
        heap.add(block)
        siftUp(heap.size - 1)
    }

    fun pop(): PriorityBlock? {
        if (heap.isEmpty()) return null
        val top = heap[0]
        val last = heap.removeAt(heap.size - 1)
        if (heap.isNotEmpty()) {
            heap[0] = last
            siftDown(0)
        }
        pendingKeys.removeIf { it.contentEquals(top.key) }
        return top
    }

    fun peek(): PriorityBlock? = heap.firstOrNull()

    /**
     * Update a block's weight and reposition in the heap.
     * Returns true if the block was found and updated.
     */
    fun update(key: ByteArray, newWeight: Int): Boolean {
        val idx = heap.indexOfFirst { it.key.contentEquals(key) }
        if (idx < 0) return false
        heap[idx].weight = newWeight
        siftDown(idx)
        siftUp(idx)
        return true
    }

    /**
     * Mark a block as pending so it doesn't get re-requested.
     */
    fun markPending(key: ByteArray) {
        val idx = heap.indexOfFirst { it.key.contentEquals(key) }
        if (idx >= 0) {
            val block = heap[idx]
            // Remove and let it be re-pushed later when the request completes
            val removed = heap.removeAt(idx)
            siftDown(idx)
        }
    }

    fun sizeWithPending(): Int = heap.size
    fun pendingCount(): Int = pendingKeys.size

    // ── Min-heap operations ───────────────────────────────────────

    private fun siftUp(idx: Int) {
        var i = idx
        while (i > 0) {
            val parent = (i - 1) ushr 1
            if (heap[i].score() < heap[parent].score()) {
                heap[i] = heap[parent].also { heap[parent] = heap[i] }
                i = parent
            } else break
        }
    }

    private fun siftDown(idx: Int) {
        var i = idx
        val half = heap.size ushr 1
        while (i < half) {
            var child = (i shl 1) + 1
            if (child + 1 < heap.size && heap[child + 1].score() < heap[child].score()) {
                child++
            }
            if (heap[i].score() > heap[child].score()) {
                heap[i] = heap[child].also { heap[child] = heap[i] }
                i = child
            } else break
        }
    }
}
