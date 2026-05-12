@file:Suppress(
    "NonAsciiCharacters",
    "FunctionName",
    "ObjectPropertyName",
    "PropertyName",
    "LocalVariableName",
    "unused",
    "MemberVisibilityCanBePrivate",
)
package borg.trikeshed.parse.confix


/* ═══════════════════════════════════════════════════════════════════════════
 *
 *  Zero stdlib collections. Only primitive arrays (IntArray/CharArray/ByteArray)
 *  and Join/Series composition from the kernel algebra. Everything reduces to:
 *
 *     JsElement  = Join<Twin<Int>, Series<Int>>     // (open j close) j commas
 *     JsIndex    = Join<Twin<Int>, Series<Char>>    // (twin      j src)
 *     JsContext  = Join<JsElement, Series<Char>>    // element    j src
 *     JsPath     = Series<Either<CharSequence,Int>>
 *
 *  One tokenizer per syntax emits Series<JsElement> over a shared Series<Char>.
 *  One reifier walks JsContext. One path-scanner walks JsPath over JsContext.
 *  One CCEK SupervisoryJob orchestrates all three with explicit lifecycle and
 *  structured fanout to subscribers.
 * ═══════════════════════════════════════════════════════════════════════════ */


/* ─── kernel algebra — rely on canonical definitions in borg.trikeshed.lib ────────────── */

import borg.trikeshed.lib.*
import borg.trikeshed.userspace.concurrency.ParseLifecycle
import borg.trikeshed.userspace.concurrency.ParseScope
import borg.trikeshed.userspace.concurrency.ParseScopeKey
import kotlinx.coroutines.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

// ── element type aliases (break circular import from parse.json) ──
typealias JsElement = Join<Twin<Int>, Series<Int>>      // (open j close) j commas
typealias JsIndex = Join<Twin<Int>, Series<Char>>       // (twin j src)
typealias JsContext = Join<JsElement, Series<Char>>      // element j src
typealias JsPathElement = Either<CharSequence, Int>
typealias JsPath = Series<JsPathElement>



/** Series of Chars from a CharSequence — use root's CharSequence.toSeries() */
@Suppress("unused")
fun CharSequence.asSeries(): Series<Char> = toSeries()


/** tag byte encoded into commas[0] when the producer wants to signal kind.
 *  Uses negative sentinels so positive comma positions remain unambiguous.
 *  The tag channel is optional — when absent, reifier infers from src[open]. */
enum class Tag(val code: Int) {
    OBJECT(-1),
    ARRAY(-2),
    STRING(-3),
    NUMBER(-4),
    BOOL_TRUE(-5),
    BOOL_FALSE(-6),
    NULL(-7),
    BYTES(-8);          // CBOR byte string; reifier decodes hex-escaped chars

    /** Dispatch kind for bijection: 0=map, 1=list, 2=scalar — collapses 8-way branch to 3-way. */
    val kind: Int get() = when (this) {
        OBJECT -> 0; ARRAY -> 1; else -> 2
    }

    companion object {
        /** Decode a tag code back to a Tag, or null for unknown/positive values. */
        fun fromCode(code: Int): Tag? = when (code) {
            -1 -> OBJECT; -2 -> ARRAY; -3 -> STRING; -4 -> NUMBER
            -5 -> BOOL_TRUE; -6 -> BOOL_FALSE; -7 -> NULL; -8 -> BYTES
            else -> null
        }
    }
}


/* ─── tiny growable IntArray (no kotlin.collections) ────────────────────── */

class IntBuf(initial: Int = 16) {
    var data: IntArray = IntArray(initial)
       set
    var size: Int = 0
       set

    fun add(v: Int) {
        if (size == data.size) {
            val n = IntArray(data.size * 2)
            var i = 0
            while (i < size) { n[i] = data[i]; i++ }
            data = n
        }
        data[size++] = v
    }

    fun resize(newSize: Int) {
        require(newSize <= data.size) { "cannot grow via resize — use add()" }
        size = newSize
    }

    fun toSeries(): Series<Int> {
        val n = size
        val d = data
        return n j { i: Int -> d[i] }
    }

    fun snapshot(): IntArray {
        val out = IntArray(size)
        var i = 0; while (i < size) { out[i] = data[i]; i++ }
        return out
    }
}

class ElemBuf(initial: Int = 16) {
   var opens: IntArray = IntArray(initial)
   var closes: IntArray = IntArray(initial)
   var commaHeads: IntArray = IntArray(initial) // start offset into commas pool
   var commaTails: IntArray = IntArray(initial) // end offset (exclusive)
    var commas: IntBuf = IntBuf(initial * 2)
       set
    var size: Int = 0
       set

