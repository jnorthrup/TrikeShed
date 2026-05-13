package borg.trikeshed.torrent

import kotlin.math.min

/**
 * PrioritySelector — dynamic, cursor-aware piece/block prioritizer.
 *
 * Replaces static rarest-first with a weighted composite score:
 *   score = (cursorDelta × cursorWeight) + (rarity × rarityWeight) + (urgency × urgencyWeight)
 *
 * The selector is transport-agnostic: works with both TorrentBlock (piece-level)
 * and IpfsBlock (CID-level) through the PriorityBlock interface.
 */
class PrioritySelector(
    private val cursorWeight: Double = 0.6,
    private val rarityWeight: Double = 0.25,
    private val urgencyWeight: Double = 0.15,
) {

    data class ScoredBlock(
        val block: PriorityBlock,
        val score: Double,
    ) : Comparable<ScoredBlock> {
        override fun compareTo(other: ScoredBlock) = score.compareTo(other.score)  // lower = higher priority
    }

    /**
     * Score a batch of available blocks and sort by priority (lowest score first).
     *
     * @param cursorByteOffset  current playback / read cursor position in torrent bytes
     * @param pieceLength       bytes per piece (torrent-specific; ignored for IpfsBlock)
     * @param blocks            candidate blocks to rank.
     */
    fun rank(
        cursorByteOffset: Long = 0,
        pieceLength: Long = 0,
        blocks: Iterable<PriorityBlock>,
    ): List<ScoredBlock> {
        val scored = blocks.map { block ->
            val cursorScore = cursorScore(block, cursorByteOffset, pieceLength)
            val rarityScore = rarityScore(block)
            val urgencyScore = urgencyScore(block)
            val total = (cursorScore * cursorWeight) +
                        (rarityScore * rarityWeight) +
                        (urgencyScore * urgencyWeight)
            ScoredBlock(block, total)
        }
        scored.sort()  // ascending: lowest score = highest priority
        return scored
    }

    /**
     * Return the highest-priority block (lowest score) or null.
     */
    fun top(
        cursorByteOffset: Long = 0,
        pieceLength: Long = 0,
        blocks: Iterable<PriorityBlock>,
    ): ScoredBlock? = rank(cursorByteOffset, pieceLength, blocks).firstOrNull()

    // ── Scoring components ────────────────────────────────────────────────

    /**
     * Cursor proximity: blocks near the cursor get the lowest score.
     * Normalized to [0, 1] where 0 = at cursor.
     */
    private fun cursorScore(block: PriorityBlock, cursorByteOffset: Long, pieceLength: Long): Double {
        val blockByteStart = when (block) {
            is TorrentBlock -> (block.pieceIndex.toLong() * pieceLength) + (block.blockIndex * block.length)
            is IpfsBlock    -> 0L  // IPFS blocks have no positional offset; fall back to weight.
        }
        val distance = (blockByteStart - cursorByteOffset).coerceAtLeast(0)
        // Normalize: 0 at cursor, 1.0 at 4× lookahead.
        val lookahead = 640L * 1024  // 640 KiB ≈ 5 s at 1 Mbps
        return (distance.toDouble() / (lookahead * 4)).coerceAtMost(1.0)
    }

    /**
     * Rarity: fewer available peers → lower (better) score.
     * Normalized: 0 = only 1 peer, 1 = 20+ peers.
     */
    private fun rarityScore(block: PriorityBlock): Double =
        (block.availablePeers.coerceAtMost(20) / 20.0)

    /**
     * Urgency: derived from the block's priority level weight.
     * Normalized to [0, 1].
     */
    private fun urgencyScore(block: PriorityBlock): Double =
        block.weight / PriorityLevel.entries.maxOf { it.weight }.toDouble()
}
