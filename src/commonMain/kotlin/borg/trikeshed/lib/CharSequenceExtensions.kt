@file:Suppress("unused")

package borg.trikeshed.lib

/**
 * CharSequence convenience wrappers backed by CharSeries to avoid creating intermediate Strings.
 * Names use the "Cs" suffix so they don't shadow kotlin stdlib functions.
 */

inline fun CharSequence.substringCs(startIndex: Int): CharSequence = substringCs(startIndex, this.length)
inline fun CharSequence.substringCs(startIndex: Int, endIndex: Int): CharSequence = this.cs.get(startIndex until endIndex).asCharSequence

inline fun CharSequence.dropCs(n: Int): CharSequence = this.cs.drop(n).asCharSequence
inline fun CharSequence.dropLastCs(n: Int): CharSequence = this.cs.dropLast(n).asCharSequence
inline fun CharSequence.takeCs(n: Int): CharSequence = this.cs.take(n).asCharSequence
inline fun CharSequence.takeLastCs(n: Int): CharSequence = this.cs.takeLast(n).asCharSequence

inline fun CharSequence.sliceCs(range: IntRange): CharSequence = this.cs[range].asCharSequence
inline fun CharSequence.reversedCs(): CharSequence = this.cs.reversed().asCharSequence

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
    val combined = combine(prefix, repl, suffix)
    return combined.asCharSequence
}

fun CharSequence.replaceRangeCs(startIndex: Int, endIndex: Int, replacement: CharSequence): CharSequence =
    replaceRangeCs(startIndex until endIndex, replacement)