    fun commasSize(): Int = commas.size

    fun truncateCommas(n: Int) {
        commas.resize(n)
    }

   fun grow() {
        val n = opens.size * 2
        opens = opens.copyGrow(n); closes = closes.copyGrow(n)
        commaHeads = commaHeads.copyGrow(n); commaTails = commaTails.copyGrow(n)
    }

   fun IntArray.copyGrow(n: Int): IntArray {
        val x = IntArray(n); var i = 0; while (i < size) { x[i] = this[i]; i++ }; return x
    }

    /** begin a new element; returns its index. Caller fills commas via [addComma] then calls [endOf]. */
    fun begin(openIdx: Int): Int {
        if (size == opens.size) grow()
        val idx = size
        opens[idx] = openIdx
        commaHeads[idx] = commas.size
        size = idx + 1
        return idx
    }

    fun addComma(pos: Int) { commas.add(pos) }

    fun endOf(elemIdx: Int, closeIdx: Int, tag: Tag) {
        // End of element finalizes close index and the comma tail. Do not shrink size.
        closes[elemIdx] = closeIdx
        commaTails[elemIdx] = commas.size
        if (size < elemIdx + 1) size = elemIdx + 1
        @Suppress("UNUSED_PARAMETER") val _t = tag
    }

    /** Prefer: call [beginTagged] which writes the tag into commas[head] first. */
    fun beginTagged(openIdx: Int, tag: Tag): Int {
        val i = begin(openIdx)
        commas.add(tag.code)
        return i
    }

    // expose element open/close indexes for callers that need precise child anchors
    fun openOf(elemIdx: Int): Int { return opens[elemIdx] }
    fun closeOf(elemIdx: Int): Int { return closes[elemIdx] }

    fun toSeries(): Series<JsElement> {
        val n = size
        val o = opens; val c = closes; val h = commaHeads; val t = commaTails
        val pool = commas.data
        val poolSize = commas.size
        return n j { i: Int ->
            val head = h[i]; val tail = t[i]
            val len = tail - head
            val commaSeries: Series<Int> = len j { k: Int -> pool[head + k] }
            (o[i] j c[i]) j commaSeries
        }.also { _ -> @Suppress("UNUSED_VARIABLE") val _p = poolSize }
    }
}


/* ═══════════════════════════════════════════════════════════════════════════
 *  Reifier — JsContext → logical value, uniformly over JSON/CBOR/YAML.
 *
 *  Returns Any? only because the algebra is dynamic. No stdlib collections:
 *  arrays become Series<Any?>, objects become Series2<CharSequence,Any?>.
 * ═══════════════════════════════════════════════════════════════════════════ */

object Combinators {

    // ── Bijection tables (std → TrikeShed) ──────────────────────────────────

    /** Bijection: Char → Tag. 128-entry lookup; unmapped chars default to NUMBER. */
    private val charToTagTable: ByteArray by lazy {
        val a = ByteArray(128) { Tag.NUMBER.code.toByte() }
        a[123] = Tag.OBJECT.code.toByte()    // '{'
        a[91]  = Tag.ARRAY.code.toByte()     // '['
        a[34]  = Tag.STRING.code.toByte()    // '"'
        a[39]  = Tag.STRING.code.toByte()    // '''
        a[116] = Tag.BOOL_TRUE.code.toByte() // 't'
        a[102] = Tag.BOOL_FALSE.code.toByte()// 'f'
        a[110] = Tag.NULL.code.toByte()      // 'n'
        // digits 0-9, '-' default to NUMBER
        a
    }

    /** α: Char → Tag. Single lookup, 2 branches max. */
    private fun charToTag(ch: Char): Tag {
        val ci = ch.code
        if (ci < 128) Tag.fromCode(charToTagTable[ci].toInt())?.let { return it }
        return Tag.NUMBER
    }

    /** Bijection: Char → skip behavior index.
     *  0=whitespace (advance), 1=string, 2=bracket, 3=literal, 4=number. */
    private val charToSkipTable: ByteArray by lazy {
        val a = ByteArray(128) { 0 }
        a[34]  = 1; a[39]  = 1   // '"'  '''
        a[123] = 2; a[91]  = 2   // '{'  '['
        a[116] = 3; a[102] = 3; a[110] = 3  // 't' 'f' 'n'
        a[45]  = 4; a[43]  = 4   // '-'  '+'
        for (d in 48..57) a[d] = 4  // '0'-'9'
        a
    }

    /** α: Char → skip kind. Single lookup, 2 branches max. */
    private fun skipKind(ch: Char): Int {
        val ci = ch.code
        return if (ci < 128) charToSkipTable[ci].toInt() else 0
    }

