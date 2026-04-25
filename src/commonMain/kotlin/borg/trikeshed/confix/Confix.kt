@file:Suppress(
    "NonAsciiCharacters",
    "FunctionName",
    "ObjectPropertyName",
    "PropertyName",
    "LocalVariableName",
    "unused",
    "MemberVisibilityCanBePrivate",
)

package borg.trikeshed.confix

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/* ═══════════════════════════════════════════════════════════════════════════
 *  Confix — one algebra, three syntaxes (JSON, CBOR, YAML).
 *
 *  Zero stdlib collections. Only primitive arrays (IntArray/CharArray/ByteArray)
 *  and Join/Series composition from the kernel algebra. Everything reduces to:
 *
 *     JsElement  = Join<Twin<Int>, Series<Int>>     // (open j close) j commas
 *     JsIndex    = Join<Twin<Int>, Series<Char>>    // (twin      j src)
 *     JsContext  = Join<JsElement, Series<Char>>    // element    j src
 *     JsPath     = Series<Either<String,Int>>
 *
 *  One tokenizer per syntax emits Series<JsElement> over a shared Series<Char>.
 *  One reifier walks JsContext. One path-scanner walks JsPath over JsContext.
 *  One CCEK SupervisoryJob orchestrates all three with explicit lifecycle and
 *  structured fanout to subscribers.
 * ═══════════════════════════════════════════════════════════════════════════ */


/* ─── kernel algebra (self-contained, no stdlib collections) ────────────── */

interface Join<A, B> {
    val a: A
    val b: B
    operator fun component1(): A = a
    operator fun component2(): B = b
}

private class JoinImpl<A, B>(override val a: A, override val b: B) : Join<A, B>

@Suppress("FunctionName")
fun <A, B> Join(a: A, b: B): Join<A, B> = JoinImpl(a, b)

inline infix fun <A, B> A.j(b: B): Join<A, B> = Join(this, b)

typealias Twin<T> = Join<T, T>
typealias Series<T> = Join<Int, (Int) -> T>
typealias Series2<A, B> = Series<Join<A, B>>

val <T> Series<T>.size: Int get() = a
operator fun <T> Series<T>.get(i: Int): T = b(i)

inline infix fun <X, C> Series<X>.α(crossinline f: (X) -> C): Series<C> =
    size j { i -> f(this[i]) }

/** left identity anchor */
val <T> T.`↺`: () -> T get() = { this }

/** Either: sum type without stdlib */
sealed interface Either<out L, out R> {
    class Left<L>(val value: L) : Either<L, Nothing>
    class Right<R>(val value: R) : Either<Nothing, R>
}

/** empty Series — size 0, indexer is an unreachable guard */
fun <T> emptySeries(): Series<T> = 0 j { _ -> error("empty") }

/** Series from IntArray */
fun IntArray.asSeries(): Series<Int> = size j { i -> this[i] }

/** Series from CharArray */
fun CharArray.asSeries(): Series<Char> = size j { i -> this[i] }

/** Series from ByteArray */
fun ByteArray.asSeries(): Series<Byte> = size j { i -> this[i] }

/** Series over a slice of a Series */
fun <T> Series<T>.slice(from: Int, untilX: Int): Series<T> {
    val s = this
    val n = untilX - from
    return n j { i -> s[from + i] }
}

/** Series of Chars from a CharSequence (no stdlib collection materialization) */
fun CharSequence.asSeries(): Series<Char> = length j { i -> this[i] }


/* ─── JSON/CBOR/YAML shared model (exactly the preload aliases) ─────────── */

/** (openIdx j closeIdx) j commaIdxs — one value in the src */
typealias JsElement = Join<Twin<Int>, Series<Int>>

/** (openIdx j closeIdx) j src — an addressed span inside a char stream */
typealias JsIndex = Join<Twin<Int>, Series<Char>>

/** element j src — a value carrying its backing stream */
typealias JsContext = Join<JsElement, Series<Char>>

/** path element: "name" or index */
typealias JsPathElement = Either<String, Int>

/** series of path elements */
typealias JsPath = Series<JsPathElement>


/** tag byte encoded into commas[0] when the producer wants to signal kind.
 *  Uses negative sentinels so positive comma positions remain unambiguous.
 *  The tag channel is optional — when absent, reifier infers from src[open]. */
object Tag {
    const val OBJECT: Int = -1
    const val ARRAY: Int = -2
    const val STRING: Int = -3
    const val NUMBER: Int = -4
    const val BOOL_TRUE: Int = -5
    const val BOOL_FALSE: Int = -6
    const val NULL: Int = -7
    const val BYTES: Int = -8          // CBOR byte string; reifier decodes hex-escaped chars
}


