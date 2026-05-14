package borg.trikeshed.torrent

import java.util.LinkedList
/**
 * BlockQueue — a priority queue for PriorityBlock transfers.
 *
 * Min-heap ordered by effective weight (CRITICAL first). Supports:
 *  - O(log N) insert / remove for the next block to request
 *  - O(1) peek at highest-priority pending block
 *  - Duplicate prevention (already-pending blocks are rejected)
 *
 * Used by TorrentElement (swarm) and IpfsElement (DHT) with the
 * same PriorityQueue through the PriorityBlock bridge interface.
 */
class BlockQueue {
    private val heap = LinkedList<PriorityBlock>()

    val size: Int get() = heap.size

    fun push(block: PriorityBlock) {
        if (block.isPending) return
        heap.add(block)
        siftUp(heap.lastIndex)
        block.isPending = true
    }

    fun pop(): PriorityBlock? {
        if (heap.isEmpty()) return null
        val top = heap[0]
        val last = heap.removeAt(heap.lastIndex)
        if (heap.isNotEmpty()) {
            heap[0] = last
            siftDown(0)
        }
        return top
    }

    // Pop the highest-priority block without marking it pending.
    fun peek(): PriorityBlock? = heap.firstOrNull()

    // Mark a block as complete and remove from queue.
    fun complete(key: ByteArray) {
        val idx = heap.indexOfFirst { it.key.contentEquals(key) }
        if (idx >= 0) {
            heap[idx].isPending = false
            heap.removeAt(idx)
        }
    }

    // Internal min-heap operations
    private fun siftUp(idx: Int) {
        var i = idx
        while (i > 0) {
            val parent = (i - 1) ushr  1
            if (heap[i].priority < heap[parent].priority) {
                swap(i, parent)
                i = parent
            } else break
        }
    }

    private fun siftDown(idx: Int) {
        var i = idx
        val n = heap.size
        while (true) {
            var smallest = i
            val left = (i shl 1) + 1
            val right = left + 1
            if (left < n && heap[left].priority < heap[smallest].priority) smallest = left
            if (right < n && heap[right].priority < heap[smallest].priority) smallest = right
            if (smallest != i) {
                swap(i, smallest)
                i = smallest
            } else break
        }
    }

    private fun swap(a: Int, b: Int) {
        val t = heap[a]; heap[a] = heap[b]; heap[b] = t
    }
}

/**
 * BlockSelector — decides which blocks to request first from available peers.
 *
 * Strategy: prioritize blocks near the cursor (streaming) + rarest pieces
 * (torrent swarm) + DAG-root dependencies (IPFS). Combines torrent-style
 * rarest-first with IPFS DAG-topology awareness.
 *
 * The selector produces a sorted list of PriorityBlocks. The BlockQueue
 * handles the actual scheduling.
 */
object BlockSelector {

    /**
     * Score and sort blocks for transfer ordering.
     * Higher urgency = lower score.
     * 
     * Factors: priority weight (60%), peer availability rarity (25%), 
     * distance from cursor (15%).
     */
    fun select(
        cursorByteOffset: Long = 0,
        pieceLength: Long = 0,
        blocksWithPeers: List<Pair<PriorityBlock, Int>>,
    ): List<PriorityBlock> {
        // blocksWithPeers: (block, available peer count)
        val score = blocksWithPeers.map { (block, peerCount) ->
            val priorityWeight = block.priority.toDouble() / PriorityLevel.LOW.weight
            val rarityScore = if (peerCount == 0) 1.0 else 1.0 / peerCount
            val cursorDistance = when (block) {
                is TorrentBlock -> {
                    val blockStart = block.pieceIndex.toLong() * pieceLength + block.blockIndex * TorrentBlock.BLOCK_SIZE
                    ((cursorByteOffset - blockStart).coerceAtLeast(0).toDouble() / (pieceLength * 4)).coerceAtMost(1.0)
                }
                is IpfsBlock -> 0.5
            }
            (priorityWeight * 0.6) + (rarityScore * 0.25) + (cursorDistance * 0.15)
        }
        
        return blocksWithPeers
            .zip(score)
            .sortedBy { it.second }
            .map { it.first }
    }

    /**
     * Select the next N blocks from a torrent swarm, with streaming-aware
     * prioritization. Combines rarest-first with cursor proximity.
     */
    fun selectForTorrent(
        cursorByteOffset: Long,
        pieces: List<TorrentPriorityBlock>,
        peerAvailability: Map<Int, Int>,  // pieceIndex → peer count
        maxBlocks: Int = 5,
    ): List<TorrentPriorityBlock> {
        val blocksWithPeers = pieces.map { block ->
            val piecePeerCount = peerAvailability[block.pieceIndex] ?: 0
            block to piecePeerCount
        }
        return select(cursorByteOffset, pieces.firstOrNull()?.let { b -> 
            b.pieceIndex.toLong() * 16384 
        } ?: 0, blocksWithPeers)
            .filterIsInstance<TorrentPriorityBlock>()
            .take(maxBlocks)
    }
}