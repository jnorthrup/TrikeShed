package borg.trikeshed.common

import borg.trikeshed.lib.*

/** Series with long Indexes for large files */
typealias LongSeries<T> = Join<Long, (Long) -> T>

val <T> LongSeries<T>.size: Long get() = a

/** index operator for borg.trikeshed.common.LongSeries
 */
operator fun <T> LongSeries<T>.get(i: Long): T = b(i)

/**int-based series is returned for intRange */
operator fun <T> LongSeries<T>.get(exclusiveRange: IntRange): Series<T> {
    //perform fixup between the range and the Series x index
    return (exclusiveRange.last - exclusiveRange.first) j { x -> this[(exclusiveRange.first + x).toLong()] }
}

operator fun <T> LongSeries<T>.get(r: LongRange): LongSeries<T> {
    //perform fixup between the range and the Series x index
    return (r.last - r.first) j { x -> this[(r.first + x)] }
}

fun <T> LongSeries<T>.slice(start: Long, end: Long = size): LongSeries<T> =
    (end - start) j { x -> this[start + x] }

fun <T> LongSeries<T>.drop(removeInitial: Long): LongSeries<T> = slice(removeInitial)

fun Series<Byte>.decodeUtf8(charArray:CharArray= CharArray(size)): Series<Char> {
    //does not use StringBuilder, but is faster than String(bytes, Charsets.UTF_8)
    var y = 0
    var w = 0
    while (y < this.size && w < charArray.size) {
        val c = this[y++].toInt()
        /* 0xxxxxxx */
        when (c shr 4) {
            in 0..7 -> charArray[w++] = c.toChar() // 0xxxxxxx

            12, 13 -> {
                // 110x xxxx   10xx xxxx
                val c2 = this[y++].toInt()
                charArray[w++] = ((c and 0x1F) shl 6 or (c2 and 0x3F)).toChar()
            }

            14 -> {
                // 1110 xxxx  10xx xxxx  10xx xxxx
                val c2 = this[y++].toInt()
                val c3 = this[y++].toInt()
                charArray[w++] = ((c and 0x0F) shl 12 or (c2 and 0x3F) shl 6 or (c3 and 0x3F)).toChar()
            }
        }
    }
    return w j charArray::get
}

fun Series<Byte>.asString(): String = toArray().decodeToString()


