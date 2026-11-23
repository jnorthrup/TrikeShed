package borg.trikeshed.parse.parquet

import borg.trikeshed.tilting.zran.le32

/** Snappy's raw block format, the one parquet's SNAPPY codec writes: a varint size, then literal and copy elements. */
object Snappy {
    fun decode(src: ByteArray): ByteArray {
        var p = 0
        var size = 0
        var shift = 0
        while (true) {
            val b = src[p++].toInt() and 0xFF
            size = size or ((b and 0x7F) shl shift)
            if (b < 0x80) break
            shift += 7
        }
        val out = ByteArray(size)
        var o = 0
        while (p < src.size) {
            val tag = src[p++].toInt() and 0xFF
            if (tag and 3 == 0) {
                var n = tag ushr 2
                if (n >= 60) {
                    var v = 0
                    for (k in 0 until n - 59) v = v or ((src[p++].toInt() and 0xFF) shl (8 * k))
                    n = v
                }
                n += 1
                src.copyInto(out, o, p, p + n)
                p += n
                o += n
                continue
            }
            val n: Int
            val back: Int
            when (tag and 3) {
                1 -> {
                    n = ((tag ushr 2) and 7) + 4
                    back = ((tag ushr 5) shl 8) or (src[p++].toInt() and 0xFF)
                }
                2 -> {
                    n = (tag ushr 2) + 1
                    back = (src[p].toInt() and 0xFF) or ((src[p + 1].toInt() and 0xFF) shl 8)
                    p += 2
                }
                else -> {
                    n = (tag ushr 2) + 1
                    back = src.le32(p)
                    p += 4
                }
            }
            for (k in 0 until n) out[o + k] = out[o - back + k]
            o += n
        }
        check(o == size) { "snappy: $o bytes written, $size declared" }
        return out
    }
}
