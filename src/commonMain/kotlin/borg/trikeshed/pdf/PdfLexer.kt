package borg.trikeshed.pdf

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size

/**
 * Byte-level COS parser. Whitespace and delimiter classes straight from the
 * spec (ISO 32000 §7.2); literal strings honor nested parens, all escapes and
 * octal; names honor `#xx`; numbers are signed reals. `N G R` references are
 * recognized by two-token lookahead. Tolerant by policy: garbage yields nulls,
 * never throws — a torn PDF still disassembles as far as its bytes allow.
 */
class PdfLexer(val src: Series<Byte>, var pos: Int = 0) {
    constructor(bytes: ByteArray, pos: Int = 0) : this(borg.trikeshed.lib.ByteSeries(bytes), pos)
    val end: Int get() = src.size

    /** Materialize a SMALL region (tokens only) as a string. */
    fun str(from: Int, to: Int): String {
        val n = to - from
        val ba = ByteArray(n) { src[from + it] }
        return ba.decodeToString()
    }

    fun ws(b: Int) = b == 0 || b == 9 || b == 10 || b == 12 || b == 13 || b == 32
    fun delim(b: Int) = b == '('.code || b == ')'.code || b == '<'.code || b == '>'.code ||
        b == '['.code || b == ']'.code || b == '{'.code || b == '}'.code || b == '/'.code || b == '%'.code

    fun peek(): Int = if (pos < end) src[pos].toInt() and 0xFF else -1
    fun at(i: Int): Int = if (i < end) src[i].toInt() and 0xFF else -1

    fun skipWs() {
        while (pos < end) {
            val b = peek()
            if (ws(b)) { pos++; continue }
            if (b == '%'.code) { // comment to EOL
                while (pos < end && peek() != 10 && peek() != 13) pos++
                continue
            }
            break
        }
    }

    /** Bare token (keyword/operator/number text) — stops on ws or delimiter. */
    fun bareToken(): String {
        skipWs()
        val start = pos
        while (pos < end && !ws(peek()) && !delim(peek())) pos++
        return str(start, pos)
    }

    fun startsWith(s: String, from: Int = pos): Boolean {
        if (from + s.length > end) return false
        for (i in s.indices) if (src[from + i].toInt() != s[i].code) return false
        return true
    }

    /** One object at pos, or null. References resolved as [PdfObject.PRef]. */
    fun parseObject(depth: Int = 0): PdfObject? {
        if (depth > 64) return null
        skipWs()
        val b = peek()
        return when {
            b == -1 -> null
            b == '<'.code && at(pos + 1) == '<'.code -> parseDict(depth)
            b == '<'.code -> parseHexString()
            b == '('.code -> parseLiteralString()
            b == '['.code -> parseArray(depth)
            b == '/'.code -> parseName()
            b == '+'.code || b == '-'.code || b == '.'.code || (b in '0'.code..'9'.code) -> parseNumberOrRef()
            else -> when (val t = bareToken()) {
                "true" -> PdfObject.PBool(true)
                "false" -> PdfObject.PBool(false)
                "null" -> PdfObject.PNull
                "" -> null.also { pos++ }           // stray delimiter: never stall
                else -> PdfObject.PName(t)          // bare keyword (content-stream operator)
            }
        }
    }

    fun parseName(): PdfObject.PName {
        pos++ // '/'
        val sb = StringBuilder()
        while (pos < end) {
            val c = peek()
            if (ws(c) || delim(c)) break
            if (c == '#'.code && pos + 2 < end) {
                val h = ((hexVal(at(pos + 1)) shl 4) or hexVal(at(pos + 2)))
                if (h >= 0) { sb.append(h.toChar()); pos += 3; continue }
            }
            sb.append(c.toChar()); pos++
        }
        return PdfObject.PName(sb.toString())
    }

