package borg.trikeshed.lib

import kotlin.math.pow

/**
 * Unified text processing on Series<Char> backed by CharSeries.
 * No String/toString() — CharSeries IS a CharSequence and all ops produce Series<Char>.
 *
 * Architecture:
 * - Series<Char> (alias for MetaSeries<Int,Char>): immutable indexed buffer, no CharSequence view
 * - CharSeries(Series<Char>): mutable cursor that IS A CharSequence
 * - CharSequence: any java.lang.CharSequence, routed through CharSeries to avoid String
 */

/** Raw buffer slice on Series<Char>. Note: CharSeries has a `slice` property — prefer CharSeries for text. */
fun Series<Char>.bufferSlice(start: Int, endExclusive: Int = Int.MAX_VALUE): Series<Char> {
    val end = endExclusive.coerceAtMost(size)
    return if (end <= start) "".toSeries() else this[start until end]
}

/** Subrange slice — delegates to bufferSlice for compatibility with code expecting slice(start,end). */
fun Series<Char>.slice(start: Int, endExclusive: Int): Series<Char> = bufferSlice(start, endExclusive)

fun Series<Char>.isEmpty(): Boolean = size == 0

/** trim, trimStart, trimEnd delegate to CharSeries cursor. */
fun Series<Char>.trim(): Series<Char> = CharSeries(this).trim.slice
fun Series<Char>.trimStart(): Series<Char> = CharSeries(this).apply {
    while (hasRemaining && this[pos].isWhitespace()) pos++
}.slice
fun Series<Char>.trimEnd(): Series<Char> = CharSeries(this).rtrim.slice

fun Series<Char>.startsWith(prefix: CharSequence): Boolean =
    size >= prefix.length && prefix.indices.all { index -> this[index] == prefix[index] }

fun Series<Char>.endsWith(suffix: CharSequence): Boolean =
    size >= suffix.length && suffix.indices.all { index -> this[size - suffix.length + index] == suffix[index] }

fun Series<Char>.leadingWhitespace(): Int {
    for (index in 0 until size) if (!this[index].isWhitespace()) return index
    return size
}

fun Series<Char>.matches(literal: CharSequence): Boolean =
    size == literal.length && literal.indices.all { index -> this[index] == literal[index] }

fun Series<Char>.isQuoted(quote: Char): Boolean =
    size >= 2 && this[0] == quote && this[size - 1] == quote

fun Series<Char>.toIntOrNull(): Int? {
    if (size == 0) return null
    var idx = 0; var sign = 1

    if (this[0] == '+' || this[0] == '-') {
        if (this[0] == '-') sign = -1; idx++; if (idx >= size) return null
    }
    var res = 0L
    while (idx < size) {
        val c = this[idx]
        if (c in '0'..'9') {
            res = res * 10 + (c - '0')
            if (res > Int.MAX_VALUE.toLong() + if (sign < 0) 1L else 0L) return null
            idx++
            continue
        }
        return null
    }
    return (res * sign).toInt()
}

fun Series<Char>.toLongOrNull(): Long? {
    if (size != 0) {
        var idx = 0;
        var sign = 1
        if (this[0] == '+' || this[0] == '-') { if (this[0] == '-') sign = -1; idx++; if (idx >= size) return null }
        var res = 0L
        while (idx < size) {
            val c = this[idx]
            if (c in '0'..'9') {
                val digit = (c - '0')
                if (res > (Long.MAX_VALUE - digit) / 10) return null
                res = res * 10 + digit
                idx++
                continue
            }
            return null
        }
        return res * sign
    }
    return null
}