/* ─── tiny growable IntArray (no kotlin.collections) ────────────────────── */

class IntBuf(initial: Int = 16) {
    var data: IntArray = IntArray(initial)
        private set
    var size: Int = 0
        private set

    fun add(v: Int) {
        if (size == data.size) {
            val n = IntArray(data.size * 2)
            var i = 0
            while (i < size) { n[i] = data[i]; i++ }
            data = n
        }
        data[size++] = v
    }

    fun toSeries(): Series<Int> {
        val n = size
        val d = data
        return n j { i -> d[i] }
    }

    fun snapshot(): IntArray {
        val out = IntArray(size)
        var i = 0; while (i < size) { out[i] = data[i]; i++ }
        return out
    }
}

class ElemBuf(initial: Int = 16) {
    private var opens: IntArray = IntArray(initial)
    private var closes: IntArray = IntArray(initial)
    private var commaHeads: IntArray = IntArray(initial) // start offset into commas pool
    private var commaTails: IntArray = IntArray(initial) // end offset (exclusive)
    var commas: IntBuf = IntBuf(initial * 2)
        private set
    var size: Int = 0
        private set

    private fun grow() {
        val n = opens.size * 2
        opens = opens.copyGrow(n); closes = closes.copyGrow(n)
        commaHeads = commaHeads.copyGrow(n); commaTails = commaTails.copyGrow(n)
    }

    private fun IntArray.copyGrow(n: Int): IntArray {
        val x = IntArray(n); var i = 0; while (i < size) { x[i] = this[i]; i++ }; return x
    }

    /** begin a new element; returns its index. Caller fills commas via [addComma] then calls [endOf]. */
    fun begin(openIdx: Int): Int {
        if (size == opens.size) grow()
        opens[size] = openIdx
        commaHeads[size] = commas.size
        return size
    }

    fun addComma(pos: Int) { commas.add(pos) }

    fun endOf(elemIdx: Int, closeIdx: Int, tagOrZero: Int) {
        // Tag is stored as the first "comma" when non-zero; real commas follow.
        // But since Tag constants are negative and commas are positive, we prepend
        // by splicing: we can only append chronologically, so we record tag BEFORE begin().
        // Callers should call tagBefore() prior to begin() for correctness. See helpers.
        closes[elemIdx] = closeIdx
        commaTails[elemIdx] = commas.size
        size = elemIdx + 1
        @Suppress("UNUSED_PARAMETER") val _t = tagOrZero
    }

    /** Prefer: call [beginTagged] which writes the tag into commas[head] first. */
    fun beginTagged(openIdx: Int, tag: Int): Int {
        val i = begin(openIdx)
        commas.add(tag)
        return i
    }

    fun toSeries(): Series<JsElement> {
        val n = size
        val o = opens; val c = closes; val h = commaHeads; val t = commaTails
        val pool = commas.data
        val poolSize = commas.size
        return n j { i ->
            val head = h[i]; val tail = t[i]
            val len = tail - head
            val commaSeries: Series<Int> = len j { k -> pool[head + k] }
            (o[i] j c[i]) j commaSeries
        }.also { _ -> @Suppress("UNUSED_VARIABLE") val _p = poolSize }
    }
}


/* ═══════════════════════════════════════════════════════════════════════════
 *  JSON tokenizer → Series<JsElement> over the input Series<Char>
 * ═══════════════════════════════════════════════════════════════════════════ */

object JsonScan {

    /** scan a char Series into Series<JsElement>. The root element is at index 0. */
    fun scan(src: Series<Char>): Series<JsElement> {
        val n = src.size
        val out = ElemBuf()
        val i = IntHolder(0)
        skipWs(src, i, n)
        parseValue(src, i, n, out)
        skipWs(src, i, n)
        return out.toSeries()
    }

    private class IntHolder(var v: Int)

    private fun skipWs(s: Series<Char>, h: IntHolder, n: Int) {
        while (h.v < n) {
            val c = s[h.v]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') h.v++ else return
        }
    }

    private fun parseValue(s: Series<Char>, h: IntHolder, n: Int, out: ElemBuf): Int {
        skipWs(s, h, n)
        if (h.v >= n) error("eof in json")
        val c = s[h.v]
        return when {
            c == '{' -> parseObject(s, h, n, out)
            c == '[' -> parseArray(s, h, n, out)
            c == '"' -> parseString(s, h, n, out)
            c == 't' || c == 'f' -> parseBool(s, h, n, out)
            c == 'n' -> parseNull(s, h, n, out)
            else -> parseNumber(s, h, n, out)
        }
    }

