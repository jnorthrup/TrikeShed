package borg.trikeshed.reactor.xet

/** A chunk as a range of the source bytes. */
data class XetChunkSpan(val offset: Int, val length: Int)

/**
 * Xet content-defined chunking (spec: huggingface.co/docs/xet/chunking). Gearhash, target 64 KiB,
 * minimum 8 KiB, maximum 128 KiB, boundary when `(h & MASK) == 0` with MASK = 0xFFFF000000000000.
 * `h` is reset to 0 at every boundary. Bytes before `start + MIN - 64` cannot affect `h` at the first
 * testable offset (the hash only depends on the last 64 bytes), so they are skipped, as the spec allows.
 */
object XetChunker {
    const val MIN_CHUNK_SIZE: Int = 8 * 1024
    const val MAX_CHUNK_SIZE: Int = 128 * 1024
    const val TARGET_CHUNK_SIZE: Int = 64 * 1024
    private const val MASK: Long = -0x1_0000_0000_0000L // 0xFFFF000000000000

    fun chunk(data: ByteArray): List<XetChunkSpan> {
        val table = XetGearTable.TABLE
        val out = ArrayList<XetChunkSpan>()
        val n = data.size
        var start = 0
        while (start < n) {
            if (n - start <= MIN_CHUNK_SIZE) {
                out.add(XetChunkSpan(start, n - start))
                break
            }
            var h = 0L
            var i = start + MIN_CHUNK_SIZE - 64
            val end = minOf(n, start + MAX_CHUNK_SIZE)
            var cut = end
            while (i < end) {
                h = (h shl 1) + table[data[i].toInt() and 0xFF]
                if (i + 1 - start >= MIN_CHUNK_SIZE && (h and MASK) == 0L) {
                    cut = i + 1
                    break
                }
                i++
            }
            out.add(XetChunkSpan(start, cut - start))
            start = cut
        }
        return out
    }
}
