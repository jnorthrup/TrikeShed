package borg.trikeshed.cas

import borg.trikeshed.lib.*

/** Half-open UTF-16 code-unit range `[start, end)` into an ORIGINAL (un-normalized) document. */
data class SourceRange(val start: Int, val end: Int) {
    init {
        require(start in 0..end) { "invalid source range [$start, $end)" }
    }

    val length: Int get() = end - start
}

/**
 * One non-blank line of an original document, located three ways.
 *
 * - [index]: position among ALL raw lines, numbered the way `String.lineSequence()` numbers them.
 * - [line]: the whole raw line, terminator excluded, indentation intact.
 * - [text]: the trimmed text, which is exactly what [LineCas] content-addressed.
 */
data class SourceLine(val index: Int, val line: SourceRange, val text: SourceRange)

/**
 * Maps spine ordinals back to the document they came from.
 *
 * [LineCas.spine] / [LineCas.spineInto] normalize before addressing: split like `String.lineSequence()`
 * (`\r\n`, `\n`, `\r`), `trim()` each line, drop what is empty. A node's `ordinal` therefore counts surviving
 * lines, not raw lines, and its identity says nothing about where the text sat. Anything that must go from a
 * spine node back to bytes (a citation, a merge locus, an excerpt shown to a model) has to go through this
 * mapping; using an ordinal as a raw line index is wrong as soon as the document has a blank line.
 *
 * Ranges index the ORIGINAL string in UTF-16 code units, so `text.substring(r.start, r.end)` is exact. Spine
 * identity does not carry them (content identity is occurrence-free by design), so callers keep the original
 * document CID next to these ranges and check it before trusting them.
 */
object LineCasSourceRanges {
    /**
     * EVERY raw line, blank ones included, as the half-open range of its content with the terminator excluded; element
     * `i` is line `i` as `String.lineSequence()` numbers it. `text.substring(first.start, last.end)` is the exact original
     * text from one line through another, original terminators between them untouched.
     */
    fun rawLines(text: String): Series<SourceRange> {
        val lines = ArrayList<SourceRange>()
        val n = text.length
        var lineStart = 0
        while (true) {
            var lineEnd = lineStart
            while (lineEnd < n && text[lineEnd] != '\n' && text[lineEnd] != '\r') lineEnd++
            lines.add(SourceRange(lineStart, lineEnd))
            if (lineEnd >= n) break
            lineStart = if (text[lineEnd] == '\r' && lineEnd + 1 < n && text[lineEnd + 1] == '\n') lineEnd + 2 else lineEnd + 1
        }
        return lines.toSeries()
    }

    /** Element `i` is the line that carries spine ordinal `i`; its size equals the spine's size for [text]. */
    fun of(text: String): Series<SourceLine> {
        val lines = ArrayList<SourceLine>()
        val n = text.length
        var lineStart = 0
        var index = 0
        while (true) {
            var lineEnd = lineStart
            while (lineEnd < n && text[lineEnd] != '\n' && text[lineEnd] != '\r') lineEnd++

            var s = lineStart
            var e = lineEnd
            while (s < e && text[s].isWhitespace()) s++
            while (e > s && text[e - 1].isWhitespace()) e--
            if (e > s) lines.add(SourceLine(index, SourceRange(lineStart, lineEnd), SourceRange(s, e)))

            if (lineEnd >= n) break
            // "\r\n" is one terminator; a lone "\r" or "\n" is another. Same as lineSequence().
            lineStart = if (text[lineEnd] == '\r' && lineEnd + 1 < n && text[lineEnd + 1] == '\n') lineEnd + 2 else lineEnd + 1
            index++
        }
        return lines.toSeries()
    }
}