    private fun parseObject(s: Series<Char>, h: IntHolder, n: Int, out: ElemBuf): Int {
        val open = h.v; h.v++  // consume '{'
        val idx = out.beginTagged(open, Tag.OBJECT)
        skipWs(s, h, n)
        if (h.v < n && s[h.v] == '}') {
            val close = h.v; h.v++
            out.endOf(idx, close, Tag.OBJECT); return idx
        }
        while (h.v < n) {
            skipWs(s, h, n)
            // record comma position = start of key
            out.addComma(h.v)
            parseString(s, h, n, out)        // key
            skipWs(s, h, n)
            if (h.v >= n || s[h.v] != ':') error("expected ':' at ${h.v}")
            h.v++
            parseValue(s, h, n, out)         // value
            skipWs(s, h, n)
            if (h.v < n && s[h.v] == ',') { h.v++; continue }
            if (h.v < n && s[h.v] == '}') {
                val close = h.v; h.v++
                out.endOf(idx, close, Tag.OBJECT); return idx
            }
            error("expected ',' or '}' at ${h.v}")
        }
        error("unterminated object")
    }

    private fun parseArray(s: Series<Char>, h: IntHolder, n: Int, out: ElemBuf): Int {
        val open = h.v; h.v++
        val idx = out.beginTagged(open, Tag.ARRAY)
        skipWs(s, h, n)
        if (h.v < n && s[h.v] == ']') {
            val close = h.v; h.v++
            out.endOf(idx, close, Tag.ARRAY); return idx
        }
        while (h.v < n) {
            skipWs(s, h, n)
            out.addComma(h.v)   // start of each element
            parseValue(s, h, n, out)
            skipWs(s, h, n)
            if (h.v < n && s[h.v] == ',') { h.v++; continue }
            if (h.v < n && s[h.v] == ']') {
                val close = h.v; h.v++
                out.endOf(idx, close, Tag.ARRAY); return idx
            }
            error("expected ',' or ']' at ${h.v}")
        }
        error("unterminated array")
    }

    private fun parseString(s: Series<Char>, h: IntHolder, n: Int, out: ElemBuf): Int {
        val open = h.v
        if (s[h.v] != '"') error("expected '\"' at ${h.v}")
        val idx = out.beginTagged(open, Tag.STRING)
        h.v++
        while (h.v < n) {
            val c = s[h.v]
            if (c == '\\') { h.v += 2; continue }
            if (c == '"') {
                val close = h.v; h.v++
                out.endOf(idx, close, Tag.STRING); return idx
            }
            h.v++
        }
        error("unterminated string")
    }

    private fun parseBool(s: Series<Char>, h: IntHolder, n: Int, out: ElemBuf): Int {
        val open = h.v
        return if (s[h.v] == 't') {
            if (h.v + 3 >= n) error("bad bool")
            val idx = out.beginTagged(open, Tag.BOOL_TRUE)
            h.v += 4
            out.endOf(idx, h.v - 1, Tag.BOOL_TRUE); idx
        } else {
            if (h.v + 4 >= n) error("bad bool")
            val idx = out.beginTagged(open, Tag.BOOL_FALSE)
            h.v += 5
            out.endOf(idx, h.v - 1, Tag.BOOL_FALSE); idx
        }
    }

    private fun parseNull(s: Series<Char>, h: IntHolder, n: Int, out: ElemBuf): Int {
        val open = h.v
        if (h.v + 3 >= n) error("bad null")
        val idx = out.beginTagged(open, Tag.NULL)
        h.v += 4
        out.endOf(idx, h.v - 1, Tag.NULL)
        @Suppress("UNUSED_VARIABLE") val _s = s
        return idx
    }

