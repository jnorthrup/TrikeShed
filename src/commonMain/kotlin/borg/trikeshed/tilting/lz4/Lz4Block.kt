package borg.trikeshed.tilting.lz4

/**
 * LZ4 block format (lz4_Block_format.md): sequences of a token, literals, a 16-bit little-endian offset and a match
 * length; the last sequence carries literals only. [Lz4BlockOps] is the seam a platform specialization replaces
 * (liblz4 through FFM on the JVM); [Lz4Kotlin] is the implementation every target compiles.
 */
interface Lz4BlockOps {
    val name: String

    /** Compress src[off, off + len) into dst from dstOff, which needs [Lz4Block.bound] (len) bytes. Returns the size. */
    fun compress(src: ByteArray, off: Int, len: Int, dst: ByteArray, dstOff: Int): Int

    /** Decompress exactly src[off, off + len) into dst[dstOff, dstOff + dstCap). Returns the size; throws [Lz4Exception]. */
    fun decompress(src: ByteArray, off: Int, len: Int, dst: ByteArray, dstOff: Int, dstCap: Int): Int
}

class Lz4Exception(message: String) : RuntimeException(message)

object Lz4Block {
    const val MIN_MATCH: Int = 4
    const val LAST_LITERALS: Int = 5
    const val MF_LIMIT: Int = 12
    const val MAX_DISTANCE: Int = 65535

    /** LZ4_COMPRESSBOUND. */
    fun bound(len: Int): Int = len + len / 255 + 16
}

internal fun ByteArray.le32At(i: Int): Int =
    (this[i].toInt() and 0xFF) or ((this[i + 1].toInt() and 0xFF) shl 8) or
        ((this[i + 2].toInt() and 0xFF) shl 16) or (this[i + 3].toInt() shl 24)

internal fun ByteArray.put32le(i: Int, v: Int) {
    this[i] = v.toByte(); this[i + 1] = (v ushr 8).toByte(); this[i + 2] = (v ushr 16).toByte(); this[i + 3] = (v ushr 24).toByte()
}

private const val HASH_LOG = 12
private const val SKIP_TRIGGER = 6

private fun lz4Hash(v: Int): Int = (v * -1640531535) ushr (32 - HASH_LOG)

private fun writeLength(dst: ByteArray, at: Int, n: Int): Int {
    var op = at
    var l = n
    while (l >= 255) { dst[op++] = -1; l -= 255 }
    dst[op++] = l.toByte()
    return op
}

/**
 * Greedy single-pass LZ4 compressor (acceleration 1) and safe decoder. One instance per thread: it owns its
 * 4096-entry hash table, reused across calls without clearing (entries carry a per-call base).
 */
class Lz4Kotlin : Lz4BlockOps {
    override val name: String get() = "kotlin"
    private val table = IntArray(1 shl HASH_LOG)
    private var base = 1

