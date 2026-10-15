package borg.trikeshed.tilting.lzo

import borg.trikeshed.tilting.lz4.le32At

/**
 * LZO1X (lzo.rst, the stream the Linux kernel's and liblzo2's lzo1x_decompress_safe read). [LzoBlockOps] is the seam
 * a platform specialization replaces (liblzo2 through FFM on the JVM); [LzoKotlin] is the implementation every target
 * compiles: the LZO1X-1 compressor of the kernel's lzo1x_compress.c, bitstream version 0, and a safe decoder.
 */
interface LzoBlockOps {
    val name: String

    /** Compress src[off, off + len) into dst from dstOff, which needs [Lzo1x.worstCompress] (len) bytes. Returns the size. */
    fun compress(src: ByteArray, off: Int, len: Int, dst: ByteArray, dstOff: Int): Int

    /** Decompress exactly src[off, off + len) into dst[dstOff, dstOff + dstCap). Returns the size; throws [LzoException]. */
    fun decompress(src: ByteArray, off: Int, len: Int, dst: ByteArray, dstOff: Int, dstCap: Int): Int
}

class LzoException(message: String) : RuntimeException(message)

object Lzo1x {
    /** lzo1x_worst_compress. */
    fun worstCompress(len: Int): Int = len + len / 16 + 64 + 3

    internal const val M2_MAX_LEN = 8
    internal const val M3_MAX_LEN = 33
    internal const val M4_MAX_LEN = 9
    internal const val M2_MAX_OFFSET = 0x0800
    internal const val M3_MAX_OFFSET = 0x4000
    internal const val M4_MAX_OFFSET_V0 = 0xbfff
    internal const val M3_MARKER = 32
    internal const val M4_MARKER = 16
    internal const val D_BITS = 13
    internal const val D_MASK = (1 shl D_BITS) - 1
}

/** One instance per thread: it owns the 8192-entry dictionary the compressor clears per 48 KiB chunk. */
class LzoKotlin : LzoBlockOps {
    override val name: String get() = "kotlin"
    private val dict = ShortArray(1 shl Lzo1x.D_BITS)
    private var op = 0
    private var stateOffset = -2

    override fun compress(src: ByteArray, off: Int, len: Int, dst: ByteArray, dstOff: Int): Int {
        op = dstOff
        stateOffset = -2
        var ip = off
        var l = len
        var t = 0
        while (l > 20) {
            val ll = minOf(l, Lzo1x.M4_MAX_OFFSET_V0 + 1)
            dict.fill(0)
            t = chunk(src, ip, ll, dst, t)
            ip += ll
            l -= ll
        }
        t += l
        var o = op
        if (t > 0) {
            val ii = off + len - t
            if (o == dstOff && t <= 238) dst[o++] = (17 + t).toByte()
            else if (t <= 3) dst[o + stateOffset] = (dst[o + stateOffset].toInt() or t).toByte()
            else if (t <= 18) dst[o++] = (t - 3).toByte()
            else {
                var tt = t - 18
                dst[o++] = 0
                while (tt > 255) { tt -= 255; dst[o++] = 0 }
                dst[o++] = tt.toByte()
            }
            src.copyInto(dst, o, ii, ii + t)
            o += t
        }
        dst[o++] = (Lzo1x.M4_MARKER or 1).toByte()
        dst[o++] = 0
        dst[o++] = 0
        return o - dstOff
    }