    // ── Tag inference ─────────────────────────────────────────────────────

    /** Returns the element's tag (inferred from commas[0] if negative, else from src[open]).
     *  Uses Char→Tag bijection lookup for monomorphic dispatch (2-branch max). */
    fun tagOf(e: JsElement, src: Series<Char>): Tag {
        val commas = e.b
        if (commas.size > 0) {
            val c0 = commas[0]
            if (c0 < 0) Tag.fromCode(c0)?.let { return it }
        }
        val open = e.a.a
        if (open >= src.size) return Tag.NULL
        return Combinators.charToTag(src[open])
    }

    /** Direct child positions found by scanning the element's source span.
     *  Single pass: collects positions into an [IntBuf], returns materialized [Series<Int>].
     *  Materialization avoids the O(k × span) re-scan that the old two-pass lazy approach
     *  incurred on every indexed access (e.g. [reifyObject] with N keys re-scanned N×2 times). */
    fun realCommas(e: JsElement, src: Series<Char>): Series<Int> {
        val open = e.a.a; val close = e.a.b
        val buf = IntBuf(16)  // most elements have ≤16 children — no growth in common case
        var p = open + 1
        while (p < close) {
            val kind = Combinators.skipKind(src[p])
            if (kind != 0) {
                buf.add(p)
                p = skipByKind(kind, src, p, close)
            } else {
                p++
            }
        }
        return buf.toSeries()
    }

    /** Dispatch skip function by kind index — 5-way branch, monomorphic per call site. */
    private fun skipByKind(kind: Int, src: Series<Char>, pos: Int, limit: Int): Int = when (kind) {
        1 -> skipString(src, pos, limit)
        2 -> skipBracket(src, pos, limit)
        3 -> skipLiteral(src, pos, limit)
        else -> skipNumber(src, pos, limit)  // kind=4
    }

    private fun skipString(src: Series<Char>, start: Int, limit: Int): Int {
        val quote = src[start]; var p = start + 1
        while (p < limit && src[p] != quote) {
            if (src[p] == '\\') p++ // skip escaped char
            p++
        }
        return if (p < limit) p + 1 else limit  // skip closing quote
    }

    private fun skipBracket(src: Series<Char>, start: Int, limit: Int): Int {
        val open = src[start]; val close = if (open == '{') '}' else ']'
        var depth = 1; var p = start + 1
        while (p < limit && depth > 0) {
            when (src[p]) {
                open -> depth++
                close -> depth--
                '"', '\'' -> p = skipString(src, p, limit) - 1
            }
            p++
        }
        return p
    }

    private fun skipLiteral(src: Series<Char>, start: Int, limit: Int): Int {
        var p = start
        while (p < limit && src[p] in 'a'..'z') p++
        return p
    }

    private fun skipNumber(src: Series<Char>, start: Int, limit: Int): Int {
        var p = start
        if (p < limit && (src[p] == '-' || src[p] == '+')) p++
        while (p < limit && (src[p] in '0'..'9' || src[p] == '.' || src[p] == 'e' || src[p] == 'E' || src[p] == '+' || src[p] == '-')) p++
        return p
    }

    /** slice of src between open..close inclusive (exclusive of delimiters if applicable) */
    private fun spanOf(e: JsElement, src: Series<Char>): Series<Char> =
        src.slice(e.a.a, e.a.b + 1)

    /** materialize a text span into a CharSequence — reuses a pooled CharArray. */
    fun textOf(e: JsElement, src: Series<Char>): CharSequence {
        val a = e.a.a; val b = e.a.b
        val len = b - a + 1
        if (len <= 0) return ""
        val ca = textBuf.let {
            if (it.size < len) textBuf = CharArray(len)
            textBuf
        }
        var i = 0
        while (i < len) { ca[i] = src[a + i]; i++ }
        return ca.concatToString(0, len)
    }

    /** Thread-local CharArray buffer for textOf — avoids per-span allocation. */
   private var textBuf: CharArray = CharArray(256)

    /** reify the value rooted at [ctx], using [syntax] to discriminate CBOR vs text decode paths. */
    fun reify(ctx: JsContext, syntax: Syntax = Syntax.JSON): Any? {
        val e = ctx.a; val src = ctx.b
        val tag = tagOf(e, src)
        return when (tag.kind) {
            0 -> {  // OBJECT
                val r = reifyObject(e, src, syntax)
                if (r.first == 0) LinkedHashMap<CharSequence, Any?>(0) else r
            }
            1 -> {  // ARRAY
                val r = reifyArray(e, src, syntax)
                if (r.first == 0) ArrayList<Any?>(0) else r
            }
            else -> when (tag) {  // scalars — sub-dispatch on specific tag
                Tag.STRING, Tag.BYTES -> reifyString(e, src, syntax)
                Tag.NUMBER -> reifyNumber(e, src, syntax)
                Tag.BOOL_TRUE -> true
                Tag.BOOL_FALSE -> false
                Tag.NULL -> null
                else -> null
            }
        }
    }