    private fun parseNumber(s: Series<Char>, h: IntHolder, n: Int, out: ElemBuf): Int {
        val open = h.v
        val idx = out.beginTagged(open, Tag.NUMBER)
        if (s[h.v] == '-' || s[h.v] == '+') h.v++
        while (h.v < n) {
            val c = s[h.v]
            val num = (c in '0'..'9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-'
            if (!num) break
            h.v++
        }
        out.endOf(idx, h.v - 1, Tag.NUMBER)
        return idx
    }
}


/* ═══════════════════════════════════════════════════════════════════════════
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

    private class ScanState(val s: Series<Char>, val n: Int) {
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

    private fun parseBlock(st: ScanState, out: ElemBuf, indent: Int): Int {
        st.skipBlankLines()
        val here = st.lineIndent()
        if (st.atEof() || here < indent) {
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

    private fun parseSeq(st: ScanState, out: ElemBuf, indent: Int): Int {
        val open = st.pos
        val idx = out.beginTagged(open, Tag.ARRAY)
        var lastClose = open
        while (!st.atEof()) {
            st.skipBlankLines()
            val here = st.lineIndent()
            if (here < indent) break
            st.consumeIndent(here)
            if (st.peek() != '-') break
            st.pos++  // consume '-'
            if (st.peek() == ' ') st.pos++
            out.addComma(st.pos)
            // child value: either a nested block (on next lines) or a scalar/flow
            val childIndent = indent + 2
            val ch = st.peek()
            if (ch == '{' || ch == '[' || ch == '"' || ch == '\'') {
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
            lastClose = st.pos.coerceAtLeast(open)
        }
        out.endOf(idx, lastClose, Tag.ARRAY)
        return idx
    }

    private fun parseMapOrScalar(st: ScanState, out: ElemBuf, indent: Int): Int {
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
            // already positioned at key start
            out.addComma(st.pos)
            // key (string-like): emit a STRING element spanning until ':'
            val keyOpen = st.pos
            val keyColon = st.readToColon()
            val keyClose = if (keyColon > keyOpen) keyColon - 1 else keyOpen
            val keyIdx = out.beginTagged(keyOpen, Tag.STRING)
            out.endOf(keyIdx, keyClose, Tag.STRING)
            st.pos = keyColon + 1  // consume ':'
            while (st.pos < st.n && st.s[st.pos] == ' ') st.pos++
            // value: inline or next-line block
            if (st.pos < st.n && st.s[st.pos] != '\n' && st.s[st.pos] != '\r') {
                val ch = st.s[st.pos]
                if (ch == '{' || ch == '[' || ch == '"' || ch == '\'') parseFlowLine(st, out)
                else parseScalarLine(st, out)
            } else {
                // newline: child block
                if (st.pos < st.n) st.pos++  // eat newline
                parseBlock(st, out, indent + 2)
            }
            lastClose = (st.pos - 1).coerceAtLeast(open)
            st.skipBlankLines()
            if (st.atEof()) break
            here = st.lineIndent()
            if (here < indent) break
            if (here > indent) break  // stray over-indent; stop map here
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

    private fun parseScalarLine(st: ScanState, out: ElemBuf): Int {
        val open = st.pos
        // classify
        val tag = classifyScalar(st)
        val idx = out.beginTagged(open, tag)
        val close = st.readLineEnd()
        out.endOf(idx, close, tag)
        return idx
    }

    private fun parseFlowLine(st: ScanState, out: ElemBuf): Int {
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

    private fun classifyScalar(st: ScanState): Int {
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

    private fun lineIs(st: ScanState, kw: String): Boolean {
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

    private fun lineIsNumber(st: ScanState): Boolean {
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

    private fun scanInlineColon(st: ScanState): Int {
        var p = st.pos
        while (p < st.n && st.s[p] != '\n' && st.s[p] != '\r') {
            if (st.s[p] == ':') return p
            p++
        }
        return -1
    }
}


/* ═══════════════════════════════════════════════════════════════════════════
 *  CBOR tokenizer → Series<JsElement> over a Series<Char>
 *
 *  CBOR is binary; to stay in the single JsContext model, we require the
 *  CBOR bytes to be projected into a Series<Char> by widening (byte.toInt()
 *  .toChar()). The tokenizer walks major types and emits (open j close) j
 *  commas exactly like JSON. Close index is inclusive of the last content
 *  byte of the item.
 *
 *  Major types (RFC 8949):
 *   0: unsigned int       1: negative int     2: byte string
 *   3: text string        4: array            5: map
 *   6: tag (skipped)      7: simple/float (true/false/null/break/float)
 * ═══════════════════════════════════════════════════════════════════════════ */

object CborScan {

    fun scan(src: Series<Char>): Series<JsElement> {
        val n = src.size
        val out = ElemBuf()
        val h = IntHolder(0)
        parseItem(src, h, n, out)
        return out.toSeries()
    }

    private class IntHolder(var v: Int)

    private fun u8(s: Series<Char>, i: Int): Int = s[i].code and 0xFF

    private fun readLen(s: Series<Char>, h: IntHolder, ai: Int): Long {
        return when (ai) {
            in 0..23 -> ai.toLong()
            24 -> { val v = u8(s, h.v).toLong(); h.v += 1; v }
            25 -> { val v = ((u8(s, h.v) shl 8) or u8(s, h.v + 1)).toLong(); h.v += 2; v }
            26 -> {
                val v = ((u8(s, h.v).toLong() shl 24) or
                         (u8(s, h.v + 1).toLong() shl 16) or
                         (u8(s, h.v + 2).toLong() shl 8) or
                         u8(s, h.v + 3).toLong())
                h.v += 4; v
            }
            27 -> {
                var v = 0L
                var k = 0
                while (k < 8) { v = (v shl 8) or u8(s, h.v + k).toLong(); k++ }
                h.v += 8; v
            }
            31 -> -1L  // indefinite
            else -> error("bad cbor additional info $ai")
        }
    }

