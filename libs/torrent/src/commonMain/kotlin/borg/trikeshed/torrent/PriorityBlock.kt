package borg.trikeshed.torrent

import kotlin.math.min

/**
 * PriorityBlock — shared interface for torrent blocks and IPFS blocks.
 * 
 * This is the semantic bridge between Torrent (piece/block-level) and IPFS
 * (CID-level) transfers. Both sides implement this so the same block
 * prioritization logic applies regardless of transport.
 *
 * The key concept: every transferable unit of data is a block identified
 * by a unique key, with a priority level and transfer state. Torrent
 * blocks use pieceIndex:blockIndex; IPFS blocks use CID bytes.
 */
sealed interface PriorityBlock {
    /** Unique identifier within the queue. */
    val key: ByteArray

    /** Block size in bytes. */
    val length: Int

    /** Priority weight — lower weight = higher urgency. */
    var priority: Int

    /** Whether this block is already scheduled for transfer. */
    var isPending: Boolean
}

/** Torrent-specific block: a 16 KiB chunk within a torrent piece. */
data class TorrentBlock(
    val pieceIndex: Int,
    val blockIndex: Int,
    val pieceHash: ByteArray,          // 20-byte SHA-1
    override var isPending: Boolean = false,
) : PriorityBlock {
    override val key: ByteArray = computeKey()
    override val length: Int = BLOCK_SIZE
    override var priority: Int = PriorityLevel.LOW.weight

    private fun computeKey(): ByteArray =
        ByteArray(8) { 0 }.apply {
            this[0] = (pieceIndex ushr 24).toByte()
            this[1] = (pieceIndex ushr 16).toByte()
            this[2] = (pieceIndex ushr 8).toByte()
            this[3] = pieceIndex.toByte()
            this[4] = (blockIndex ushr 24).toByte()
            this[5] = (blockIndex ushr 16).toByte()
            this[6] = (blockIndex ushr 8).toByte()
            this[7] = blockIndex.toByte()
        }

    companion object {
        const val BLOCK_SIZE = 16 * 1024  // 16 KiB — same as TorrentPiece.BLOCK_SIZE
    }
}

/** IPFS-specific block: a content-addressed block (CID). */
data class IpfsBlock(
    val cidBytes: ByteArray,             // raw CID bytes
    override val length: Int = 0,        // variable size
    override var isPending: Boolean = false,
    override var priority: Int = PriorityLevel.LOW.weight,
) : PriorityBlock {
    override val key: ByteArray get() = cidBytes
}

/** Priority levels — CRITICAL has lowest weight = highest urgency. */
enum class PriorityLevel(val weight: Int) {
    CRITICAL(0),   // Must-have-now (cursor position, DAG root)
    HIGH(1),       // Near cursor or immediate DAG dependency
    MEDIUM(2),     // Within 4x lookahead, or mid-level DAG node
    LOW(3),        // Background (far ahead, leaf nodes)
}

/**
 * Compute the effective priority weight for a block based on its position
 * relative to the media cursor (for streaming) or DAG urgency (for IPFS).
 *
 * @param cursorByteOffset current playback cursor in bytes (0 if not streaming)
 * @param pieceLength bytes per torrent piece (TorrentMetainfo.pieceLength)
 * @param isRoot true if this is a DAG root node (IPFS)
 */
fun PriorityBlock.computeWeight(
    cursorByteOffset: Long = 0,
    pieceLength: Long = 0,
    isRoot: Boolean = false,
): Int {
    if (isRoot) return PriorityLevel.CRITICAL.weight

    return when (this) {
        is TorrentBlock -> {
            val blockStart = pieceIndex.toLong() * pieceLength + blockIndex * TorrentBlock.BLOCK_SIZE
            val distance = (cursorByteOffset - blockStart).coerceAtLeast(0)
            when {
                distance <= pieceLength -> PriorityLevel.CRITICAL.weight
                distance <= pieceLength * 4 -> PriorityLevel.HIGH.weight
                distance <= pieceLength * 16 -> PriorityLevel.MEDIUM.weight
                else -> PriorityLevel.LOW.weight
            }
        }
        is IpfsBlock -> {
            // IPFS blocks: use declared priority unless overridden
            priority
        }
    }
}