    override fun compress(src: ByteArray, off: Int, len: Int, dst: ByteArray, dstOff: Int): Int {
        if (len > Int.MAX_VALUE - 1 - base) { table.fill(0); base = 1 }
        val floor = base           // entries below floor were written by earlier calls
        val b = base - off         // entry = b + index into src
        base += len + 1
        val end = off + len
        var anchor = off
        var op = dstOff
        if (len > Lz4Block.MF_LIMIT) {
            val mfLimit = end - Lz4Block.MF_LIMIT
            val matchLimit = end - Lz4Block.LAST_LITERALS
            var ip = off
            table[lz4Hash(src.le32At(ip))] = b + ip
            ip++
            var ref = 0
            outer@ while (true) {
                var attempts = 1 shl SKIP_TRIGGER
                search@ while (true) {
                    if (ip >= mfLimit) break@outer
                    val seq = src.le32At(ip)
                    val h = lz4Hash(seq)
                    val e = table[h]
                    table[h] = b + ip
                    if (e >= floor) {
                        val r = e - b
                        if (ip - r <= Lz4Block.MAX_DISTANCE && src.le32At(r) == seq) { ref = r; break@search }
                    }
                    ip += attempts++ ushr SKIP_TRIGGER
                }
                while (ip > anchor && ref > off && src[ip - 1] == src[ref - 1]) { ip--; ref-- }
                sequence@ while (true) {
                    val token = op++
                    val litLen = ip - anchor
                    if (litLen >= 15) {
                        dst[token] = 0xF0.toByte()
                        op = writeLength(dst, op, litLen - 15)
                    } else dst[token] = (litLen shl 4).toByte()
                    src.copyInto(dst, op, anchor, ip)
                    op += litLen
                    val distance = ip - ref
                    dst[op++] = distance.toByte()
                    dst[op++] = (distance ushr 8).toByte()
                    var mLen = Lz4Block.MIN_MATCH
                    while (ip + mLen < matchLimit && src[ip + mLen] == src[ref + mLen]) mLen++
                    ip += mLen
                    anchor = ip
                    val ml = mLen - Lz4Block.MIN_MATCH
                    if (ml >= 15) {
                        dst[token] = (dst[token].toInt() or 15).toByte()
                        op = writeLength(dst, op, ml - 15)
                    } else dst[token] = (dst[token].toInt() or ml).toByte()
                    if (ip >= mfLimit) break@outer
                    table[lz4Hash(src.le32At(ip - 2))] = b + ip - 2
                    val seq = src.le32At(ip)
                    val h = lz4Hash(seq)
                    val e = table[h]
                    table[h] = b + ip
                    if (e >= floor) {
                        val r = e - b
                        if (ip - r <= Lz4Block.MAX_DISTANCE && src.le32At(r) == seq) { ref = r; continue@sequence }
                    }
                    ip++
                    continue@outer
                }
            }
        }
        val run = end - anchor
        val token = op++
        if (run >= 15) {
            dst[token] = 0xF0.toByte()
            op = writeLength(dst, op, run - 15)
        } else dst[token] = (run shl 4).toByte()
        src.copyInto(dst, op, anchor, end)
        op += run
        return op - dstOff
    }

    override fun decompress(src: ByteArray, off: Int, len: Int, dst: ByteArray, dstOff: Int, dstCap: Int): Int =
        lz4Decompress(src, off, len, dst, dstOff, dstCap)
}

/** Safe LZ4 block decoder: every length and offset is checked against both buffers; prefix references are refused. */
fun lz4Decompress(src: ByteArray, off: Int, len: Int, dst: ByteArray, dstOff: Int, dstCap: Int): Int {
    if (len <= 0) throw Lz4Exception("empty block")
    var ip = off
    val iEnd = off + len
    var op = dstOff
    val oEnd = dstOff + dstCap
    while (true) {
        val token = src[ip++].toInt() and 0xFF
        var lit = token ushr 4
        if (lit == 15) {
            while (true) {
                if (ip >= iEnd) throw Lz4Exception("literal length runs past the block")
                val x = src[ip++].toInt() and 0xFF
                lit += x
                if (lit > dstCap) throw Lz4Exception("literal length $lit exceeds the output")
                if (x != 255) break
            }
        }
        if (lit > iEnd - ip) throw Lz4Exception("literals run past the block")
        if (lit > oEnd - op) throw Lz4Exception("literals overrun the output")
        src.copyInto(dst, op, ip, ip + lit)
        ip += lit
        op += lit
        if (ip == iEnd) return op - dstOff
        if (iEnd - ip < 2) throw Lz4Exception("truncated offset")
        val distance = (src[ip].toInt() and 0xFF) or ((src[ip + 1].toInt() and 0xFF) shl 8)
        ip += 2
        if (distance == 0 || distance > op - dstOff) throw Lz4Exception("offset $distance outside the output")
        var mLen = token and 15
        if (mLen == 15) {
            while (true) {
                if (ip >= iEnd) throw Lz4Exception("match length runs past the block")
                val x = src[ip++].toInt() and 0xFF
                mLen += x
                if (mLen > dstCap) throw Lz4Exception("match length $mLen exceeds the output")
                if (x != 255) break
            }
        }
        mLen += Lz4Block.MIN_MATCH
        if (mLen > oEnd - op) throw Lz4Exception("match overruns the output")
        val m = op - distance
        if (distance >= mLen) dst.copyInto(dst, op, m, m + mLen)
        else for (k in 0 until mLen) dst[op + k] = dst[m + k]
        op += mLen
        if (ip >= iEnd) throw Lz4Exception("block ends after a match")
    }
}
