package borg.trikeshed.reflink

import borg.trikeshed.job.ContentId

/**
 * Interface for scanning blocks to find duplication candidates.
 */
interface ReflinkScanner {
    fun scan(bytes: ByteArray): List<ContentId>
}

/**
 * Simple hash-based dedup scanner (Fixed block size chunking).
 */
class FixedBlockReflinkScanner(val blockSize: Int = 4096) : ReflinkScanner {
    override fun scan(bytes: ByteArray): List<ContentId> {
        val result = mutableListOf<ContentId>()
        var offset = 0
        while (offset < bytes.size) {
            val length = minOf(blockSize, bytes.size - offset)
            val chunk = bytes.copyOfRange(offset, offset + length)
            result.add(ContentId.of(chunk))
            offset += length
        }
        return result
    }
}
