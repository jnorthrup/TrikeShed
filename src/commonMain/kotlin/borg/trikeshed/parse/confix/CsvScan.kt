package borg.trikeshed.parse.confix

import borg.trikeshed.lib.*
import borg.trikeshed.lib.Series

/* ═══════════════════════════════════════════════════════════════════════════════
 *
 *  CSV tokenizer → Series<JsElement>
 * ═══════════════════════════════════════════════════════════════════════════ */
/* ═══════════════════════════════════════════════════════════════════════════
 *
 *  CSV tokenizer → Series<JsElement> over Series<Char>.
 *
 *  Algebraically identical to JSON: a CSV is treated as a virtual array of
 *  rows, each row as a virtual array of fields. No stdlib collections.
 *
 *  Top-level input is the first line (header) or a data line.
 *  Each line emits one JsElement with:
 *    open  = line start offset in src
 *    close = line end   offset in src
 *    commas = start offsets of each comma-delimited field within the line
 *
 *  Tag.ARRAY is used for every line — the reifier sees it as an array and
 *  walks field boundaries through commas. Field text is sliced directly from
 *  src[open..close] by the caller (KlineCsvParser adapter).
 *
 *  Sentinels used:
 *    Tag.NULL   (-7) — blank or whitespace-only line (skipped by parser)
 *    Tag.STRING (-3) — field with embedded comma or double-quote
 *    Tag.NUMBER (-4) — purely numeric field (no dots/quotes) — hint for fast path
 * ═══════════════════════════════════════════════════════════════════════════ */
object CsvScan {

    /** Scan a raw CSV text into JsElement series — one element per non-blank line.
     *
     * Each JsElement has:
     *   - open  = line start offset in src
     *   - close = line end   offset in src (exclusive, trimmed of whitespace)
     *   - commas = start offsets of each comma-delimited field
     *
     * Blank/whitespace-only lines are emitted as NULL-tagged elements.
     */
    fun scan(src: Series<Char>): Series<JsElement> {
        val cs = CharSeries(src)
        val out = ElemBuf()
        scanLines(cs, out)
        return out.toSeries()
    }

   fun scanLines(cs: CharSeries, out: ElemBuf) {
        while (cs.hasRemaining) {
            val lineOpen = cs.pos
            // advance to end-of-line (LF or CR+LF)
            while (cs.hasRemaining && cs[cs.pos] != '\n' && cs[cs.pos] != '\r') cs.inc()
            var lineClose = cs.pos  // exclusive — one-past-last-content-char
            // consume line terminator(s)
            if (cs.hasRemaining && cs[cs.pos] == '\r') cs.inc()
            if (cs.hasRemaining && cs[cs.pos] == '\n') cs.inc()

            // trim trailing whitespace from the content end
            while (lineClose > lineOpen && cs[lineClose - 1].isWhitespace()) lineClose--

            if (lineClose == lineOpen) {
                // blank line — emit NULL-tagged element
                val idx = out.beginTagged(lineOpen, Tag.NULL)
                out.endOf(idx, lineClose, Tag.NULL)
                continue
            }

            // One JsElement per line. Record field-start positions (at lineOpen + after each comma).
            val idx = out.begin(lineOpen)
            out.addComma(lineOpen)  // first field starts at line open
            var pos = lineOpen
            while (pos < lineClose) {
                if (cs[pos] == ',') out.addComma(pos + 1)  // next field starts after comma
                pos++
            }

            // If the line ends with a comma (trailing field separator), that comma
            // was included in the scan above but should not contribute a field.
            // The last field value runs from commas[last]+1 to lineClose.
            // Adjust the element's close backward so fieldCount = commas.size (not +1).
            if (cs[lineClose - 1] == ',') lineClose--

            out.endOf(idx, lineClose, Tag.ARRAY)
        }
    }

    /** Return the text of field `fieldIdx` (0-based) from a CSV-line JsElement, trimmed.
     *  Returns null if fieldIdx is out of range or the field is blank. */
    fun fieldText(e: JsElement, src: Series<Char>, fieldIdx: Int): CharSequence? {
        val commas = e.b
        val n = commas.size
        if (fieldIdx < 0 || fieldIdx >= n) return null
        // commas[k] = field-start position; commas[k+1]-1 = field end (excl. comma), e.a.b = line close
        val fieldStart = commas[fieldIdx]
        val fieldEnd = if (fieldIdx + 1 < n) commas[fieldIdx + 1] - 1 else e.a.b
        var start = fieldStart
        var end = fieldEnd
        // trim leading whitespace
        while (start < end && src[start].isWhitespace()) start++
        // trim trailing whitespace
        while (end > start && src[end - 1].isWhitespace()) end--
        if (end <= start) return null
        val len = end - start
        val chars = CharArray(len) { src[start + it] }
        return chars.concatToString()
    }

    /** Parse field `fieldIdx` as Long. Returns null if absent/blank/non-numeric. */
    fun fieldLong(e: JsElement, src: Series<Char>, fieldIdx: Int): Long? {
        val t = fieldText(e, src, fieldIdx) ?: return null
        return t.toString().toLongOrNull()
    }

    /** Parse field `fieldIdx` as Double. Returns null if absent/blank/non-numeric. */
    fun fieldDouble(e: JsElement, src: Series<Char>, fieldIdx: Int): Double? {
        val t = fieldText(e, src, fieldIdx) ?: return null
        return t.toString().toDoubleOrNull()
    }
}
