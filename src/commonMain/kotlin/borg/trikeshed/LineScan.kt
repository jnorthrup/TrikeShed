/**
 * Line scanning on LongSeries<Byte> — the lineSequence analog for file-backed byte streams.
 *
 * Architecture:
 *   SeekFileBufferCommon (LongSeries<Byte>)
 *     -> lineOffsets()   scans for '\n', returns LongArray of line-start positions
 *     -> lines()         Series<ByteSeries> — zero-copy views, pos/limit per line
 *     -> charLines()     Series<CharSeries> — ByteSeries.decodeUtf8() per line, single-pass
 *     -> csvSplit(',')   Series<Series<Series<Char>>> — comma-split fields per line
 *
 * Each ByteSeries view delegates reads back to the parent LongSeries (windowed 64KB pread).
 * No bytes are copied until decodeUtf8() materializes a CharArray per line.
 */
package borg.trikeshed

import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.CharSeries
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.decodeUtf8
import borg.trikeshed.lib.div
import borg.trikeshed.lib.j

/**
 * Scan [LongSeries<Byte>] for newline boundaries.
 * Returns LongArray where each element is the START offset of a line.
 */
fun LongSeries<Byte>.lineOffsets(): LongArray {
    val offsets = mutableListOf<Long>()
    val sz: Long = size
    var i = 0L
    // skip UTF-8 BOM if present
    if (sz >= 3 && this[0L] == 0xEF.toByte() && this[1L] == 0xBB.toByte() && this[2L] == 0xBF.toByte()) {
        i = 3
    }
    var lineStart = i
    offsets.add(lineStart)

    while (i < sz) {
        val b = this[i]
        if (b == '\n'.code.toByte()) {
            lineStart = i + 1
            if (lineStart < sz) offsets.add(lineStart)
            i++
        } else if (b == '\r'.code.toByte()) {
            lineStart = i + 1
            if (lineStart < sz) {
                if (this[lineStart] == '\n'.code.toByte()) {
                    lineStart++
                }
                offsets.add(lineStart)
            }
            i = lineStart
        } else {
            i++
        }
    }
    return offsets.toLongArray()
}

/**
 * Zero-copy line views into the underlying LongSeries<Byte>.
 */
fun LongSeries<Byte>.lines(): Series<ByteSeries> {
    val offsets = lineOffsets()
    val sz: Long = size
    return offsets.size j { idx: Int ->
        val start = offsets[idx]
        val nextStart = if (idx + 1 < offsets.size) offsets[idx + 1] else sz
        var end = nextStart
        while (end > start) {
            val b = this[end - 1]
            if (b == '\n'.code.toByte() || b == '\r'.code.toByte()) end-- else break
        }
        val len = (end - start).toInt()
        ByteSeries(len j { x: Int -> this[start + x] })
    }
}

/**
 * Upcast each line ByteSeries to CharSeries via decodeUtf8().
 */
fun LongSeries<Byte>.charLines(): Series<CharSeries> {
    val byteLines = lines()
    return byteLines.a j { idx: Int ->
        CharSeries(byteLines.b(idx).decodeUtf8())
    }
}

/**
 * CSV field splitter on charLines.
 */
fun LongSeries<Byte>.csvSplit(delim: Char = ','): Series<Series<Series<Char>>> {
    val cl = charLines()
    return cl.a j { rowIdx: Int ->
        cl.b(rowIdx) / delim
    }
}
