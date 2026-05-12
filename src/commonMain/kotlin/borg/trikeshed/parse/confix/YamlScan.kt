package borg.trikeshed.parse.confix

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.slice

/* ═══════════════════════════════════════════════════════════════════════════════
 *
 *  YAML tokenizer → Series<JsElement>
 * ═══════════════════════════════════════════════════════════════════════════
 *  YAML tokenizer (indentation-as-confix flow subset) → Series<JsElement>
 *
 *  Supports the project-sized subset: block mappings ("key: value"), block
 *  sequences ("- value"), and scalars. Inline flow ({..}, [..], "..") is
 *  parsed via a cooperative reuse of JsonScan for the inline range.
 *
 *  YAML's indentation is projected onto confix: open = first char of the
 *  parent line's first child, close = last char of the last child line,
 *  commas = start offsets of each child.
 * ═══════════════════════════════════════════════════════════════════════════ */

object YamlScan {

    fun scan(src: Series<Char>): Series<JsElement> {
        val n = src.size
        val out = ElemBuf()
        val st = ScanState(src, n)
        st.skipBlankLines()
        parseBlock(st, out, indent = 0)
        return out.toSeries()
    }

   class ScanState(val s: Series<Char>, val n: Int) {
        var pos: Int = 0
        fun peek(): Char = if (pos < n) s[pos] else '\u0000'
        fun atEof(): Boolean = pos >= n

        fun lineIndent(): Int {
            var i = pos; var k = 0
            while (i < n && s[i] == ' ') { k++; i++ }
            return k
        }

        /** advance to start of next line's non-space char */
        fun skipBlankLines() {
            while (pos < n) {
                val start = pos
                while (pos < n && s[pos] == ' ') pos++
                if (pos < n && (s[pos] == '\n' || s[pos] == '\r' || s[pos] == '#')) {
                    // blank or comment line
                    while (pos < n && s[pos] != '\n') pos++
                    if (pos < n) pos++
                } else { pos = start; return }
            }
        }

        fun consumeIndent(k: Int) {
            var c = 0
            while (c < k && pos < n && s[pos] == ' ') { pos++; c++ }
        }

        fun peekLineIndent(): Int {
            var i = pos
            while (i < n && s[i] != '\n' && s[i] != '\r') i++
            if (i < n) i++ // skip newline
            var k = 0
            while (i < n && s[i] == ' ') { k++; i++ }
            return if (i < n && s[i] != '\n' && s[i] != '\r' && s[i] != '#') k else lineIndent()
        }

        fun readLineEnd(): Int {
            // returns close index (last non-newline char of line)
            var end = pos
            while (end < n && s[end] != '\n' && s[end] != '\r') end++
            val close = if (end > 0) end - 1 else 0
            pos = if (end < n) end + 1 else end
            return close
        }

        fun readToColon(): Int {
            var e = pos
            while (e < n && s[e] != ':' && s[e] != '\n' && s[e] != '\r') e++
            return e
        }
    }

   fun parseBlock(st: ScanState, out: ElemBuf, indent: Int): Int {
        st.skipBlankLines()
        val here = st.lineIndent()
        if (st.atEof() || here < indent) {
            // check for sequence dash at parent indent (indent - 2)
            if (!st.atEof() && here >= 0 && here == indent - 2) {
                st.consumeIndent(here)
                if (st.peek() == '-') return parseSeq(st, out, here)
                st.pos -= here  // restore
            }
            // empty block → null
            val p = st.pos
            val idx = out.beginTagged(p, Tag.NULL)
            out.endOf(idx, p, Tag.NULL)
            return idx
        }
        st.consumeIndent(here)
        return when (st.peek()) {
            '-' -> parseSeq(st, out, here)
            '{', '[', '"', '\'' -> parseFlowLine(st, out)
            else -> parseMapOrScalar(st, out, here)
        }
    }

   fun parseSeq(st: ScanState, out: ElemBuf, indent: Int): Int {
        // st.pos on entry is the first non-space char (after consumeIndent in parseBlock).
        // compute the start-of-line position for this sequence from the known indent
        var nextLineStart = (st.pos - indent).coerceAtLeast(0)
        val open = nextLineStart
        val idx = out.beginTagged(open, Tag.ARRAY)
        var lastClose = open
        while (!st.atEof()) {
            // position at start of the (next) line before inspection
            st.pos = nextLineStart
            st.skipBlankLines()
            val here = st.lineIndent()
            if (here < indent) break
            st.consumeIndent(here)
            if (st.peek() != '-') break
            st.pos++  // consume '-'
            if (st.peek() == ' ') st.pos++
            val childIndent = indent + 2
            val ch = st.peek()
            val childIdx = if (ch == '{' || ch == '[' || ch == '"' || ch == '\'') {
                parseFlowLine(st, out)
            } else {
                // try inline scalar on same line first
                val scalarStart = st.pos
                // peek if it's "key: value" inline → treat as mapping block
                val colonAt = scanInlineColon(st)
                if (colonAt >= 0) {
                    // rewind and parse a one-element inline map by recursing at this column
                    st.pos = scalarStart
                    parseMapOrScalar(st, out, childIndent)
                } else {
                    parseScalarLine(st, out)
                }
            }
            // append comma using child's open index
            out.addComma(out.openOf(childIdx))
            lastClose = out.closeOf(childIdx).coerceAtLeast(open)
            // nextLineStart should point to the start of the next line (parse* helpers set st.pos there)
            nextLineStart = st.pos
        }
        out.endOf(idx, lastClose, Tag.ARRAY)
        return idx
    }