    /** lzo1x_1_do_compress over one chunk; returns the literals left pending at its end. */
    private fun chunk(src: ByteArray, inOff: Int, inLen: Int, dst: ByteArray, tiIn: Int): Int {
        val inEnd = inOff + inLen
        val ipEnd = inOff + inLen - 20
        var ti = tiIn
        var ip = inOff
        var ii = ip
        ip += if (ti < 4) 4 - ti else 0
        var o = op
        var skip = true
        while (true) {
            if (skip) ip += 1 + ((ip - ii) ushr 5)
            skip = true
            if (ip >= ipEnd) break
            val dv = src.le32At(ip)
            val h = ((dv * 0x1824429d) ushr (32 - Lzo1x.D_BITS)) and Lzo1x.D_MASK
            val mPos = inOff + (dict[h].toInt() and 0xFFFF)
            dict[h] = (ip - inOff).toShort()
            if (dv != src.le32At(mPos)) continue
            ii -= ti
            ti = 0
            val t = ip - ii
            if (t != 0) {
                if (t <= 3) dst[o + stateOffset] = (dst[o + stateOffset].toInt() or t).toByte()
                else if (t <= 18) dst[o++] = (t - 3).toByte()
                else {
                    var tt = t - 18
                    dst[o++] = 0
                    while (tt > 255) { tt -= 255; dst[o++] = 0 }
                    dst[o++] = tt.toByte()
                }
                src.copyInto(dst, o, ii, ii + t)
                o += t
            }
            var mLen = 4
            while (ip + mLen < ipEnd && src[ip + mLen] == src[mPos + mLen]) mLen++
            var mOff = ip - mPos
            ip += mLen
            if (mLen <= Lzo1x.M2_MAX_LEN && mOff <= Lzo1x.M2_MAX_OFFSET) {
                mOff -= 1
                dst[o++] = (((mLen - 1) shl 5) or ((mOff and 7) shl 2)).toByte()
                dst[o++] = (mOff ushr 3).toByte()
            } else if (mOff <= Lzo1x.M3_MAX_OFFSET) {
                mOff -= 1
                if (mLen <= Lzo1x.M3_MAX_LEN) dst[o++] = (Lzo1x.M3_MARKER or (mLen - 2)).toByte()
                else {
                    var ml = mLen - Lzo1x.M3_MAX_LEN
                    dst[o++] = Lzo1x.M3_MARKER.toByte()
                    while (ml > 255) { ml -= 255; dst[o++] = 0 }
                    dst[o++] = ml.toByte()
                }
                dst[o++] = (mOff shl 2).toByte()
                dst[o++] = (mOff ushr 6).toByte()
            } else {
                mOff -= 0x4000
                if (mLen <= Lzo1x.M4_MAX_LEN) dst[o++] = (Lzo1x.M4_MARKER or ((mOff ushr 11) and 8) or (mLen - 2)).toByte()
                else {
                    var ml = mLen - Lzo1x.M4_MAX_LEN
                    dst[o++] = (Lzo1x.M4_MARKER or ((mOff ushr 11) and 8)).toByte()
                    while (ml > 255) { ml -= 255; dst[o++] = 0 }
                    dst[o++] = ml.toByte()
                }
                dst[o++] = (mOff shl 2).toByte()
                dst[o++] = (mOff ushr 6).toByte()
            }
            stateOffset = -2
            ii = ip
            skip = false
        }
        op = o
        return inEnd - (ii - ti)
    }

    override fun decompress(src: ByteArray, off: Int, len: Int, dst: ByteArray, dstOff: Int, dstCap: Int): Int =
        lzo1xDecompress(src, off, len, dst, dstOff, dstCap)
}

