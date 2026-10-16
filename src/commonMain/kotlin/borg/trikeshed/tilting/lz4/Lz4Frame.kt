package borg.trikeshed.tilting.lz4

/** XXH32, the checksum of the LZ4 frame format. */
object Xxh32 {
    private const val P1: Int = -1640531535 // 2654435761
    private const val P2: Int = -2048144777 // 2246822519
    private const val P3: Int = -1028477379 // 3266489917
    private const val P4: Int = 668265263
    private const val P5: Int = 374761393

    fun hash(src: ByteArray, off: Int = 0, len: Int = src.size - off, seed: Int = 0): Int {
        val end = off + len
        var i = off
        var h: Int
        if (len >= 16) {
            var v1 = seed + P1 + P2
            var v2 = seed + P2
            var v3 = seed
            var v4 = seed - P1
            val limit = end - 16
            while (i <= limit) {
                v1 = round(v1, src.le32At(i))
                v2 = round(v2, src.le32At(i + 4))
                v3 = round(v3, src.le32At(i + 8))
                v4 = round(v4, src.le32At(i + 12))
                i += 16
            }
            h = v1.rotateLeft(1) + v2.rotateLeft(7) + v3.rotateLeft(12) + v4.rotateLeft(18)
        } else h = seed + P5
        h += len
        while (i + 4 <= end) { h = (h + src.le32At(i) * P3).rotateLeft(17) * P4; i += 4 }
        while (i < end) { h = (h + (src[i].toInt() and 0xFF) * P5).rotateLeft(11) * P1; i++ }
        h = (h xor (h ushr 15)) * P2
        h = (h xor (h ushr 13)) * P3
        return h xor (h ushr 16)
    }

    private fun round(acc: Int, input: Int): Int = (acc + input * P2).rotateLeft(13) * P1
}

/**
 * LZ4 frame format (lz4_Frame_format.md): magic, descriptor (FLG, BD, optional content size, header checksum),
 * blocks (LE32 size whose high bit marks a stored block), end mark, optional content checksum. Frames written here
 * use independent blocks of [blockSize] and only the header checksum. The reader also accepts block checksums, a
 * content checksum, a content size, every block size, and skippable frames ahead of the frame (TrikeShed's leaf
 * dictionary frame is one). Linked blocks and dictionary ids are refused.
 */
class Lz4Frame(val block: Lz4BlockOps, val blockSize: Int = 64 shl 10) {
    init {
        require(blockSize == 64 shl 10 || blockSize == 256 shl 10 || blockSize == 1 shl 20 || blockSize == 4 shl 20)
    }

    private val scratch = ByteArray(Lz4Block.bound(blockSize))

    fun bound(len: Int): Int = 7 + (len / blockSize + 1) * (4 + blockSize) + 4

    fun encode(src: ByteArray, off: Int, len: Int): ByteArray {
        val out = ByteArray(bound(len))
        out.put32le(0, MAGIC)
        out[4] = 0x60 // version 01, independent blocks
        out[5] = (bdCode(blockSize) shl 4).toByte()
        out[6] = (Xxh32.hash(out, 4, 2) ushr 8).toByte()
        var op = 7
        var cur = 0
        while (cur < len) {
            val n = minOf(blockSize, len - cur)
            val c = block.compress(src, off + cur, n, scratch, 0)
            if (c < n) {
                out.put32le(op, c); op += 4
                scratch.copyInto(out, op, 0, c); op += c
            } else {
                out.put32le(op, n or STORED); op += 4
                src.copyInto(out, op, off + cur, off + cur + n); op += n
            }
            cur += n
        }
        out.put32le(op, 0)
        return out.copyOf(op + 4)
    }

    /** Decode the frame in src[off, off + len) into dst[dstOff, dstOff + dstCap); returns the content size. */
    fun decodeInto(src: ByteArray, off: Int, len: Int, dst: ByteArray, dstOff: Int = 0, dstCap: Int = dst.size - dstOff): Int {
        val end = off + len
        var ip = off
        avail(end - ip, 4)
        var magic = src.le32At(ip)
        while (magic and -16 == SKIPPABLE) {
            avail(end - ip, 8)
            val size = src.le32At(ip + 4)
            if (size < 0 || size > end - ip - 8) throw Lz4Exception("skippable frame overruns the input")
            ip += 8 + size
            avail(end - ip, 4)
            magic = src.le32At(ip)
        }
        if (magic != MAGIC) throw Lz4Exception("not an LZ4 frame")
        ip += 4
        avail(end - ip, 3)
        val descriptor = ip
        val flg = src[ip++].toInt() and 0xFF
        val bd = src[ip++].toInt() and 0xFF
        if (flg ushr 6 != 1) throw Lz4Exception("frame version ${flg ushr 6}")
        if (flg and 0x02 != 0 || bd and 0x8F != 0) throw Lz4Exception("reserved descriptor bits set")
        if (flg and 0x20 == 0) throw Lz4Exception("linked blocks are not supported")
        if (flg and 0x01 != 0) throw Lz4Exception("dictionary frames are not supported")
        val blockMax = when ((bd ushr 4) and 7) {
            4 -> 64 shl 10
            5 -> 256 shl 10
            6 -> 1 shl 20
            7 -> 4 shl 20
            else -> throw Lz4Exception("block size code ${(bd ushr 4) and 7}")
        }
        val blockChecksum = flg and 0x10 != 0
        var contentSize = -1L
        if (flg and 0x08 != 0) {
            avail(end - ip, 9)
            contentSize = (src.le32At(ip).toLong() and 0xFFFFFFFFL) or (src.le32At(ip + 4).toLong() shl 32)
            ip += 8
        }
        avail(end - ip, 1)
        if ((src[ip].toInt() and 0xFF) != (Xxh32.hash(src, descriptor, ip - descriptor) ushr 8) and 0xFF)
            throw Lz4Exception("header checksum")
        ip++
        var op = dstOff
        val oEnd = dstOff + dstCap
        while (true) {
            avail(end - ip, 4)
            val word = src.le32At(ip)
            ip += 4
            if (word == 0) break
            val size = word and STORED.inv()
            if (size > blockMax) throw Lz4Exception("block of $size bytes exceeds $blockMax")
            avail(end - ip, size + if (blockChecksum) 4 else 0)
            if (blockChecksum && src.le32At(ip + size) != Xxh32.hash(src, ip, size)) throw Lz4Exception("block checksum")
            op += if (word and STORED != 0) {
                if (size > oEnd - op) throw Lz4Exception("stored block overruns the output")
                src.copyInto(dst, op, ip, ip + size)
                size
            } else block.decompress(src, ip, size, dst, op, minOf(blockMax, oEnd - op))
            ip += size + if (blockChecksum) 4 else 0
        }
        val total = op - dstOff
        if (flg and 0x04 != 0) {
            avail(end - ip, 4)
            if (src.le32At(ip) != Xxh32.hash(dst, dstOff, total)) throw Lz4Exception("content checksum")
        }
        if (contentSize >= 0 && contentSize != total.toLong()) throw Lz4Exception("content size $contentSize, decoded $total")
        return total
    }

    private companion object {
        const val MAGIC: Int = 0x184D2204
        const val SKIPPABLE: Int = 0x184D2A50
        const val STORED: Int = Int.MIN_VALUE

        fun bdCode(blockSize: Int): Int = when (blockSize) { 64 shl 10 -> 4; 256 shl 10 -> 5; 1 shl 20 -> 6; else -> 7 }

        fun avail(left: Int, need: Int) { if (left < need) throw Lz4Exception("frame truncated") }
    }
}
