package borg.trikeshed.torrent

/**
 * Piece tracker — manages download state per piece with SHA-1 verification.
 * Each bit in completedBlocks corresponds to a BLOCK_SIZE chunk (16 KiB).
 */
class TorrentPiece(
    val index: Int,
    val hash: ByteArray,      // 20-byte SHA-1 expected hash
    val length: Int,           // actual bytes in this piece (last piece may be shorter)
) {
    companion object {
        const val BLOCK_SIZE = 16384  // 16 KiB — hyperdl internal chunk size
    }

    val blockCount: Int = (length + BLOCK_SIZE - 1) / BLOCK_SIZE
    private var completedMask = 0L  // bitmask for completed blocks (up to 64 blocks)
    private val data = ByteArray(length)

    val isComplete: Boolean get() = completedMask == ((1L shl blockCount) - 1)
    val downloadedBytes: Long get() = completedMask.countOneBits().toLong() * BLOCK_SIZE

    fun blockCompleted(blockIndex: Int) {
        completedMask = completedMask or (1L shl blockIndex)
    }

    fun isBlockDone(blockIndex: Int): Boolean = (completedMask and (1L shl blockIndex)) != 0L

    fun writeBlock(blockIndex: Int, blockData: ByteArray) {
        val offset = blockIndex * BLOCK_SIZE
        val len = minOf(blockData.size, length - offset)
        blockData.copyInto(data, offset, 0, len)
        blockCompleted(blockIndex)
    }

    fun verify(hasher: (ByteArray) -> ByteArray): Boolean {
        return hasher(data).contentEquals(hash)
    }

    fun data(): ByteArray = if (isComplete) data else error("Piece $index not complete")
}
