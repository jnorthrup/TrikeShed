package borg.trikeshed.parse.confix

/* ═══════════════════════════════════════════════════════════════════════════════
 *
 *  JSON tokenizer → Series<JsElement>
 * ═══════════════════════════════════════════════════════════════════════════ */

 *  JSON tokenizer → Series<JsElement> over the input Series<Char>
 * ═══════════════════════════════════════════════════════════════════════════ */

object JsonScan {

    /** scan a char Series into Series<JsElement>. The root element is at index 0. */
    fun scan(src: Series<Char>): Series<JsElement> {
        val cs = CharSeries(src)
        val out = ElemBuf()
        cs.skipWs
        parseValue(cs, out)
        cs.skipWs
        return out.toSeries()
    }

    /** Parse exactly one JSON value starting at global position [startPos] in [src].
     *  Returns the JsElement with positions already in the global coordinate space
     *  (no offset adjustment needed). Used by [Combinators.reifyChildAt] to avoid full re-scan. */
    fun parseOne(src: Series<Char>, startPos: Int): JsElement {
        val cs = CharSeries(src)
        cs.pos(startPos)
        val out = ElemBuf(1)  // single-element buffer — no grow overhead
        parseValue(cs, out)
        return out.toSeries()[0]
    }

   fun parseValue(cs: CharSeries, out: ElemBuf): Int {
        cs.skipWs
        if (!cs.hasRemaining) error("eof in json")
        val c = cs[cs.pos]
        return when (c) {
            '{' -> parseObject(cs, out)
            '[' -> parseArray(cs, out)
            '"' -> parseString(cs, out)
            't', 'f' -> parseBool(cs, out)
            'n' -> parseNull(cs, out)
            '-', '+', in '0'..'9' -> parseNumber(cs, out)
            else -> error("unexpected char '${c}' in json at position ${cs.pos}")
        }
    }

   fun parseObject(cs: CharSeries, out: ElemBuf): Int {
        val open = cs.pos; cs.inc()  // consume '{'
        val idx = out.beginTagged(open, Tag.OBJECT)
        cs.skipWs
        if (cs.hasRemaining && cs[cs.pos] == '}') {
            val close = cs.pos; cs.inc()
            out.endOf(idx, close, Tag.OBJECT); return idx
        }
        while (cs.hasRemaining) {
            cs.skipWs
            // record comma position = start of key
            out.addComma(cs.pos)
            parseString(cs, out)         // key
            cs.skipWs
            if (!cs.hasRemaining || cs[cs.pos] != ':') error("expected ':' at ${cs.pos}")
            cs.inc()
            parseValue(cs, out)          // value
            cs.skipWs
            if (cs.hasRemaining && cs[cs.pos] == ',') { cs.inc(); continue }
            if (cs.hasRemaining && cs[cs.pos] == '}') {
                val close = cs.pos; cs.inc()
                out.endOf(idx, close, Tag.OBJECT); return idx
            }
            error("expected ',' or '}' at ${cs.pos}")
        }
        error("unterminated object")
    }

   fun parseArray(cs: CharSeries, out: ElemBuf): Int {
        val open = cs.pos; cs.inc()
        val idx = out.beginTagged(open, Tag.ARRAY)
        cs.skipWs
        if (cs.hasRemaining && cs[cs.pos] == ']') {
            val close = cs.pos; cs.inc()
            out.endOf(idx, close, Tag.ARRAY); return idx
        }
        while (cs.hasRemaining) {
            cs.skipWs
            out.addComma(cs.pos)   // start of each element
            parseValue(cs, out)
            cs.skipWs
            if (cs.hasRemaining && cs[cs.pos] == ',') { cs.inc(); continue }
            if (cs.hasRemaining && cs[cs.pos] == ']') {
                val close = cs.pos; cs.inc()
                out.endOf(idx, close, Tag.ARRAY); return idx
            }
            error("expected ',' or ']' at ${cs.pos}")
        }
        error("unterminated array")
    }

   fun parseString(cs: CharSeries, out: ElemBuf): Int {
        val open = cs.pos
        if (cs[cs.pos] != '"') error("expected '\"' at ${cs.pos}")
        val idx = out.beginTagged(open, Tag.STRING)
        cs.inc()  // skip opening quote
        // Use CharSeries.seekTo with escape-aware scanning
        if (!cs.seekTo('"', '\\')) error("unterminated string")
        // cs.pos now points past the closing quote
        val close = cs.pos - 1
        out.endOf(idx, close, Tag.STRING)
        return idx
    }

   fun parseBool(cs: CharSeries, out: ElemBuf): Int {
        val open = cs.pos
        return if (cs[cs.pos] == 't') {
            if (cs.pos + 3 >= cs.limit) error("bad bool")
            val idx = out.beginTagged(open, Tag.BOOL_TRUE)
            cs.pos(cs.pos + 4)
            out.endOf(idx, cs.pos - 1, Tag.BOOL_TRUE); idx
        } else {
            if (cs.pos + 4 >= cs.limit) error("bad bool")
            val idx = out.beginTagged(open, Tag.BOOL_FALSE)
            cs.pos(cs.pos + 5)
            out.endOf(idx, cs.pos - 1, Tag.BOOL_FALSE); idx
        }
    }

   fun parseNull(cs: CharSeries, out: ElemBuf): Int {
        val open = cs.pos
        if (cs.pos + 3 >= cs.limit) error("bad null")
        // validate exact token 'null'
        if (cs[cs.pos] != 'n' || cs[cs.pos + 1] != 'u' || cs[cs.pos + 2] != 'l' || cs[cs.pos + 3] != 'l') error("bad null")
        val idx = out.beginTagged(open, Tag.NULL)
        cs.pos(cs.pos + 4)
        out.endOf(idx, cs.pos - 1, Tag.NULL)
        return idx
    }

   fun parseNumber(cs: CharSeries, out: ElemBuf): Int {
        val open = cs.pos
        val idx = out.beginTagged(open, Tag.NUMBER)
        val c = cs[cs.pos]
        if (c == '-' || c == '+') cs.inc()
        while (cs.hasRemaining) {
            val ch = cs[cs.pos]
            val num = (ch in '0'..'9') || ch == '.' || ch == 'e' || ch == 'E' || ch == '+' || ch == '-'
            if (!num) break
            cs.inc()
        }
        out.endOf(idx, cs.pos - 1, Tag.NUMBER)
        return idx
    }
}
