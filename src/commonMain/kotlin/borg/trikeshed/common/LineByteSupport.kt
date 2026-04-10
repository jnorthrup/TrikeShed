package borg.trikeshed.common

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries

internal fun byteLineSequence(bytes: ByteArray): Sequence<Join<Long, ByteArray>> =
    sequence {
        var lineStart = 0
        var index = 0
        while (index < bytes.size) {
            if (bytes[index] == '\n'.code.toByte()) {
                val endExclusive = index + 1
                yield(lineStart.toLong() j bytes.copyOfRange(lineStart, endExclusive))
                lineStart = endExclusive
            }
            index++
        }

        if (lineStart < bytes.size) {
            val tail = bytes.copyOfRange(lineStart, bytes.size)
            if (tail.isNotEmpty()) {
                yield(lineStart.toLong() j tail)
            }
        }
    }

internal fun byteLineIterable(bytes: ByteArray): Iterable<Join<Long, Series<Byte>>> =
    byteLineSequence(bytes)
        .map { (offset, lineBytes) -> offset j lineBytes.toSeries() }
        .asIterable()

internal fun overwriteByteAt(
    bytes: ByteArray,
    index: Int,
    value: Byte,
): ByteArray {
    require(index >= 0) { "negative byte index: $index" }
    return if (index < bytes.size) {
        bytes.copyOf().also { it[index] = value }
    } else {
        ByteArray(index + 1).also { expanded ->
            bytes.copyInto(expanded)
            expanded[index] = value
        }
    }
}

internal fun checkedFileOffset(offset: Long): Int {
    require(offset >= 0L) { "negative file offset: $offset" }
    require(offset <= Int.MAX_VALUE.toLong()) { "file offset exceeds JS/Wasm limit: $offset" }
    return offset.toInt()
}