    private fun parseItem(s: Series<Char>, h: IntHolder, n: Int, out: ElemBuf): Int {
        val open = h.v
        if (h.v >= n) error("cbor eof")
        val ib = u8(s, h.v); h.v++
        val mt = ib ushr 5
        val ai = ib and 0x1F
        return when (mt) {
            0 -> {                                   // unsigned int
                val idx = out.beginTagged(open, Tag.NUMBER)
                readLen(s, h, ai)
                out.endOf(idx, h.v - 1, Tag.NUMBER); idx
            }
            1 -> {                                   // negative int
                val idx = out.beginTagged(open, Tag.NUMBER)
                readLen(s, h, ai)
                out.endOf(idx, h.v - 1, Tag.NUMBER); idx
            }
            2 -> {                                   // byte string
                val idx = out.beginTagged(open, Tag.BYTES)
                val len = readLen(s, h, ai)
                if (len < 0L) parseIndefinite(s, h, n, out, Tag.BYTES)
                else h.v += len.toInt()
                out.endOf(idx, h.v - 1, Tag.BYTES); idx
            }
            3 -> {                                   // text string
                val idx = out.beginTagged(open, Tag.STRING)
                val len = readLen(s, h, ai)
                if (len < 0L) parseIndefinite(s, h, n, out, Tag.STRING)
                else h.v += len.toInt()
                out.endOf(idx, h.v - 1, Tag.STRING); idx
            }
            4 -> {                                   // array
                val idx = out.beginTagged(open, Tag.ARRAY)
                val len = readLen(s, h, ai)
                if (len < 0L) {
                    while (h.v < n && u8(s, h.v) != 0xFF) {
                        out.addComma(h.v); parseItem(s, h, n, out)
                    }
                    if (h.v < n) h.v++
                } else {
                    var k = 0L
                    while (k < len) { out.addComma(h.v); parseItem(s, h, n, out); k++ }
                }
                out.endOf(idx, h.v - 1, Tag.ARRAY); idx
            }
            5 -> {                                   // map
                val idx = out.beginTagged(open, Tag.OBJECT)
                val len = readLen(s, h, ai)
                if (len < 0L) {
                    while (h.v < n && u8(s, h.v) != 0xFF) {
                        out.addComma(h.v); parseItem(s, h, n, out); parseItem(s, h, n, out)
                    }
                    if (h.v < n) h.v++
                } else {
                    var k = 0L
                    while (k < len) {
                        out.addComma(h.v); parseItem(s, h, n, out); parseItem(s, h, n, out); k++
                    }
                }
                out.endOf(idx, h.v - 1, Tag.OBJECT); idx
            }
            6 -> {                                   // tag: consume tag value, then inner item
                readLen(s, h, ai)
                parseItem(s, h, n, out)
            }
            7 -> {                                   // simple / float
                val tag = when (ai) {
                    20 -> Tag.BOOL_FALSE
                    21 -> Tag.BOOL_TRUE
                    22, 23 -> Tag.NULL
                    25 -> Tag.NUMBER
                    26 -> Tag.NUMBER
                    27 -> Tag.NUMBER
                    else -> Tag.NULL
                }
                val idx = out.beginTagged(open, tag)
                when (ai) {
                    25 -> h.v += 2
                    26 -> h.v += 4
                    27 -> h.v += 8
                    24 -> h.v += 1
                    else -> { /* simple value, no payload */ }
                }
                out.endOf(idx, h.v - 1, tag); idx
            }
            else -> error("cbor major type $mt")
        }
    }

    private fun parseIndefinite(s: Series<Char>, h: IntHolder, n: Int, out: ElemBuf, tag: Int) {
        while (h.v < n && u8(s, h.v) != 0xFF) {
            val ib = u8(s, h.v); h.v++
            val ai = ib and 0x1F
            val len = readLen(s, h, ai)
            if (len >= 0) h.v += len.toInt()
        }
        if (h.v < n) h.v++
        @Suppress("UNUSED_PARAMETER") val t = tag
        @Suppress("UNUSED_PARAMETER") val o = out
    }
}


/* ═══════════════════════════════════════════════════════════════════════════
 *  Reifier — JsContext → logical value, uniformly over JSON/CBOR/YAML.
 *
 *  Returns Any? only because the algebra is dynamic. No stdlib collections:
 *  arrays become Series<Any?>, objects become Series2<String,Any?>.
 * ═══════════════════════════════════════════════════════════════════════════ */

object Reify {