fun Series<Char>.toDoubleOrNull(): Double? {
    if (size == 0) return null
    var idx = 0; val n = size; var sign = 1.0
    if (this[idx] == '+' || this[idx] == '-') { if (this[idx] == '-') sign = -1.0; idx++; if (idx >= n) return null }
    var intPart = 0L
    while (idx < n && this[idx] in '0'..'9') { intPart = intPart * 10 + (this[idx] - '0'); idx++ }
    var fracPart = 0.0; var fracDiv = 1.0
    if (idx < n && this[idx] == '.') {
        idx++; if (idx >= n) return null
        while (idx < n && this[idx] in '0'..'9') { fracPart = fracPart * 10 + (this[idx] - '0'); fracDiv *= 10.0; idx++ }
    }
    var exp = 0; var expSign = 1
    if (idx < n && (this[idx] == 'e' || this[idx] == 'E')) {
        idx++; if (idx >= n) return null
        if (this[idx] == '+' || this[idx] == '-') { if (this[idx] == '-') expSign = -1; idx++ }
        if (idx >= n) return null
        while (idx < n && this[idx] in '0'..'9') { exp = exp * 10 + (this[idx] - '0'); idx++ }
    }
    if (idx != n) return null
    var value = (intPart.toDouble() + if (fracDiv > 1.0) fracPart / fracDiv else 0.0) * sign
    if (exp != 0) value *= 10.0.pow((expSign * exp).toDouble())
    return value
}

/* ═══════════════════════════════════════════════════════════════════════════════
 * CharSeries — cursor that IS a CharSequence; all text ops route through here
 * ═══════════════════════════════════════════════════════════════════════════════ */
fun CharSeries.asCharSequence(): CharSequence = this

/** Substring variants — CharSeries.subSequence already implements CharSequence. */
fun CharSeries.substringCs(startIndex: Int): CharSequence {
    val s = startIndex.coerceIn(0, length)
    return if (s >= length) "" else this.subSequence(s, length)
}
fun CharSeries.substringCs(startIndex: Int, endIndex: Int): CharSequence {
    val s = startIndex.coerceIn(0, length)
    val e = endIndex.coerceIn(s, length)
    return if (e <= s) "" else this.subSequence(s, e)
}

/** Replace range — explicit String concat. */
fun CharSeries.replaceRangeCs(range: IntRange, replacement: CharSequence): CharSequence {
    val cs: CharSeries = this
    val r: Int = range.first.coerceAtLeast(0).coerceAtMost(cs.length)
    val l: Int = (range.last + 1).coerceAtLeast(r).coerceAtMost(cs.length)
    val pre: String = cs.subSequence(0, r).toString()
    val suf: String = cs.subSequence(l, cs.length).toString()
    return pre + replacement.toString() + suf
}


/** Single-char delimiters — common for parsing. */
fun CharSequence.substringBefore(delim: Char): CharSequence {
    val idx = this.indexOf(delim)
    return if (idx < 0) this else this.subSequence(0, idx)
}
fun CharSequence.substringAfter(delim: Char): CharSequence {
    val idx = this.indexOf(delim)
    return if (idx < 0) this else this.subSequence(idx + 1, length)
}

/** Remove surrounding delimiters — works with Char and CharSequence. */
fun CharSequence.removeSurrounding(delimiter: CharSequence): CharSequence {
    if (length >= delimiter.length * 2) {
        val first = this.subSequence(0, delimiter.length)
        val last = this.subSequence(length - delimiter.length, length)
        if (first == delimiter && last == delimiter) {
            return this.subSequence(delimiter.length, length - delimiter.length)
        }
    }
    return this
}

/** CharSeries self-type — resolves toCharSeries() ambiguity when receiver is already CharSeries. */
fun CharSeries.toCharSeries(): CharSeries = this
/** Explicit conversion to CharSeries. */
fun Series<Char>.toCharSeries(): CharSeries = CharSeries(this)
fun CharSequence.toCharSeries(): CharSeries = CharSeries(this)

/** replaceRange — routes through CharSeries. */
fun CharSequence.replaceRangeCs(range: IntRange, replacement: CharSequence): CharSequence {
    val cs: CharSeries = CharSeries(this)
    val r: Int = range.first.coerceAtLeast(0).coerceAtMost(cs.length)
    val l: Int = (range.last + 1).coerceAtLeast(r).coerceAtMost(cs.length)
    val pre: String = cs.subSequence(0, r).toString()
    val suf: String = cs.subSequence(l, cs.length).toString()
    return pre + replacement.toString() + suf
}
