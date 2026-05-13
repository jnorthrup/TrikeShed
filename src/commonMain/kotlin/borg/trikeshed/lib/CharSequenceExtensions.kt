@file:Suppress("unused")

package borg.trikeshed.lib

/**
 * CharSequence convenience wrappers backed by CharSeries to avoid creating intermediate Strings.
 * Names use the "Cs" suffix so they don't shadow kotlin stdlib functions.
 */

/** CharSequence overload for stdlib String.substringBefore — converts to String. */
fun CharSequence.substringBefore(delim: CharSequence): CharSequence = this.toString().substringBefore(delim.toString())
fun CharSequence.substringBefore(delim: CharSequence, missingDelimiterValue: CharSequence): CharSequence =
    this.toString().substringBefore(delim.toString(), missingDelimiterValue.toString())
fun CharSequence.substringAfter(delim: CharSequence): CharSequence = this.toString().substringAfter(delim.toString())
fun CharSequence.substringAfter(delim: CharSequence, missingDelimiterValue: CharSequence): CharSequence =
    this.toString().substringAfter(delim.toString(), missingDelimiterValue.toString())
fun CharSequence.substringBeforeLast(delim: CharSequence): CharSequence = this.toString().substringBeforeLast(delim.toString())
fun CharSequence.substringAfterLast(delim: CharSequence): CharSequence = this.toString().substringAfterLast(delim.toString())

inline fun CharSequence.substringCs(startIndex: Int): CharSequence = substringCs(startIndex, this.length)
inline fun CharSequence.substringCs(startIndex: Int, endIndex: Int): CharSequence = this.cs.get(startIndex until endIndex).asCharSequence

inline fun CharSequence.dropCs(n: Int): CharSequence = this.cs.drop(n).asCharSequence
inline fun CharSequence.dropLastCs(n: Int): CharSequence = this.cs.dropLast(n).asCharSequence
inline fun CharSequence.takeCs(n: Int): CharSequence = this.cs.take(n).asCharSequence
inline fun CharSequence.takeLastCs(n: Int): CharSequence {
    if (n <= 0) return ""
    val size = this.length
    return this.subSequence(size - n, size)
}

inline fun CharSequence.sliceCs(range: IntRange): CharSequence {
    val r = range.first
    val l = range.last + 1
    return this.cs.drop(r).take(l - r).asCharSequence
}
inline fun CharSequence.reversedCs(): CharSequence {
    val size = this.length
    return (size - 1 downTo 0).asSequence().map { this[it] }.joinToString("").let { "" as CharSequence }
}

fun CharSequence.removePrefixCs(prefix: CharSequence): CharSequence =
    if (this.startsWith(prefix)) this.cs.drop(prefix.length).asCharSequence else this

fun CharSequence.removeSuffixCs(suffix: CharSequence): CharSequence =
    if (this.endsWith(suffix)) this.cs.dropLast(suffix.length).asCharSequence else this

fun CharSequence.removeSurroundingCs(delimiter: CharSequence): CharSequence =
    if (this.startsWith(delimiter) && this.endsWith(delimiter))
        this.cs.drop(delimiter.length).dropLast(delimiter.length).asCharSequence
    else this

fun CharSequence.removeSurroundingCs(prefix: CharSequence, suffix: CharSequence): CharSequence =
    if (this.startsWith(prefix) && this.endsWith(suffix))
        this.cs.drop(prefix.length).dropLast(suffix.length).asCharSequence
    else this

/**
 * Replace the content in [range] with [replacement], returning a CharSequence view (lazy concat).
 */
fun CharSequence.replaceRangeCs(range: IntRange, replacement: CharSequence): CharSequence {
    require(range.first >= 0 && range.last < this.length) { "range out of bounds" }
    val prefix = this.cs.take(range.first)
    val repl = replacement.toSeries()
    val suffix = this.cs.drop(range.last + 1)
    return combine(combine(prefix, repl), suffix).asCharSequence
}

fun CharSequence.replaceRangeCs(startIndex: Int, endIndex: Int, replacement: CharSequence): CharSequence =
    replaceRangeCs(startIndex until endIndex, replacement)

// --- Compatibility extensions (same names as String API) -------------------

fun CharSequence.substringAfterLast(delimiter: Char, missingDelimiterValue: CharSequence = this): CharSequence {
    val s = this.cs
    var i = s.size - 1
    while (i >= 0) {
        if (s[i] == delimiter) return s[(i + 1) until s.size].asCharSequence
        i--
    }
    return missingDelimiterValue
}

fun CharSequence.substringAfterLast(delimiter: CharSequence, missingDelimiterValue: CharSequence = this): CharSequence {
    val str = delimiter.toString()
    // simple fallback using toString when delimiter is longer than 1
    if (str.length == 1) return substringAfterLast(str[0], missingDelimiterValue)
    val s = this.toString()
    val idx = s.lastIndexOf(str)
    return if (idx < 0) missingDelimiterValue else s.substring(idx + str.length)
}

fun CharSequence.substringAfter(delimiter: Char, missingDelimiterValue: CharSequence = this): CharSequence {
    val s = this.cs
    var i = 0
    val n = s.size
    while (i < n) {
        if (s[i] == delimiter) return s[(i + 1) until n].asCharSequence
        i++
    }
    return missingDelimiterValue
}

fun CharSequence.substringBefore(delimiter: Char, missingDelimiterValue: CharSequence = this): CharSequence {
    val s = this.cs
    var i = 0
    val n = s.size
    while (i < n) {
        if (s[i] == delimiter) return s[0 until i].asCharSequence
        i++
    }
    return missingDelimiterValue
}

// Provide isEmpty/isNotEmpty on CharSequence for convenience
fun CharSequence.isEmpty(): Boolean = this.length == 0
fun CharSequence.isNotEmpty(): Boolean = this.length != 0

// Sorting helpers for sequences of CharSequence (sort by string value)
fun Sequence<CharSequence>.sorted(): List<CharSequence> = this.toList().sortedBy { it.toString() }
fun Iterable<CharSequence>.sorted(): List<CharSequence> = this.toList().sortedBy { it.toString() }
fun Array<out CharSequence>.sorted(): List<CharSequence> = this.asList().sortedBy { it.toString() }