/** lzo1x_decompress_safe: bitstream version 0, every read and copy checked; references before dstOff are refused. */
fun lzo1xDecompress(src: ByteArray, off: Int, len: Int, dst: ByteArray, dstOff: Int, dstCap: Int): Int {
    if (len < 3) throw LzoException("input overrun")
    val ipEnd = off + len
    val opEnd = dstOff + dstCap
    var ip = off
    var op = dstOff
    if (len >= 5 && (src[ip].toInt() and 0xFF) == 17) {
        val version = src[ip + 1].toInt() and 0xFF
        if (version != 0) throw LzoException("bitstream version $version (lzo-rle) is not supported")
        ip += 2
    }
    var state = 0
    var next: Int
    var t: Int
    var mPos: Int
    val first = src[ip].toInt() and 0xFF
    if (first > 17) {
        ip++
        t = first - 17
        if (t > ipEnd - ip) throw LzoException("input overrun")
        if (t > opEnd - op) throw LzoException("output overrun")
        src.copyInto(dst, op, ip, ip + t)
        ip += t
        op += t
        state = if (t < 4) t else 4
    }
    while (true) {
        if (ip >= ipEnd) throw LzoException("input overrun")
        t = src[ip++].toInt() and 0xFF
        if (t < 16) {
            if (state == 0) {
                if (t == 0) {
                    var zeros = 0
                    while (true) {
                        if (ip >= ipEnd) throw LzoException("input overrun")
                        if (src[ip].toInt() != 0) break
                        ip++
                        if (++zeros > dstCap / 255 + 1) throw LzoException("literal run exceeds the output")
                    }
                    t = 15 + 255 * zeros + (src[ip++].toInt() and 0xFF)
                }
                t += 3
                if (t > ipEnd - ip) throw LzoException("input overrun")
                if (t > opEnd - op) throw LzoException("output overrun")
                src.copyInto(dst, op, ip, ip + t)
                ip += t
                op += t
                state = 4
                continue
            } else if (state != 4) {
                if (ip >= ipEnd) throw LzoException("input overrun")
                next = t and 3
                mPos = op - 1 - (t ushr 2) - ((src[ip++].toInt() and 0xFF) shl 2)
                if (mPos < dstOff) throw LzoException("lookbehind overrun")
                if (2 > opEnd - op) throw LzoException("output overrun")
                dst[op] = dst[mPos]
                dst[op + 1] = dst[mPos + 1]
                op += 2
                if (next > ipEnd - ip) throw LzoException("input overrun")
                if (next > opEnd - op) throw LzoException("output overrun")
                src.copyInto(dst, op, ip, ip + next)
                ip += next
                op += next
                state = next
                continue
            } else {
                if (ip >= ipEnd) throw LzoException("input overrun")
                next = t and 3
                mPos = op - (1 + Lzo1x.M2_MAX_OFFSET) - (t ushr 2) - ((src[ip++].toInt() and 0xFF) shl 2)
                t = 3
            }
        } else if (t >= 64) {
            if (ip >= ipEnd) throw LzoException("input overrun")
            next = t and 3
            mPos = op - 1 - ((t ushr 2) and 7) - ((src[ip++].toInt() and 0xFF) shl 3)
            t = (t ushr 5) + 1
        } else if (t >= 32) {
            t = (t and 31) + 2
            if (t == 2) {
                var zeros = 0
                while (true) {
                    if (ip >= ipEnd) throw LzoException("input overrun")
                    if (src[ip].toInt() != 0) break
                    ip++
                    if (++zeros > dstCap / 255 + 1) throw LzoException("match exceeds the output")
                }
                t += 31 + 255 * zeros + (src[ip++].toInt() and 0xFF)
            }
            if (2 > ipEnd - ip) throw LzoException("input overrun")
            val le = (src[ip].toInt() and 0xFF) or ((src[ip + 1].toInt() and 0xFF) shl 8)
            ip += 2
            mPos = op - 1 - (le ushr 2)
            next = le and 3
        } else {
            mPos = op - ((t and 8) shl 11)
            t = (t and 7) + 2
            if (t == 2) {
                var zeros = 0
                while (true) {
                    if (ip >= ipEnd) throw LzoException("input overrun")
                    if (src[ip].toInt() != 0) break
                    ip++
                    if (++zeros > dstCap / 255 + 1) throw LzoException("match exceeds the output")
                }
                t += 7 + 255 * zeros + (src[ip++].toInt() and 0xFF)
            }
            if (2 > ipEnd - ip) throw LzoException("input overrun")
            val le = (src[ip].toInt() and 0xFF) or ((src[ip + 1].toInt() and 0xFF) shl 8)
            ip += 2
            mPos -= le ushr 2
            next = le and 3
            if (mPos == op) {
                if (t != 3) throw LzoException("malformed end of stream")
                if (ip != ipEnd) throw LzoException("input not consumed")
                return op - dstOff
            }
            mPos -= 0x4000
        }
        if (mPos < dstOff) throw LzoException("lookbehind overrun")
        if (t > opEnd - op) throw LzoException("output overrun")
        for (k in 0 until t) dst[op + k] = dst[mPos + k]
        op += t
        if (next > ipEnd - ip) throw LzoException("input overrun")
        if (next > opEnd - op) throw LzoException("output overrun")
        src.copyInto(dst, op, ip, ip + next)
        ip += next
        op += next
        state = next
    }
}