   private fun reifyString(e: JsElement, src: Series<Char>, syntax: Syntax): CharSequence {
        val a = e.a.a; val b = e.a.b
        // quoted JSON/YAML strings
        if (a <= b && (src[a] == '"' || src[a] == '\'')) {
            val quote = src[a]
            val start = a + 1
            val end = b - 1
            if (end < start) return ""
            val ca = CharArray(end - start + 1)
            var i = 0
            while (i < ca.size) { ca[i] = src[start + i]; i++ }
            val raw = ca.concatToString()
            if (quote == '"' && raw.contains('\\')) {
                val u = unescapeJsonString(raw)
                return u
            }
            return if (quote == '"') unescapeJsonString(raw) else unescapeYamlSingleQuoted(raw)
        }

        // CBOR text string decode — only when source is CBOR binary
        if (syntax == Syntax.CBOR) {
            val tag = tagOf(e, src)
            if (tag == Tag.STRING || tag == Tag.BYTES) {
                val ib = src[a].code and 0xFF
                val mt = ib ushr 5
                val ai = ib and 0x1F
                if (mt == 3) {
                    var p = a + 1
                    val len = when (ai) {
                        in 0..23 -> ai
                        24 -> { val v = src[p].code and 0xFF; p += 1; v }
                        25 -> { val v = ((src[p].code and 0xFF) shl 8) or (src[p + 1].code and 0xFF); p += 2; v }
                        26 -> {
                            val v = ((src[p].code and 0xFF) shl 24) or
                                    ((src[p + 1].code and 0xFF) shl 16) or
                                    ((src[p + 2].code and 0xFF) shl 8) or
                                    (src[p + 3].code and 0xFF)
                            p += 4; v
                        }
                        else -> -1
                    }
                    if (len >= 0 && p + len - 1 <= b) {
                        val ca = CharArray(len)
                        var i = 0
                        while (i < len && p + i <= b) { ca[i] = src[p + i]; i++ }
                        return ca.concatToString()
                    }
                }
            }
        }

        // fallback: return raw trimmed scalar (YAML unquoted plain scalar)
        val ca = CharArray(b - a + 1)
        var i = 0
        while (i < ca.size) { ca[i] = src[a + i]; i++ }
        return ca.concatToString().trim()
    }

