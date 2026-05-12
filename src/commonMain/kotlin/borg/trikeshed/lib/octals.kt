package borg.trikeshed.lib

import kotlin.math.pow

fun Int.toOctal(): Int {
    var n = this
    var octalNumber = 0
    var i = 0
    while (n != 0) {
        octalNumber += (n % 8 * 10.0.pow(i.toDouble())).toInt()
        n /= 8
        ++i
    }
    return octalNumber
}

fun Int.fromOctal(): Int {
    var n = this
    var decimalNumber = 0
    var i = 0
    while (n != 0) {
        decimalNumber += (n % 10 * 8.0.pow(i.toDouble())).toInt()
        n /= 10
        ++i
    }
    return decimalNumber
}

val Int.fromBinary: Int get() = this.toLong().fromBinary
val Long.fromBinary: Int get() {
    var x = this
    var r = 0
    var i = 0
    while (x != 0L) {
        r += (x and 1L).toInt() shl i
        x = x shr 1
        i++
    }
    return r
}

fun String.replaceChar(old: Char, new: Char): String {
    val sb = StringBuilder(length)
    for (i in 0 until length) {
        sb.append(if (this[i] == old) new else this[i])
    }
    return sb.toString()
}

/**
 * Splits a byte array into lines, yielding (offset, lineBytes) pairs.
 * Line terminator (\n) is excluded from the yielded bytes.
 */
fun streamByteLines(bytes: ByteArray): Sequence<Join<Long, ByteArray>> = sequence {
    var offset = 0L
    var lineStart = 0L
    val line = ArrayList<Byte>()

    for (byte in bytes) {
        line += byte
        offset++
        if (byte == '\n'.code.toByte()) {
            yield(lineStart j line.toByteArray())
            line.clear()
            lineStart = offset
        }
    }

    if (line.isNotEmpty()) {
        yield(lineStart j line.toByteArray())
    }
}

object binaryPrefix {
    infix fun invoke(p1: Long): Int = p1.fromBinary
    infix fun invoke(p1: Int): Int = p1.fromBinary
}

object octalPrefix {
    infix fun invoke(p1: Int): Int = p1.fromOctal()
}