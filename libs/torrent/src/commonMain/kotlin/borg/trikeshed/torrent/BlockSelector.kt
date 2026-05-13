package borg.trikeshed.torrent

/**
 * PriorityBlock — unified abstraction for prioritized block requests across
 * torrent and IPFS transports.
 *
 * Both sides implement the same PriorityBlock interface so the scheduler
 * doesn't care whether a block comes from a BitTorrent swarm or an IPFS DHT query.
 *
 * Torrent blocks: pieceIndex × blockIndex (16 KiB chunks)
 * IPFS blocks:   CID-based blocks (variable size, IPLD DAG)
 *
 * Priority levels (lowest number = most urgent):
 *   0 = CRITICAL  — at or just past the cursor / DAG root dependency
 *   1 = HIGH      — within lookahead buffer / immediate parent ref
 *   2 = MEDIUM    — within 4× lookahead / mid-level DAG node
 *   3 = LOW       — everything else
 */
sealed interface PriorityBlock {
    /** Unique identifier within scope (pieceIndex:blockIndex or CID string). */
    val key: CharSequence
    /** Size in bytes (0 = unknown / variable). */
    val sizeBytes: Long
    /** Priority tier: 0 = CRITICAL … 3 = LOW. */
    var tier: Int
    /** How many peers/caches currently hold this block. */
    var availableFrom: Int
    /** Whether this block has already been requested. */
    var isQueued: Boolean

    companion object {
        const val CRITICAL = 0
        const val HIGH = 1
        const val MEDIUM = 2
        const val LOW = 3
    }
}

data class TorrentBlockPriority(
    val pieceIndex: Int,
    val blockIndex: Int,
    override val sizeBytes: Long = 16384, // 16 KiB
    override var tier: Int = PriorityBlock.LOW,
    override var availableFrom: Int = 0,
    override var isQueued: Boolean = false,
) : PriorityBlock {
    override val key: CharSequence = "$pieceIndex:$blockIndex"
}

data class IpfsBlockPriority(
    val cid: CharSequence,
    override val sizeBytes: Long = 0,
    override var tier: Int = PriorityBlock.LOW,
    override var availableFrom: Int = 0,
    override var isQueued: Boolean = false,
) : PriorityBlock {
    override val key: CharSequence get() = cid
}

/**
 * BlockSelector — dynamic priority scheduler for block transfers.
 *
 * Replaces static rarest-first with a three-factor weighted score:
 *   Score = tierWeight + (1 / availablePeers) + cursorProximity
 *
 * The cursor proximity factor makes blocks near the playback/seek position
 * get pulled first (streaming mode). In non-streaming mode it's zero and
 * the scheduler falls back to tier + rarity.
 *
 * The same selector works for torrent pieces AND IPFS CID fetch ordering.
 */
object BlockSelector {

    data class BlockRequest(
        val block: PriorityBlock,
        val score: Double,
    ) : Comparable<BlockRequest> {
        override fun compareTo(other: BlockRequest) = score.compareTo(other.score)  // lower = better
    }

    /**
     * Weight for each priority tier.
     * CRITICAL = 0, HIGH = 10, MEDIUM = 20, LOW = 30.
     * These gaps ensure tier always dominates the score.
     */
    private fun tierScore(tier: Int): Int = tier * 10

    /**
     * Compute a total priority score. Lower = more urgent.
     */
    fun score(
        block: PriorityBlock,
        cursorByteOffset: Long = 0,
    ): Double {
        val tier = tierScore(block.tier).toDouble()
        // Rarity: fewer available = higher urgency
        val rarity = if (block.availableFrom > 0) 1.0 / block.availableFrom else 5.0
        // Cursor proximity: blocks near the cursor get bonus priority
        val cursor = if (cursorByteOffset > 0 && block is TorrentBlockPriority) {
            val blockByte = block.pieceIndex.toLong() * 16384 * 64 + block.blockIndex * 16384
            val dist = (blockByte - cursorByteOffset).coerceAtLeast(0)
            if (dist == 0L) 0.0
            else (dist.toDouble() / 10_000_000.0).coerceAtMost(3.0)
        } else 1.0
        tier + rarity + cursor
    }

    /**
     * Select and rank blocks by total priority score.
     * Returns a sorted list — lowest score (highest urgency) first.
     */
    fun rank(
        cursorByteOffset: Long = 0,
        blocks: Iterable<PriorityBlock>,
    ): List<BlockRequest> {
        val scored = blocks.map { BlockRequest(it, score(it, cursorByteOffset)) }
        scored.sort()
        return scored
    }

    /**
     * Select the top N blocks by priority score.
     */
    fun select(
        cursorByteOffset: Long = 0,
        blocks: Iterable<PriorityBlock>,
        n: Int = 5,
    ): List<PriorityBlock> = rank(cursorByteOffset, blocks).take(n).map { it.block }
}
