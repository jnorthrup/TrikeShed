@file:Suppress("unused")

package borg.trikeshed.lib

/**
 * Centralized Series<Char> helpers backed by CharSeries.
 * Moved out of Yaml.kt to avoid duplicate inline implementations and reduce GC churn.
 */

fun Series<Char>.slice(start: Int, endExclusive: Int = Int.MAX_VALUE): Series<Char> {
    val end = endExclusive.coerceAtMost(size)
    return if (end <= start) "".toSeries() else this[start until end]
}

fun Series<Char>.isEmpty(): Boolean = size == 0

fun Series<Char>.trim(): Series<Char> = CharSeries(this).trim.slice

fun Series<Char>.trimStart(): Series<Char> = CharSeries(this).apply {
    while (hasRemaining && this[pos].isWhitespace()) pos++
}.slice

fun Series<Char>.trimEnd(): Series<Char> = CharSeries(this).rtrim.slice

fun Series<Char>.drop(count: Int): Series<Char> = slice(count.coerceAtMost(size), size)

fun Series<Char>.startsWith(prefix: String): Boolean =
    size >= prefix.length && prefix.indices.all { index -> this[index] == prefix[index] }

fun Series<Char>.endsWith(suffix: String): Boolean =
    size >= suffix.length && suffix.indices.all { index -> this[size - suffix.length + index] == suffix[index] }

fun Series<Char>.leadingWhitespace(): Int {
    for (index in 0 until size) if (!this[index].isWhitespace()) return index
    return size
}

fun Series<Char>.matches(literal: String): Boolean =
    size == literal.length && literal.indices.all { index -> this[index] == literal[index] }

fun Series<Char>.isQuoted(quote: Char): Boolean =
    size >= 2 && this[0] == quote && this[size - 1] == quote

fun Series<Char>.toIntOrNull(): Int? {
    if (size == 0) return null
    var idx = 0
    var sign = 1
    if (this[0] == '+' || this[0] == '-') {
        if (this[0] == '-') sign = -1
        idx++
        if (idx >= size) return null
    }
    var res = 0L
    while (idx < size) {
        val c = this[idx]
        if (c < '0' || c > '9') return null
        res = res * 10 + (c - '0')
        if (res > Int.MAX_VALUE.toLong() + if (sign < 0) 1L else 0L) return null
        idx++
    }
    return (res * sign).toInt()
}

fun Series<Char>.toLongOrNull(): Long? {
    if (size == 0) return null
    var idx = 0
    var sign = 1
    if (this[0] == '+' || this[0] == '-') {
        if (this[0] == '-') sign = -1
        idx++
        if (idx >= size) return null
    }
    var res = 0L
    while (idx < size) {
        val c = this[idx]
        if (c < '0' || c > '9') return null
        val digit = (c - '0')
        if (res > (Long.MAX_VALUE - digit) / 10) return null
        res = res * 10 + digit
        idx++
    }
    return res * sign
}

fun Series<Char>.toDoubleOrNull(): Double? {
    if (size == 0) return null
    var idx = 0
    val n = size
    var sign = 1.0
    if (this[idx] == '+' || this[idx] == '-') {
        if (this[idx] == '-') sign = -1.0
        idx++
        if (idx >= n) return null
    }
    var intPart = 0L
    while (idx < n && this[idx] in '0'..'9') {
        intPart = intPart * 10 + (this[idx] - '0')
        idx++
    }
    var fracPart = 0.0
    var fracDiv = 1.0
    if (idx < n && this[idx] == '.') {
        idx++
        if (idx >= n) return null
        while (idx < n && this[idx] in '0'..'9') {
            fracPart = fracPart * 10 + (this[idx] - '0')
            fracDiv *= 10.0
            idx++
        }
    }
    var exp = 0
    var expSign = 1
    if (idx < n && (this[idx] == 'e' || this[idx] == 'E')) {
        idx++
        if (idx >= n) return null
        if (this[idx] == '+' || this[idx] == '-') {
            if (this[idx] == '-') expSign = -1
            idx++
        }
        if (idx >= n) return null
        while (idx < n && this[idx] in '0'..'9') {
            exp = exp * 10 + (this[idx] - '0')
            idx++
        }
    }
    if (idx != n) return null
    var value = (intPart.toDouble() + if (fracDiv > 1.0) fracPart / fracDiv else 0.0) * sign
    if (exp != 0) value *= Math.pow(10.0, (expSign * exp).toDouble())
    return value
}