    /** Returns the element's tag (inferred from commas[0] if negative, else from src[open]). */
    fun tagOf(e: JsElement, src: Series<Char>): Int {
        val commas = e.b
        if (commas.size > 0) {
            val c0 = commas[0]
            if (c0 < 0) return c0
        }
        val open = e.a.a
        if (open >= src.size) return Tag.NULL
        return when (src[open]) {
            '{' -> Tag.OBJECT
            '[' -> Tag.ARRAY
            '"' -> Tag.STRING
            't' -> Tag.BOOL_TRUE
            'f' -> Tag.BOOL_FALSE
            'n' -> Tag.NULL
            else -> Tag.NUMBER
        }
    }

    /** commas series with the leading tag sentinel stripped, if present. */
    fun realCommas(e: JsElement): Series<Int> {
        val c = e.b
        return if (c.size > 0 && c[0] < 0) c.slice(1, c.size) else c
    }

    /** slice of src between open..close inclusive (exclusive of delimiters if applicable) */
    fun spanOf(e: JsElement, src: Series<Char>): Series<Char> =
        src.slice(e.a.a, e.a.b + 1)

    /** materialize a text span into a String. Only place we allocate a String. */
    fun textOf(e: JsElement, src: Series<Char>): String {
        val a = e.a.a; val b = e.a.b
        val ca = CharArray(b - a + 1)
        var i = 0
        while (i < ca.size) { ca[i] = src[a + i]; i++ }
        return ca.concatToString()
    }

    /** reify the value rooted at [ctx] */
    fun reify(ctx: JsContext): Any? {
        val e = ctx.a; val src = ctx.b
        return when (tagOf(e, src)) {
            Tag.OBJECT -> reifyObject(e, src)
            Tag.ARRAY -> reifyArray(e, src)
            Tag.STRING, Tag.BYTES -> reifyString(e, src)
            Tag.NUMBER -> reifyNumber(e, src)
            Tag.BOOL_TRUE -> true
            Tag.BOOL_FALSE -> false
            Tag.NULL -> null
            else -> null
        }
    }

    private fun reifyString(e: JsElement, src: Series<Char>): String {
        // trim quotes for JSON/YAML string, keep raw for CBOR tokens
        val a = e.a.a; val b = e.a.b
        val q = if (a <= b && (src[a] == '"' || src[a] == '\'')) 1 else 0
        val start = a + q
        val endX = b - q + 1
        if (endX <= start) return ""
        val ca = CharArray(endX - start)
        var i = 0
        while (i < ca.size) { ca[i] = src[start + i]; i++ }
        return ca.concatToString()
    }

    private fun reifyNumber(e: JsElement, src: Series<Char>): Double {
        val t = textOf(e, src)
        // kotlin common exposes String.toDouble
        return t.trim().toDouble()
    }

    /** child JsElement at commas[k] — for arrays, each comma is an element open */
    private fun reifyArray(e: JsElement, src: Series<Char>): Series<Any?> {
        // We don't have the enclosing ElemBuf anymore — so we mini-scan children on
        // demand using the JSON-flavored rules. For objects/arrays this requires
        // matching confix, which reuses JsonScan at the child span.
        val cs = realCommas(e)
        val n = cs.size
        return n j { i ->
            val childOpen = cs[i]
            // build a minimal sub-scan starting at childOpen to recover a JsElement.
            val sub = src.slice(childOpen, src.size)
            val singleton = JsonScan.scan(sub)
            val cj = singleton[0]
            val adj = (cj.a.a + childOpen) j (cj.a.b + childOpen)
            val ccommas = cj.b
            val adjustedCommas: Series<Int> = ccommas.size j { k ->
                val v = ccommas[k]
                if (v < 0) v else v + childOpen
            }
            val child: JsElement = adj j adjustedCommas
            reify(child j src)
        }
    }

    /** object has commas in key-position pairs; keys sit at comma[k], values follow */
    private fun reifyObject(e: JsElement, src: Series<Char>): Series2<String, Any?> {
        val cs = realCommas(e)
        val n = cs.size
        return n j { i ->
            val keyOpen = cs[i]
            // reuse JsonScan starting at keyOpen to re-discover key element + ':' + value element
            val sub = src.slice(keyOpen, src.size)
            // parse key
            val keyScanned = JsonScan.scan(sub)
            val k0 = keyScanned[0]
            val keyAdj = (k0.a.a + keyOpen) j (k0.a.b + keyOpen)
            val keyCommas: Series<Int> = k0.b.size j { k -> val v = k0.b[k]; if (v < 0) v else v + keyOpen }
            val keyElem: JsElement = keyAdj j keyCommas
            val key = reifyString(keyElem, src)
            // find ':' after keyElem
            var p = keyAdj.b + 1
            while (p < src.size && (src[p] == ' ' || src[p] == ':' || src[p] == '\t')) p++
            val sub2 = src.slice(p, src.size)
            val valScanned = JsonScan.scan(sub2)
            val v0 = valScanned[0]
            val valAdj = (v0.a.a + p) j (v0.a.b + p)
            val valCommas: Series<Int> = v0.b.size j { k -> val v = v0.b[k]; if (v < 0) v else v + p }
            val valElem: JsElement = valAdj j valCommas
            val value = reify(valElem j src)
            key j value
        }
    }
}


/* ═══════════════════════════════════════════════════════════════════════════
 *  Path scanning — JsPath over JsContext
 *
 *  A JsPath is a Series<Either<String,Int>>. Walking it descends into the
 *  JsContext one segment at a time. Returns the resolved JsContext or null.
 * ═══════════════════════════════════════════════════════════════════════════ */

object Path {