    private fun hexVal(c: Int): Int = when (c) {
        in '0'.code..'9'.code -> c - '0'.code
        in 'a'.code..'f'.code -> c - 'a'.code + 10
        in 'A'.code..'F'.code -> c - 'A'.code + 10
        else -> -1
    }

    fun parseHexString(): PdfObject.PStr {
        pos++ // '<'
        val out = ArrayList<Byte>()
        var hi = -1
        while (pos < end) {
            val c = peek(); pos++
            if (c == '>'.code) break
            val v = hexVal(c)
            if (v < 0) continue
            if (hi < 0) hi = v else { out.add(((hi shl 4) or v).toByte()); hi = -1 }
        }
        if (hi >= 0) out.add((hi shl 4).toByte()) // odd count: final digit, low nibble 0
        return PdfObject.PStr(out.toByteArray())
    }

    fun parseLiteralString(): PdfObject.PStr {
        pos++ // '('
        val out = ArrayList<Byte>()
        var nest = 1
        while (pos < end) {
            val c = peek(); pos++
            when (c) {
                '\\'.code -> {
                    val e = peek(); pos++
                    when (e) {
                        'n'.code -> out.add(10); 'r'.code -> out.add(13); 't'.code -> out.add(9)
                        'b'.code -> out.add(8); 'f'.code -> out.add(12)
                        '('.code -> out.add(40); ')'.code -> out.add(41); '\\'.code -> out.add(92)
                        13 -> { if (peek() == 10) pos++ }   // line continuation \CRLF
                        10 -> {}                             // line continuation \LF
                        in '0'.code..'7'.code -> {           // up to 3 octal digits
                            var v = e - '0'.code
                            var n = 1
                            while (n < 3 && peek() in '0'.code..'7'.code) { v = v * 8 + (peek() - '0'.code); pos++; n++ }
                            out.add((v and 0xFF).toByte())
                        }
                        else -> if (e >= 0) out.add(e.toByte()) // unknown escape: the char itself
                    }
                }
                '('.code -> { nest++; out.add(40) }
                ')'.code -> { nest--; if (nest == 0) break; out.add(41) }
                else -> out.add(c.toByte())
            }
        }
        return PdfObject.PStr(out.toByteArray())
    }

    fun parseArray(depth: Int): PdfObject.PArr {
        pos++ // '['
        val items = ArrayList<PdfObject>()
        while (pos < end) {
            skipWs()
            if (peek() == ']'.code) { pos++; break }
            val o = parseObject(depth + 1) ?: break
            items.add(o)
        }
        return PdfObject.PArr(items)
    }

    fun parseDict(depth: Int): PdfObject.PDict {
        pos += 2 // '<<'
        val map = LinkedHashMap<String, PdfObject>()
        while (pos < end) {
            skipWs()
            if (peek() == '>'.code && at(pos + 1) == '>'.code) { pos += 2; break }
            if (peek() != '/'.code) { pos++; continue } // tolerate junk keys
            val key = parseName().value
            val value = parseObject(depth + 1) ?: break
            map[key] = value
        }
        return PdfObject.PDict(map)
    }

    /** number, or `N G R` reference via bounded lookahead. */
    fun parseNumberOrRef(): PdfObject {
        val start = pos
        val t = bareToken()
        val n = t.toDoubleOrNull() ?: return PdfObject.PNum(0.0)
        val isInt = t.toIntOrNull() != null && t.toIntOrNull()!! >= 0
        if (isInt) {
            val save = pos
            skipWs()
            val g = bareToken()
            if (g.toIntOrNull() != null) {
                skipWs()
                val save2 = pos
                val r = bareToken()
                if (r == "R") return PdfObject.PRef(t.toInt(), g.toInt())
                pos = save2
            }
            pos = save
        }
        // keep pos after the number token only
        pos = start + t.length + run { var i = start; var w = 0; while (i < end && ws(at(i))) { i++; w++ }; w }
        return PdfObject.PNum(n)
    }
}
