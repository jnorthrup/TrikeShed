package borg.trikeshed.tilting.lzo

import borg.trikeshed.tilting.lz4.le32At
import borg.trikeshed.tilting.lz4.put32le

/**
 * Btrfs LZO extent format (fs/btrfs/lzo.c): a LE32 total size that counts itself, then per input sector a LE32
 * segment length and an LZO1X payload. A segment header never crosses an output sector boundary: when fewer than
 * 4 bytes are left in the sector after a payload, zeros pad it (counted in the total).
 */
class BtrfsLzo(val block: LzoBlockOps, val sectorSize: Int = 4096) {
    private val segment = ByteArray(Lzo1x.worstCompress(sectorSize))

    fun bound(len: Int): Int = 4 + ((len + sectorSize - 1) / sectorSize) * (4 + 3 + Lzo1x.worstCompress(sectorSize))

    fun encode(src: ByteArray, off: Int, len: Int): ByteArray {
        val out = ByteArray(bound(len))
        var total = 4
        var cur = 0
        while (cur < len) {
            val n = minOf(len - cur, sectorSize)
            val seg = block.compress(src, off + cur, n, segment, 0)
            out.put32le(total, seg)
            total += 4
            segment.copyInto(out, total, 0, seg)
            total += seg
            val left = sectorSize - total % sectorSize
            if (left < 4) total += left
            cur += n
        }
        out.put32le(0, total)
        return out.copyOf(total)
    }

    /** Decode the extent in src[off, off + len) into dst[dstOff, dstOff + dstCap); returns the decoded size. */
    fun decodeInto(src: ByteArray, off: Int, len: Int, dst: ByteArray, dstOff: Int = 0, dstCap: Int = dst.size - dstOff): Int {
        if (len < 4) throw LzoException("extent shorter than its header")
        val total = src.le32At(off)
        if (total < 4 || total > len) throw LzoException("header says $total bytes, extent has $len")
        var cur = 4
        var op = dstOff
        val oEnd = dstOff + dstCap
        while (cur < total) {
            if (total - cur < 4) throw LzoException("segment header truncated")
            val seg = src.le32At(off + cur)
            cur += 4
            if (seg < 3 || seg > Lzo1x.worstCompress(sectorSize)) throw LzoException("segment of $seg bytes")
            if (seg > total - cur) throw LzoException("segment overruns the extent")
            op += block.decompress(src, off + cur, seg, dst, op, minOf(sectorSize, oEnd - op))
            cur += seg
            val left = sectorSize - cur % sectorSize
            if (left < 4) cur += left
        }
        return op - dstOff
    }
}