    fun resolve(ctx: JsContext, path: JsPath): JsContext? {
        var cur: JsContext? = ctx
        var i = 0
        while (i < path.size && cur != null) {
            val seg = path[i]
            cur = step(cur, seg)
            i++
        }
        return cur
    }

    private fun step(ctx: JsContext, seg: JsPathElement): JsContext? {
        val e = ctx.a; val src = ctx.b
        return when (val s = seg) {
            is Either.Left -> stepByName(e, src, s.value)
            is Either.Right -> stepByIndex(e, src, s.value)
        }
    }

    private fun stepByIndex(e: JsElement, src: Series<Char>, idx: Int): JsContext? {
        if (Reify.tagOf(e, src) != Tag.ARRAY) return null
        val cs = Reify.realCommas(e)
        if (idx < 0 || idx >= cs.size) return null
        val start = cs[idx]
        val child = childElementAt(start, src)
        return child j src
    }

    private fun stepByName(e: JsElement, src: Series<Char>, name: String): JsContext? {
        if (Reify.tagOf(e, src) != Tag.OBJECT) return null
        val cs = Reify.realCommas(e)
        var i = 0
        while (i < cs.size) {
            val keyOpen = cs[i]
            val keyElem = childElementAt(keyOpen, src)
            val keyText = stripQuotes(Reify.textOf(keyElem, src))
            if (keyText == name) {
                // value follows key + ':'
                var p = keyElem.a.b + 1
                while (p < src.size && (src[p] == ' ' || src[p] == ':' || src[p] == '\t')) p++
                val valElem = childElementAt(p, src)
                return valElem j src
            }
            i++
        }
        return null
    }

    /** minimal JSON-flavored child element at an absolute offset in src */
    private fun childElementAt(start: Int, src: Series<Char>): JsElement {
        val sub = src.slice(start, src.size)
        val scanned = JsonScan.scan(sub)
        val c0 = scanned[0]
        val adj = (c0.a.a + start) j (c0.a.b + start)
        val commas: Series<Int> = c0.b.size j { k -> val v = c0.b[k]; if (v < 0) v else v + start }
        return adj j commas
    }