    // helper: unescape JSON-style quotes (handles \n, \uXXXX, etc.)
   private fun unescapeJsonString(s: CharSequence): CharSequence {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                i++
                val e = s[i]
                when (e) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    '\\' -> out.append('\\')
                    '/' -> out.append('/')
                    '"' -> out.append('"')
                    'u' -> {
                        var code = 0
                        var consumed = 0
                        while (consumed < 4 && i + 1 + consumed < s.length) {
                            val ch = s[i + 1 + consumed]
                            val d = ch.digitToIntOrNull(16) ?: break
                            code = (code shl 4) or d
                            consumed++
                        }
                        out.append(code.toChar())
                        i += consumed
                    }
                    else -> out.append(e)
                }
            } else {
                out.append(c)
            }
            i++
        }
        return out.toString()
    }

   private fun unescapeYamlSingleQuoted(s: CharSequence): CharSequence {
        // YAML single-quoted scalar: replace doubled single-quotes with a single quote
        val out = StringBuilder(s.length)
        var index = 0
        while (index < s.length) {
            if (s[index] == '\'' && index + 1 < s.length && s[index + 1] == '\'') {
                out.append('\'')
                index += 2
            } else {
                out.append(s[index])
                index++
            }
        }
        return out.toString()
    }

   private fun reifyNumber(e: JsElement, src: Series<Char>, syntax: Syntax): Number {
        // CBOR numeric decode — only when source is CBOR binary
        val a = e.a.a
        if (syntax == Syntax.CBOR && a < src.size) {
            val ib = src[a].code and 0xFF
            val mt = ib ushr 5
            val ai = ib and 0x1F
            var p = a + 1
            if (mt == 0 || mt == 1) {
                // unsigned or negative integer
                val value: Long = when (ai) {
                    in 0..23 -> ai.toLong()
                    24 -> { if (p < src.size) { val v = src[p].code and 0xFF; p += 1; v.toLong() } else -1L }
                    25 -> { if (p + 1 < src.size) { val v = ((src[p].code and 0xFF) shl 8) or (src[p + 1].code and 0xFF); p += 2; v.toLong() } else -1L }
                    26 -> { if (p + 3 < src.size) {
                        val v = ((src[p].code and 0xFF) shl 24) or
                                ((src[p + 1].code and 0xFF) shl 16) or
                                ((src[p + 2].code and 0xFF) shl 8) or
                                (src[p + 3].code and 0xFF)
                        p += 4; v.toLong()
                    } else -1L }
                    27 -> { if (p + 7 < src.size) {
                        var v = 0L; var k = 0
                        while (k < 8) { v = (v shl 8) or (src[p + k].code and 0xFF).toLong(); k++ }
                        p += 8; v
                    } else -1L }
                    else -> -1L
                }
                if (value >= 0L) {
                    return if (mt == 0) value.toDouble() else (-(value.toDouble()) - 1.0)
                }
            }
        }
        // Fallback: textual numeric span — try Int, then Long, then Double
        val t = textOf(e, src).trim().toString()
        t.toIntOrNull()?.let { return it }
        t.toLongOrNull()?.let { return it }
        return t.toDouble()
    }

    /** Parse one child at global position [childOpen] — no slice, no offset adjust. */
   private fun reifyChildAt(src: Series<Char>, childOpen: Int): JsElement =
        JsonScan.parseOne(src, childOpen)

    /** child JsElement at commas[k] — for arrays, each comma is an element open */
   private fun reifyArray(e: JsElement, src: Series<Char>, syntax: Syntax): Series<Any?> {
        val cs = realCommas(e, src)
        return cs.size j { i: Int -> reify(reifyChildAt(src, cs[i]) j src, syntax) }
    }

    /** object has keys at even child indices, values at odd indices */
   private fun reifyObject(e: JsElement, src: Series<Char>, syntax: Syntax): Series2<CharSequence, Any?> {
        val all = realCommas(e, src)
        val keyCount = all.size / 2
        return keyCount j { i: Int ->
            val keyElem = reifyChildAt(src, all[i * 2])
            val key = reifyString(keyElem, src, syntax)
            var p = keyElem.a.b + 1  // key close+1, skip ':' and whitespace
            while (p < src.size && (src[p] == ' ' || src[p] == ':' || src[p] == '\t' || src[p] == '\n' || src[p] == '\r')) p++
            val valElem = reifyChildAt(src, p)
            key j reify(valElem j src, syntax)
        }
    }
}