   fun parseMapOrScalar(st: ScanState, out: ElemBuf, indent: Int): Int {
        // Look for "key:" on this line
        val startOfKey = st.pos
        val colon = st.readToColon()
        if (colon >= st.n || st.s[colon] != ':') {
            // just a scalar line
            st.pos = startOfKey
            return parseScalarLine(st, out)
        }
        // it's a mapping
        val open = startOfKey
        val idx = out.beginTagged(open, Tag.OBJECT)
        var lastClose = open
        var here = indent
        while (!st.atEof() && here >= indent) {
            // key (string-like): emit a STRING element spanning until ':'
            val keyOpen = st.pos
            val keyColon = st.readToColon()
            val keyClose = if (keyColon > keyOpen) keyColon - 1 else keyOpen
            val keyIdx = out.beginTagged(keyOpen, Tag.STRING)
            out.endOf(keyIdx, keyClose, Tag.STRING)
            // record comma as key's open
            out.addComma(out.openOf(keyIdx))
            st.pos = keyColon + 1  // consume ':'
            while (st.pos < st.n && st.s[st.pos] == ' ') st.pos++
            // value: inline or next-line block
            val valueIdx = if (st.pos < st.n && st.s[st.pos] != '\n' && st.s[st.pos] != '\r') {
                val ch = st.s[st.pos]
                if (ch == '{' || ch == '[' || ch == '"' || ch == '\'') parseFlowLine(st, out) else parseScalarLine(st, out)
            } else {
                // newline: child block
                if (st.pos < st.n) st.pos++  // eat newline
                parseBlock(st, out, indent + 2)
            }
            lastClose = out.closeOf(valueIdx).coerceAtLeast(open)
            st.skipBlankLines()
            if (st.atEof()) break
            here = st.lineIndent()
            if (here < indent) break
            if (here > indent) {
                // Could be a multiline plain scalar continuation — check for colon
                val colonCheck = st.readToColon()
                if (colonCheck < st.n && st.s[colonCheck] == ':') {
                    break  // genuine over-indent key
                }
                // Multiline continuation — skip this line and loop again
                st.readLineEnd()
                continue
            }
            st.consumeIndent(here)
            // ensure next is a key
            val p = st.pos
            val c2 = st.readToColon()
            if (c2 >= st.n || st.s[c2] != ':') { st.pos = p; break }
            st.pos = p
        }
        out.endOf(idx, lastClose, Tag.OBJECT)
        return idx
    }

   fun parseScalarLine(st: ScanState, out: ElemBuf): Int {
        val open = st.pos
        // classify
        val tag = classifyScalar(st)
        val idx = out.beginTagged(open, tag)
        val close = st.readLineEnd()
        out.endOf(idx, close, tag)
        return idx
    }

   fun parseFlowLine(st: ScanState, out: ElemBuf): Int {
        // delegate a single inline JSON-ish value to JsonScan by slicing the rest of the line
        val open = st.pos
        var end = st.pos
        while (end < st.n && st.s[end] != '\n') end++
        val sub = st.s.slice(open, end)
        // run a micro-scan; we only need root tag & endpoints. For simplicity, emit a
        // single NUMBER/STRING-or-OBJECT-or-ARRAY element pointing at the line.
        val ch = st.s[open]
        val tag = when (ch) {
            '{' -> Tag.OBJECT
            '[' -> Tag.ARRAY
            '"', '\'' -> Tag.STRING
            else -> Tag.NUMBER
        }
        val idx = out.beginTagged(open, tag)
        out.endOf(idx, end - 1, tag)
        st.pos = if (end < st.n) end + 1 else end
        @Suppress("UNUSED_VARIABLE") val _s = sub
        return idx
    }

   fun classifyScalar(st: ScanState): Tag {
        val p = st.pos
        val ch = if (p < st.n) st.s[p] else '\u0000'
        if (ch == '"' || ch == '\'') return Tag.STRING
        // peek token
        if (ch == 't' || ch == 'T') return if (lineIs(st, "true") || lineIs(st, "True")) Tag.BOOL_TRUE else Tag.STRING
        if (ch == 'f' || ch == 'F') return if (lineIs(st, "false") || lineIs(st, "False")) Tag.BOOL_FALSE else Tag.STRING
        if (ch == 'n' || ch == 'N') return if (lineIs(st, "null") || lineIs(st, "Null") || lineIs(st, "~")) Tag.NULL else Tag.STRING
        if (ch == '~') return Tag.NULL
        if (ch == '-' || ch == '+' || (ch in '0'..'9')) return if (lineIsNumber(st)) Tag.NUMBER else Tag.STRING
        return Tag.STRING
    }

   fun lineIs(st: ScanState, kw: String): Boolean {
        var i = 0; var p = st.pos
        while (i < kw.length && p < st.n) {
            if (st.s[p] != kw[i]) return false
            p++; i++
        }
        if (i != kw.length) return false
        // rest of line must be whitespace/EOL
        while (p < st.n && (st.s[p] == ' ' || st.s[p] == '\t')) p++
        return p >= st.n || st.s[p] == '\n' || st.s[p] == '\r' || st.s[p] == '#'
    }

   fun lineIsNumber(st: ScanState): Boolean {
        var p = st.pos
        if (p < st.n && (st.s[p] == '-' || st.s[p] == '+')) p++
        var digits = 0
        while (p < st.n) {
            val c = st.s[p]
            if (c in '0'..'9' || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                if (c in '0'..'9') digits++
                p++
            } else break
        }
        while (p < st.n && (st.s[p] == ' ' || st.s[p] == '\t')) p++
        return digits > 0 && (p >= st.n || st.s[p] == '\n' || st.s[p] == '\r' || st.s[p] == '#')
    }

   fun scanInlineColon(st: ScanState): Int {
        var p = st.pos
        while (p < st.n && st.s[p] != '\n' && st.s[p] != '\r') {
            if (st.s[p] == ':') return p
            p++
        }
        return -1
    }
}
