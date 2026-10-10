package borg.trikeshed.tilting.zran

const val SEEKABLE_MAGIC: Int = -1886197071 // 0x8F92EAB1
const val SEEK_TABLE_MAGIC: Int = 0x184D2A5E
const val SEEK_TABLE_FOOTER: Int = 9

/**
 * zstd seekable format (zstd contrib/seekable_format): a skippable frame after the data frames lists
 * every frame's packed and content size. Frame k holds content [content[k], content[k + 1]) at packed
 * bytes [packed[k], packed[k + 1]). `zstd -d` skips the table and emits the concatenated content.
 */
class SeekTable(val frames: Int, val packed: LongArray, val content: LongArray) {
    val packedBytes: Long get() = packed[frames]
    val contentBytes: Long get() = content[frames]

    /** Frame holding content byte [at]. */
    fun frameOf(at: Long): Int {
        var lo = 0
        var hi = frames - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (content[mid] <= at) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** The table as its skippable frame (no per-frame checksums: each frame carries its own). */
    fun bytes(): ByteArray {
        val size = frames * 8 + SEEK_TABLE_FOOTER
        val b = ByteArray(8 + size)
        b.put32(0, SEEK_TABLE_MAGIC)
        b.put32(4, size)
        for (k in 0 until frames) {
            b.put32(8 + 8 * k, (packed[k + 1] - packed[k]).toInt())
            b.put32(12 + 8 * k, (content[k + 1] - content[k]).toInt())
        }
        val f = 8 + 8 * frames
        b.put32(f, frames)
        b[f + 4] = 0
        b.put32(f + 5, SEEKABLE_MAGIC)
        return b
    }
}

/** Seek table frame length named by a file's last [SEEK_TABLE_FOOTER] bytes, or -1 when they are no seekable footer. */
fun seekTableLength(footer: ByteArray, off: Int = 0): Long {
    if (footer.le32(off + 5) != SEEKABLE_MAGIC) return -1
    val descriptor = footer.u8(off + 4)
    if (descriptor and 0x7C != 0) return -1
    val entry = if (descriptor and 0x80 != 0) 12 else 8
    return 8 + (footer.le32(off).toLong() and 0xFFFFFFFFL) * entry + SEEK_TABLE_FOOTER
}

/** Parses a whole seek table frame; null when it is not one. */
fun parseSeekTable(src: ByteArray, off: Int = 0, len: Int = src.size - off): SeekTable? {
    if (len < 8 + SEEK_TABLE_FOOTER || src.le32(off) != SEEK_TABLE_MAGIC) return null
    val f = off + len - SEEK_TABLE_FOOTER
    if (seekTableLength(src, f) != len.toLong()) return null
    val entry = if (src.u8(f + 4) and 0x80 != 0) 12 else 8
    val frames = src.le32(f)
    if (src.le32(off + 4).toLong() != frames.toLong() * entry + SEEK_TABLE_FOOTER) return null
    val packed = LongArray(frames + 1)
    val content = LongArray(frames + 1)
    for (k in 0 until frames) {
        val e = off + 8 + k * entry
        packed[k + 1] = packed[k] + (src.le32(e).toLong() and 0xFFFFFFFFL)
        content[k + 1] = content[k] + (src.le32(e + 4).toLong() and 0xFFFFFFFFL)
    }
    return SeekTable(frames, packed, content)
}