/* ═══════════════════════════════════════════════════════════════════════════
 *  Path scanning — JsPath over JsContext
 *
 *  A JsPath is a Series<Either<CharSequence,Int>>. Walking it descends into the
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

   fun step(ctx: JsContext, seg: JsPathElement): JsContext? {
        val e = ctx.a; val src = ctx.b
        return when (val s = seg) {
            is Either.Left -> stepByName(e, src, s.value)
            is Either.Right -> stepByIndex(e, src, s.value)
        }
    }

   fun stepByIndex(e: JsElement, src: Series<Char>, idx: Int): JsContext? {
        val tag = Combinators.tagOf(e, src)
        if (tag != Tag.ARRAY) return null

        // Strategy: prefer stored commas for YAML (authoritative), realCommas for JSON (char-class scan).
        // Heuristic: if stored commas have few positives (≤ realCommas count), they're from a clean
        // YAML/JSON full-scan. If stored has many more positives than realCommas, they're polluted
        // (JSON micro-scan includes nested child commas) — use realCommas instead.
        val rc = Combinators.realCommas(e, src)
        val stored = e.b
        var storedPosCount = 0; var ii = 0
        while (ii < stored.size) { if (stored[ii] >= 0) storedPosCount++; ii++ }

        val useStored = storedPosCount > 0 && (rc.size == 0 || storedPosCount <= rc.size)

        if (useStored) {
            var found = 0; var i = 0
            while (i < stored.size) {
                if (stored[i] >= 0) {
                    if (found == idx) return childElementAt(stored[i], src) j src
                    found++
                }
                i++
            }
            return null
        }

        // JSON path: realCommas gives correct positions
        if (idx < rc.size) return childElementAt(rc[idx], src) j src
        return null
    }

   fun stepByName(e: JsElement, src: Series<Char>, name: CharSequence): JsContext? {
        if (Combinators.tagOf(e, src) != Tag.OBJECT) return null

        // Two-pass key lookup:
        // 1. Stored comma series — has parser-recorded key positions (works for YAML
        //    plain-identifier keys that realCommas can't detect via char-class scan).
        //    For JSON micro-scan elements, stored commas include nested child commas,
        //    so extra entries are iterated but non-key positions won't match key text.
        // 2. realCommas fallback — char-class re-scan (works for JSON where stored commas
        //    may be polluted by nested children and the key positions happen to overlap).

        val stored = e.b
        var positiveCount = 0; var jj = 0
        while (jj < stored.size) { if (stored[jj] >= 0) positiveCount++; jj++ }

        // Pass 1: stored commas
        if (positiveCount > 0) {
            val result = lookupKeyInPositions(positiveCount, { k ->
                var ii = 0; var seen = 0
                while (ii < stored.size) {
                    if (stored[ii] >= 0) {
                        if (seen == k) return@lookupKeyInPositions stored[ii]
                        seen++
                    }
                    ii++
                }
                -1
            }, e, src, name)
            if (result != null) return result
        }

        // Pass 2: realCommas
        val rc = Combinators.realCommas(e, src)
        if (rc.size > 0) {
            val result = lookupKeyInPositions(rc.size, { k -> rc[k] }, e, src, name)
            if (result != null) return result
        }

        return null
    }

    /** Iterate key positions, resolve each to a key element, match against name */
    private fun lookupKeyInPositions(
        count: Int,
        getPosition: (Int) -> Int,
        e: JsElement,
        src: Series<Char>,
        name: CharSequence
    ): JsContext? {
        val parentOpen = e.a.a
        val parentClose = e.a.b
        var i = 0
        while (i < count) {
            val keyOpen = getPosition(i)
            if (keyOpen < 0) { i++; continue }
            // Skip keys that are inside a nested container (YAML comma leak guard)
            // A key at position keyOpen is a direct child of element e only if
            // it's not contained within a child OBJECT/ARRAY whose open > parentOpen.
            // We check this by seeing if any child element range [co, cc) contains keyOpen
            // where co > parentOpen and the child tag is OBJECT or ARRAY.
            // Simple approach: skip if keyOpen is deeper-indented than the first key.
            if (keyOpen > parentOpen && keyOpen < parentClose && i > 0) {
                val prevKeyOpen = getPosition(0)
                // If the first key was at indent X, direct children should be at indent X too.
                // Count leading spaces for comparison.
                var sp0 = 0; var p0 = prevKeyOpen
                while (p0 > 0 && src[p0 - 1] == ' ') { sp0++; p0-- }
                var spCur = 0; var pCur = keyOpen
                while (pCur > 0 && src[pCur - 1] == ' ') { spCur++; pCur-- }
                if (spCur > sp0) { i++; continue }  // deeper indent than siblings = nested child
            }
            var keyElem = childElementAt(keyOpen, src)
            // childElementAt prefers containers (OBJECT/ARRAY) over STRING when
            // both share the same open position (common in YAML block keys where
            // the key starts the same line as the parent container). For key
            // lookup we always want the STRING element, not the container.
            val keyTag = Combinators.tagOf(keyElem, src)
            if (keyTag == Tag.OBJECT || keyTag == Tag.ARRAY) {
                try {
                    val all = YamlScan.scan(src)
                    var k = 0
                    var found: JsElement? = null
                    while (k < all.size) {
                        val cand = all[k]
                        if (cand.a.a == keyOpen && Combinators.tagOf(cand, src) == Tag.STRING) {
                            found = cand
                            break
                        }
                        k++
                    }
                    if (found != null) keyElem = found
                } catch (_: Throwable) { /* keep original keyElem */ }
            }
            val keyText = stripQuotes(Combinators.textOf(keyElem, src))
            if (keyText == name) {
                // value follows key + ':'
                var p = keyElem.a.b + 1
                // skip colon, whitespace and newlines so childElementAt starts at the real value
                while (p < src.size && (src[p] == ' ' || src[p] == ':' || src[p] == '\t' || src[p] == '\n' || src[p] == '\r')) p++
                // if value starts with a sequence marker on the next line, use the line start as the open anchor
                if (p < src.size && src[p] == '-') {
                    var p2 = p
                    while (p2 > 0 && src[p2 - 1] != '\n' && src[p2 - 1] != '\r') p2--
                    p = p2
                }
                val valElem = childElementAt(p, src)
                return valElem j src
            }
            i++
        }
        return null
    }

    /** minimal JSON/YAML/CBOR-flavored child element at an absolute offset in src */
   fun childElementAt(start: Int, src: Series<Char>): JsElement {
        val sub = src.slice(start, src.size)
        if (sub.size == 0) throw IllegalStateException("empty child slice at $start")
        val first = sub[0]
        // If the first char is non-printable it's likely CBOR — prefer the shortest matching candidate
        if (first.code <= 0x1F) {
            val bytes = ByteArray(src.size) { i -> src[i].code.toByte() }
            val all = CborScan.scan(bytes.toSeries())
            var k = 0
            var best: JsElement? = null
            var bestLen = Int.MAX_VALUE
            while (k < all.size) {
                val cand = all[k]
                if (cand.a.a == start) {
                    val len = cand.a.b - cand.a.a
                    if (len < bestLen) { best = cand; bestLen = len }
                    if (Combinators.tagOf(cand, src) == Tag.STRING) return cand
                }
                k++
            }
            if (best != null) return best
            throw IllegalStateException("cannot locate child at $start in CBOR scan")
        }
        // Heuristic: try JSON-like starters quickly (but avoid misclassifying YAML '-' sequence markers)
        try {
            val isSignNumber = (first == '-' || first == '+') && sub.size > 1 && (sub[1] in '0'..'9')
            if (first == '{' || first == '[' || first == '"' || /* single-quote routed to YAML */ isSignNumber || (first >= '0' && first <= '9') || first == 't' || first == 'f' || first == 'n') {
                val scanned = JsonScan.scan(sub)
                val c0 = scanned[0]
                val adj = (c0.a.a + start) j (c0.a.b + start)
                val commas: Series<Int> = c0.b.size j { k: Int -> val v = c0.b[k]; if (v < 0) v else v + start }
                return adj j commas
            }
        } catch (_: Throwable) {
            // ignore and try YAML below
        }
        // Try YAML full-scan and pick the element whose open==start, or that contains start (prefer STRING or the shortest span)
        try {
            val all = YamlScan.scan(src)
            var k = 0
            var bestExactNonNull: JsElement? = null
            var bestExactNonNullLen = Int.MAX_VALUE
            var bestExactAny: JsElement? = null
            var bestExactAnyLen = Int.MAX_VALUE
            var bestExactContainer: JsElement? = null
            var bestExactContainerLen = Int.MAX_VALUE
            var bestContainNonNull: JsElement? = null
            var bestContainNonNullLen = Int.MAX_VALUE
            var bestContainAny: JsElement? = null
            var bestContainAnyLen = Int.MAX_VALUE
            while (k < all.size) {
                val cand = all[k]
                val len = cand.a.b - cand.a.a
                val tag = Combinators.tagOf(cand, src)
                if (cand.a.a == start) {
                    // prefer container exact matches (OBJECT/ARRAY) over scalars
                    if (tag == Tag.OBJECT || tag == Tag.ARRAY) {
                        if (len < bestExactContainerLen) { bestExactContainer = cand; bestExactContainerLen = len }
                    }
                    if (tag != Tag.NULL) {
                        if (len < bestExactNonNullLen) { bestExactNonNull = cand; bestExactNonNullLen = len }
                    }
                    if (len < bestExactAnyLen) { bestExactAny = cand; bestExactAnyLen = len }
                } else if (cand.a.a <= start && cand.a.b >= start) {
                    if (tag != Tag.NULL) {
                        if (len < bestContainNonNullLen) { bestContainNonNull = cand; bestContainNonNullLen = len }
                    }
                    if (len < bestContainAnyLen) { bestContainAny = cand; bestContainAnyLen = len }
                }
                k++
            }
            if (bestExactContainer != null) {
                return bestExactContainer
            }
            if (bestExactNonNull != null) {
                return bestExactNonNull
            }
            if (bestExactAny != null) {
                return bestExactAny
            }
            if (bestContainNonNull != null) {
                return bestContainNonNull
            }
            if (bestContainAny != null) {
                return bestContainAny
            }
        } catch (_: Throwable) {
            // ignore
        }
        // Fallback: try JSON again to surface better error
        try {
            val scanned = JsonScan.scan(sub)
            val c0 = scanned[0]
            val adj = (c0.a.a + start) j (c0.a.b + start)
            val commas: Series<Int> = c0.b.size j { k: Int -> val v = c0.b[k]; if (v < 0) v else v + start }
            return adj j commas
        } catch (_: Throwable) {
            val bytes = ByteArray(src.size) { i -> src[i].code.toByte() }
            val all = CborScan.scan(bytes.toSeries())
            var k = 0
            var best: JsElement? = null
            var bestLen = Int.MAX_VALUE
            while (k < all.size) {
                val cand = all[k]
                if (cand.a.a == start) {
                    val len = cand.a.b - cand.a.a
                    if (len < bestLen) { best = cand; bestLen = len }
                }
                k++
            }
            if (best != null) return best
            throw IllegalStateException("cannot locate child at $start in any scan")
        }
    }

   fun stripQuotes(s: CharSequence): CharSequence {
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
    Syntax.CBOR -> {
        val bytes = ByteArray(src.size) { i -> src[i].code.toByte() }
        CborScan.scan(bytes.toSeries())
    }
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
 *  Delegates lifecycle management to root's ParseScope/ParseLifecycle.
 *  Fanout is structured concurrency (coroutineScope { launch {...} }).
 * ═══════════════════════════════════════════════════════════════════════════ */

/** A subscriber consuming rooted contexts as they arrive from any syntax. */
fun interface ConfixSubscriber {
    suspend fun onContext(syntax: Syntax, ctx: JsContext)
}

/** Source bundle: one per syntax, each paired with its Series<Char>. */
class ConfixSource(val syntax: Syntax, val src: Series<Char>)

/**
 * Single CCEK SupervisoryJob element. Holds:
 *   - identity key (ParseScopeKey via ParseScope delegation)
 *   - forward-only lifecycle delegated to ParseScope
 *   - supervisor job so one syntax failure does not cancel siblings
 *   - subscriber fanout
 *   - pointer to the source bundle (JSON + CBOR + YAML) — all three reduce to
 *     the same JsContext algebra.
 */
class ConfixElement(
   val sources: Series<ConfixSource>,
    parent: CoroutineContext? = null,
) : AbstractCoroutineContextElement(ParseScopeKey) {

    /**
     * Internal ParseScope that owns the lifecycle state machine.
     * Uses a dummy source/span since ConfixElement fans out to its own sources.
     */
   val parseScope = ParseScope(
        source = 0 j { _: Int -> '\u0000' },
        span = 0 j 0,
        parentContext = parent
    )

    val supervisor: CompletableJob get() = parseScope.supervisor

    val lifecycleState: ParseLifecycle get() = parseScope.lifecycleState

   val subs = mutableListOf<ConfixSubscriber>()

    fun subscribe(s: ConfixSubscriber) {
        val st = parseScope.lifecycleState
        check(st == ParseLifecycle.CREATED || st == ParseLifecycle.OPEN) {
            "cannot subscribe in state $st"
        }
        subs.add(s)
    }

    fun open() {
        parseScope.open()
    }

    /**
     * Activate: tokenize each syntax under a child job of the supervisor, reify
     * roots, then fan out to every subscriber. Failures in one child do not
     * cancel the others (SupervisorJob semantics).
     */
    suspend fun activate() {
        parseScope.activate()
        coroutineScope {
            val n = sources.size
            var i = 0
            while (i < n) {
                val srcBundle = sources[i]
                launch(parseScope.supervisor) {
                    val ctx = contextOf(srcBundle.syntax, srcBundle.src)
                    val subsSnap = subs.toList()
                    var k = 0
                    while (k < subsSnap.size) {
                        launch(parseScope.supervisor) { subsSnap[k].onContext(srcBundle.syntax, ctx) }
                        k++
                    }
                }
                i++
            }
        }
    }

    /** Issue a path query against every source; returns Series<JsContext?> aligned with sources. */
    fun query(path: JsPath): Series<JsContext?> {
        check(parseScope.lifecycleState != ParseLifecycle.CLOSED) { "query on CLOSED" }
        val n = sources.size
        val cached = arrayOfNulls<JsContext>(n)
        var i = 0
        while (i < n) {
            val sb = sources[i]
            cached[i] = contextOf(sb.syntax, sb.src)
            i++
        }
        return n j { k: Int -> cached[k]?.let { Path.resolve(it, path) } }
    }

    fun drain() {
        parseScope.drain()
    }

    fun close() {
        parseScope.close()
    }
}


/* ─── small helpers to construct paths and sources without stdlib ──────── */

fun path(vararg segs: Any): JsPath {
    val n = segs.size
    return n j { i ->
        when (val s = segs[i]) {
            is CharSequence -> Either.Left(s)
            is Int -> Either.Right(s)
            else -> error("path segment must be CharSequence or Int, was ${s::class}")
        }
    }
}

fun sources(vararg src: ConfixSource): Series<ConfixSource> {
    val n = src.size
    return n j { i: Int -> src[i] }
}

fun jsonSource(text: CharSequence): ConfixSource = ConfixSource(Syntax.JSON, text.toSeries())
fun yamlSource(text: CharSequence): ConfixSource = ConfixSource(Syntax.YAML, text.toSeries())
fun cborSource(bytes: ByteArray): ConfixSource {
    // widen bytes → chars without stdlib collection
    val n = bytes.size
    val ca = CharArray(n)
    var i = 0
    while (i < n) { ca[i] = (bytes[i].toInt() and 0xFF).toChar(); i++ }
    val series: Series<Char> = n j { k: Int -> ca[k] }
    return ConfixSource(Syntax.CBOR, series)
}