    private fun stripQuotes(s: String): String {
        if (s.length >= 2 && (s[0] == '"' || s[0] == '\'') && s[s.length - 1] == s[0])
            return s.substring(1, s.length - 1)
        return s
    }
}


/* ═══════════════════════════════════════════════════════════════════════════
 *  Syntax discriminator — pick the tokenizer uniformly
 * ═══════════════════════════════════════════════════════════════════════════ */

enum class Syntax { JSON, CBOR, YAML }

fun tokenize(syntax: Syntax, src: Series<Char>): Series<JsElement> = when (syntax) {
    Syntax.JSON -> JsonScan.scan(src)
    Syntax.CBOR -> CborScan.scan(src)
    Syntax.YAML -> YamlScan.scan(src)
}

/** convenience: tokenize the root and return a JsContext pointing at element 0 */
fun contextOf(syntax: Syntax, src: Series<Char>): JsContext {
    val elems = tokenize(syntax, src)
    val root = elems[0]
    return root j src
}


/* ═══════════════════════════════════════════════════════════════════════════
 *  CCEK SupervisoryJob — single context Element orchestrating all three
 *  tokenizers with explicit lifecycle and structured fanout.
 *
 *  Keys are singleton identity objects. Lifecycle is forward-only:
 *     CREATED → OPEN → ACTIVE → DRAINING → CLOSED
 *  Fanout is structured concurrency (coroutineScope { launch {...} }).
 * ═══════════════════════════════════════════════════════════════════════════ */

enum class Lifecycle { CREATED, OPEN, ACTIVE, DRAINING, CLOSED }

/** A subscriber consuming rooted contexts as they arrive from any syntax. */
fun interface ConfixSubscriber {
    suspend fun onContext(syntax: Syntax, ctx: JsContext)
}

/** singleton routing identity */
object ConfixKey : CoroutineContext.Key<ConfixElement>

/** Source bundle: one per syntax, each paired with its Series<Char>. */
class ConfixSource(val syntax: Syntax, val src: Series<Char>)

/** tiny growable subscriber registry (no stdlib collection) */
private class SubArray {
    var data: Array<ConfixSubscriber?> = arrayOfNulls(4)
    var size: Int = 0
    fun add(s: ConfixSubscriber) {
        if (size == data.size) {
            val n = arrayOfNulls<ConfixSubscriber>(data.size * 2)
            var i = 0; while (i < size) { n[i] = data[i]; i++ }
            data = n
        }
        data[size++] = s
    }
    fun asSeries(): Series<ConfixSubscriber> {
        val n = size; val d = data
        return n j { i -> d[i]!! }
    }
}

/**
 * Single CCEK SupervisoryJob element. Holds:
 *   - identity key (ConfixKey)
 *   - forward-only lifecycle
 *   - supervisor job so one syntax failure does not cancel siblings
 *   - subscriber fanout
 *   - pointer to the source bundle (JSON + CBOR + YAML) — all three reduce to
 *     the same JsContext algebra.
 */
class ConfixElement(
    private val sources: Series<ConfixSource>,
    parent: CoroutineContext? = null,
) : AbstractCoroutineContextElement(ConfixKey) {

    val supervisor: CompletableJob =
        if (parent == null) SupervisorJob() else SupervisorJob(parent[Job])

    private var _state: Lifecycle = Lifecycle.CREATED
    val lifecycleState: Lifecycle get() = _state

    private val subs = SubArray()
    val fanoutSubscribers: Series<ConfixSubscriber> get() = subs.asSeries()

    fun subscribe(s: ConfixSubscriber) {
        check(_state == Lifecycle.CREATED || _state == Lifecycle.OPEN) {
            "cannot subscribe in state $_state"
        }
        subs.add(s)
    }

    fun open() {
        check(_state == Lifecycle.CREATED) { "open() requires CREATED, was $_state" }
        _state = Lifecycle.OPEN
    }

    /**
     * Activate: tokenize each syntax under a child job of the supervisor, reify
     * roots, then fan out to every subscriber. Failures in one child do not
     * cancel the others (SupervisorJob semantics).
     */
    suspend fun activate() {
        check(_state == Lifecycle.OPEN) { "activate() requires OPEN, was $_state" }
        _state = Lifecycle.ACTIVE
        coroutineScope {
            val n = sources.size
            var i = 0
            while (i < n) {
                val srcBundle = sources[i]
                launch(supervisor) {
                    val ctx = contextOf(srcBundle.syntax, srcBundle.src)
                    val subsSnap = fanoutSubscribers
                    var k = 0
                    while (k < subsSnap.size) {
                        launch(supervisor) { subsSnap[k].onContext(srcBundle.syntax, ctx) }
                        k++
                    }
                }
                i++
            }
        }
    }

    /** Issue a path query against every source; returns Series<JsContext?> aligned with sources. */
    fun query(path: JsPath): Series<JsContext?> {
        check(_state != Lifecycle.CLOSED) { "query on CLOSED" }
        val n = sources.size
        val cached = arrayOfNulls<JsContext>(n)
        var i = 0
        while (i < n) {
            val sb = sources[i]
            cached[i] = contextOf(sb.syntax, sb.src)
            i++
        }
        return n j { k -> cached[k]?.let { Path.resolve(it, path) } }
    }

    fun drain() {
        if (_state == Lifecycle.CLOSED) return
        _state = Lifecycle.DRAINING
    }

    fun close() {
        if (_state == Lifecycle.CLOSED) return
        _state = Lifecycle.CLOSED
        supervisor.complete()
    }
}


/* ─── small helpers to construct paths and sources without stdlib ──────── */

fun path(vararg segs: Any): JsPath {
    val n = segs.size
    return n j { i ->
        when (val s = segs[i]) {
            is String -> Either.Left(s)
            is Int -> Either.Right(s)
            else -> error("path segment must be String or Int, was ${s::class}")
        }
    }
}

fun sources(vararg src: ConfixSource): Series<ConfixSource> {
    val n = src.size
    return n j { i -> src[i] }
}

fun jsonSource(text: CharSequence): ConfixSource = ConfixSource(Syntax.JSON, text.asSeries())
fun yamlSource(text: CharSequence): ConfixSource = ConfixSource(Syntax.YAML, text.asSeries())
fun cborSource(bytes: ByteArray): ConfixSource {
    // widen bytes → chars without stdlib collection
    val n = bytes.size
    val ca = CharArray(n)
    var i = 0
    while (i < n) { ca[i] = (bytes[i].toInt() and 0xFF).toChar(); i++ }
    val series: Series<Char> = n j { k -> ca[k] }
    return ConfixSource(Syntax.CBOR, series)
}
