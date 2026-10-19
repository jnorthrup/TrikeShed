package borg.trikeshed.parse.confix

/**
 * A known-schema plan for JSON texts over UTF-8 bytes (RFC 8259): the top-level object members a reader keeps,
 * their names compiled once to UTF-8 bytes. A [KnownSchemaReader] validates the whole text in one pass, nested
 * values and unplanned members included, and keeps for each planned member the span and kind of its value; a
 * repeated member keeps its last value, as JSON.parse does. Values are then read typed ([KnownSchemaReader.text],
 * [KnownSchemaReader.number], [KnownSchemaReader.long], [KnownSchemaReader.textEquals],
 * [KnownSchemaReader.positiveDecimal]) with no generic tree, map or boxed member; a string is decoded only when it
 * is read, and copied through its escapes only when it has any.
 *
 * Acceptance is the strict grammar of the generic scanners: whitespace is space, tab, LF and CR; numbers follow the
 * RFC grammar; strings reject raw control characters and unknown escapes, take any 4-hex \u escape (lone surrogates
 * included), and decode bytes >= 0x80 as UTF-8 with replacement; nesting deeper than 512 is rejected. A member name
 * matches when the key's decoded text equals it ("s\u0075b" is "sub", a raw 0xFF byte is U+FFFD): an ASCII key without
 * escapes is compared byte for byte with the ASCII names, any other key is decoded and compared with every name.
 * The plan is immutable once built.
 */
class KnownSchema(vararg names: String) {
    private val nameArray: Array<String> = arrayOf(*names)

    /** The member names, by column. */
    val names: List<String> = List(nameArray.size) { nameArray[it] }

    /** UTF-8 bytes of the all-ASCII names, null for the others (those match decoded keys only). */
    internal val asciiKeys: Array<ByteArray?> =
        Array(nameArray.size) { i -> nameArray[i].takeIf { s -> s.all { it.code < 0x80 } }?.encodeToByteArray() }
    val size: Int get() = nameArray.size

    /** The column of member [name], or -1 when the plan does not keep it. */
    fun column(name: String): Int = nameArray.indexOf(name)

    internal fun columnOfText(name: String): Int = nameArray.indexOf(name)

    /** A reader of this plan: one per thread, reused document after document. */
    fun reader(): KnownSchemaReader = KnownSchemaReader(this)
}

/** What a scanned text was: not JSON, a JSON value other than an object, or an object. */
enum class KnownShape { MALFORMED, VALUE, OBJECT }

/** The kind of a planned member's value in the last scanned text; ABSENT when the text has no such member. */
enum class KnownKind { ABSENT, STRING, NUMBER, TRUE, FALSE, NULL, OBJECT, ARRAY }

/**
 * Scans texts against one [KnownSchema]. After [scan] returns OBJECT, each column holds the last value of its member;
 * after VALUE or MALFORMED every column is ABSENT, so a malformed text never yields a partial record. The reader
 * keeps a reference to the scanned bytes until the next scan: read the columns before the bytes change.
 */
class KnownSchemaReader(val schema: KnownSchema) {
    private val n = schema.size
    private val kinds = Array(n) { KnownKind.ABSENT }
    private val opens = IntArray(n)
    private val closes = IntArray(n)
    private val escapes = BooleanArray(n)
    private val wides = BooleanArray(n)
    private var src: ByteArray = EMPTY
    private var at = 0
    private var end = 0
    private var ok = true
    private var escaped = false
    private var wide = false
    private val decoded = StringBuilder()
    private var chars = CharArray(64)

    /**
     * NDJSON with this plan: each line of [src] in [from, to) (LF-terminated; a CR before the LF is dropped; the last
     * line may lack its LF) is scanned and handed to [each] with its shape and span. Lines of only spaces, tabs and
     * CRs are skipped, as StructuredParserSupport.parseNdjson skips blank lines (that one also skips lines of other
     * Unicode whitespace, which JSON rejects anyway). The columns hold a line's values during its [each] call.
     * Nothing is allocated per line.
     * @throws IndexOutOfBoundsException when [from, to) is not a range of [src]
     */
    inline fun forEachLine(src: ByteArray, from: Int = 0, to: Int = src.size, each: (shape: KnownShape, lineFrom: Int, lineTo: Int) -> Unit) {
        if (from < 0 || to > src.size || from > to) throw IndexOutOfBoundsException("[$from, $to) of ${src.size} bytes")
        var at = from
        while (at < to) {
            var end = at
            while (end < to && src[end] != '\n'.code.toByte()) end++
            var last = end
            if (last > at && src[last - 1] == '\r'.code.toByte()) last--
            var blank = true
            for (i in at until last) {
                val b = src[i]
                if (b != ' '.code.toByte() && b != '\t'.code.toByte() && b != '\r'.code.toByte()) {
                    blank = false
                    break
                }
            }
            if (!blank) each(scan(src, at, last), at, last)
            at = end + 1
        }
    }

    /**
     * Validates the JSON text at [from, to) of [src] and records the planned members of a top-level object.
     * @throws IndexOutOfBoundsException when [from, to) is not a range of [src]
     */
    fun scan(src: ByteArray, from: Int = 0, to: Int = src.size): KnownShape {
        if (from < 0 || to > src.size || from > to) throw IndexOutOfBoundsException("[$from, $to) of ${src.size} bytes")
        this.src = src
        at = from
        end = to
        ok = true
        kinds.fill(KnownKind.ABSENT)
        ws()
        val shape = if (at < end && src[at] == LBRACE) {
            members()
            KnownShape.OBJECT
        } else {
            value(0)
            KnownShape.VALUE
        }
        ws()
        if (ok && at == end && shape == KnownShape.OBJECT) return shape
        kinds.fill(KnownKind.ABSENT)
        return if (ok && at == end) shape else KnownShape.MALFORMED
    }

    fun kind(column: Int): KnownKind = kinds[column]

    fun present(column: Int): Boolean = kinds[column] != KnownKind.ABSENT

    /** The value's bytes: a string's content between its quotes, or the whole literal, number or nested value. */
    fun from(column: Int): Int = opens[column]
    fun to(column: Int): Int = closes[column]

    /** The decoded string, or null when the member is absent or not a string. */
    fun text(column: Int): String? {
        if (kinds[column] != KnownKind.STRING) return null
        val from = opens[column]
        val to = closes[column]
        return if (escapes[column]) decodeEscaped(from, to, wides[column]) else src.decodeToString(from, to)
    }

    /**
     * Whether the member is a string whose decoded text equals [expected] decoded as UTF-8 (with replacement).
     * ASCII on both sides is compared byte for byte; an ASCII member never equals non-ASCII [expected] (whose
     * decoding holds a non-ASCII char); everything else compares decoded texts.
     */
    fun textEquals(column: Int, expected: ByteArray): Boolean {
        if (kinds[column] != KnownKind.STRING) return false
        val expectedAscii = expected.all { it >= 0 }
        if (escapes[column] || wides[column]) return text(column) == expected.decodeToString()
        if (!expectedAscii) return false
        val from = opens[column]
        if (closes[column] - from != expected.size) return false
        for (i in expected.indices) if (src[from + i] != expected[i]) return false
        return true
    }

    /**
     * The JSON number as a Double, as JavaScript's Number and the generic scanners read it (fraction and exponent
     * syntax included); NaN when the member is absent or not a number. Integers of at most 15 digits are converted
     * without text, the rest through the platform's decimal conversion.
     */
    fun number(column: Int): Double {
        if (kinds[column] != KnownKind.NUMBER) return Double.NaN
        val from = opens[column]
        val to = closes[column]
        val digitsFrom = if (src[from] == MINUS) from + 1 else from
        if (to - digitsFrom in 1..15) {
            var v = 0L
            var i = digitsFrom
            while (i < to) {
                val d = src[i] - ZERO
                if (d !in 0..9) break
                v = v * 10 + d
                i++
            }
            if (i == to) return if (digitsFrom > from) -v.toDouble() else v.toDouble()
        }
        return src.decodeToString(from, to).toDouble()
    }

    /**
     * The number exactly as a Long when it is written as an integer (no fraction or exponent) within
     * [Long.MIN_VALUE, Long.MAX_VALUE]; null for absent, non-numbers, fractions, exponents and overflow.
     */
    fun long(column: Int): Long? {
        if (kinds[column] != KnownKind.NUMBER) return null
        val from = opens[column]
        val to = closes[column]
        val negative = src[from] == MINUS
        val digitsFrom = if (negative) from + 1 else from
        if (to - digitsFrom !in 1..19) return null
        // Accumulated negatively: the negative range is one larger, so Long.MIN_VALUE parses without overflow.
        var v = 0L
        for (i in digitsFrom until to) {
            val d = src[i] - ZERO
            if (d !in 0..9) return null
            if (v < (Long.MIN_VALUE + d) / 10) return null
            v = v * 10 - d
        }
        return if (negative) v else if (v == Long.MIN_VALUE) null else -v
    }

    /**
     * A string member holding a positive decimal integer of at most 18 digits, read from its decoded text; 0 when
     * the member is absent, not a string, or anything else.
     */
    fun positiveDecimal(column: Int): Long {
        if (kinds[column] != KnownKind.STRING) return 0
        if (escapes[column]) {
            val s = text(column)!!
            if (s.isEmpty() || s.length > 18) return 0
            var v = 0L
            for (c in s) {
                if (c !in '0'..'9') return 0
                v = v * 10 + (c - '0')
            }
            return v
        }
        val from = opens[column]
        val to = closes[column]
        if (to - from !in 1..18) return 0
        var v = 0L
        for (i in from until to) {
            val d = src[i] - ZERO
            if (d !in 0..9) return 0
            v = v * 10 + d
        }
        return v
    }

    private fun fail() {
        ok = false
        at = end
    }

    private fun ws() {
        while (at < end) {
            val c = src[at].toInt()
            if (c == 0x20 || c == 0x09 || c == 0x0A || c == 0x0D) at++ else return
        }
    }

    /** The top-level object from its '{': planned members recorded, the last duplicate winning. */
    private fun members() {
        at++
        ws()
        if (at < end && src[at] == RBRACE) {
            at++
            return
        }
        while (ok) {
            ws()
            if (at >= end || src[at] != QUOTE) return fail()
            val keyFrom = at + 1
            string()
            if (!ok) return
            val keyTo = at - 1
            val column = columnOf(keyFrom, keyTo, escaped)
            ws()
            if (at >= end || src[at] != COLON) return fail()
            at++
            ws()
            val valueFrom = at
            val kind = value(1)
            if (!ok) return
            if (column >= 0) {
                kinds[column] = kind
                if (kind == KnownKind.STRING) {
                    opens[column] = valueFrom + 1
                    closes[column] = at - 1
                    escapes[column] = escaped
                    wides[column] = wide
                } else {
                    opens[column] = valueFrom
                    closes[column] = at
                    escapes[column] = false
                }
            }
            ws()
            if (at >= end) return fail()
            when (src[at]) {
                COMMA -> at++
                RBRACE -> {
                    at++
                    return
                }
                else -> return fail()
            }
        }
    }

    private fun columnOf(from: Int, to: Int, escapedKey: Boolean): Int {
        // Escapes or bytes outside ASCII: the decoded text decides, against every name.
        if (escapedKey || wide) return schema.columnOfText(decodeEscaped(from, to, wide))
        val keys = schema.asciiKeys
        val length = to - from
        for (k in keys.indices) {
            val key = keys[k] ?: continue
            if (key.size != length) continue
            var i = 0
            while (i < length && src[from + i] == key[i]) i++
            if (i == length) return k
        }
        return -1
    }

    /**
     * The text of a validated string span with escapes, decoded as the generic scanners decode it. An ASCII span
     * ([wideSpan] false) is appended char by char; otherwise each run between escapes is decoded as UTF-8.
     */
    private fun decodeEscaped(from: Int, to: Int, wideSpan: Boolean): String {
        decoded.setLength(0)
        if (!wideSpan) {
            // ASCII with escapes: at most one char per byte, into a reused array, then one String.
            if (chars.size < to - from) chars = CharArray(maxOf(to - from, chars.size * 2))
            val out = chars
            var w = 0
            var i = from
            while (i < to) {
                val b = src[i]
                if (b != BACKSLASH) {
                    out[w++] = b.toInt().toChar()
                    i++
                    continue
                }
                out[w++] = when (src[i + 1].toInt().toChar()) {
                    '"' -> '"'
                    '\\' -> '\\'
                    '/' -> '/'
                    'b' -> '\b'
                    'f' -> '\u000C'
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    else -> {
                        var u = 0
                        for (k in 2..5) u = (u shl 4) or hex(src[i + k].toInt())
                        i += 4
                        u.toChar()
                    }
                }
                i += 2
            }
            return out.concatToString(0, w)
        }
        var i = from
        var run = from
        while (i < to) {
            if (src[i] != BACKSLASH) {
                i++
                continue
            }
            if (i > run) decoded.append(src.decodeToString(run, i))
            when (src[i + 1].toInt().toChar()) {
                '"' -> decoded.append('"')
                '\\' -> decoded.append('\\')
                '/' -> decoded.append('/')
                'b' -> decoded.append('\b')
                'f' -> decoded.append('\u000C')
                'n' -> decoded.append('\n')
                'r' -> decoded.append('\r')
                't' -> decoded.append('\t')
                else -> {
                    var u = 0
                    for (k in 2..5) u = (u shl 4) or hex(src[i + k].toInt())
                    decoded.append(u.toChar())
                    i += 4
                }
            }
            i += 2
            run = i
        }
        if (to > run) decoded.append(src.decodeToString(run, to))
        return decoded.toString()
    }

    private fun value(depth: Int): KnownKind {
        if (depth > 512 || at >= end) {
            fail()
            return KnownKind.ABSENT
        }
        return when (src[at]) {
            QUOTE -> {
                string()
                KnownKind.STRING
            }
            LBRACE -> {
                nested(depth, RBRACE)
                KnownKind.OBJECT
            }
            LBRACKET -> {
                nested(depth, RBRACKET)
                KnownKind.ARRAY
            }
            T -> {
                word(TRUE)
                KnownKind.TRUE
            }
            F -> {
                word(FALSE)
                KnownKind.FALSE
            }
            N -> {
                word(NULL)
                KnownKind.NULL
            }
            else -> {
                number()
                KnownKind.NUMBER
            }
        }
    }

    private fun nested(depth: Int, close: Byte) {
        at++
        ws()
        if (at < end && src[at] == close) {
            at++
            return
        }
        while (ok) {
            ws()
            if (close == RBRACE) {
                if (at >= end || src[at] != QUOTE) return fail()
                string()
                if (!ok) return
                ws()
                if (at >= end || src[at] != COLON) return fail()
                at++
                ws()
            }
            value(depth + 1)
            if (!ok) return
            ws()
            if (at >= end) return fail()
            val c = src[at]
            at++
            if (c == close) return
            if (c != COMMA) return fail()
        }
    }

    private fun word(w: ByteArray) {
        if (end - at < w.size) return fail()
        for (i in w.indices) if (src[at + i] != w[i]) return fail()
        at += w.size
    }

    private fun number() {
        if (at < end && src[at] == MINUS) at++
        if (at >= end) return fail()
        if (src[at] == ZERO) at++
        else if (digit(src[at])) {
            while (at < end && digit(src[at])) at++
        } else return fail()
        if (at < end && src[at] == DOT) {
            at++
            if (at >= end || !digit(src[at])) return fail()
            while (at < end && digit(src[at])) at++
        }
        if (at < end && (src[at] == E_LOWER || src[at] == E_UPPER)) {
            at++
            if (at < end && (src[at] == PLUS || src[at] == MINUS)) at++
            if (at >= end || !digit(src[at])) return fail()
            while (at < end && digit(src[at])) at++
        }
    }

    /**
     * From the opening quote past the closing one, validating escapes; [escaped] tells whether it had any, [wide]
     * whether it had a byte outside ASCII.
     */
    private fun string() {
        at++
        escaped = false
        wide = false
        while (at < end) {
            val c = src[at].toInt() and 0xFF
            when {
                c == 0x22 -> {
                    at++
                    return
                }
                c == 0x5C -> {
                    if (at + 1 >= end) return fail()
                    when (src[at + 1].toInt().toChar()) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> {}
                        'u' -> {
                            if (at + 6 > end) return fail()
                            for (k in 2..5) if (hex(src[at + k].toInt()) < 0) return fail()
                            at += 4
                        }
                        else -> return fail()
                    }
                    at += 2
                    escaped = true
                }
                c < 0x20 -> return fail()
                else -> {
                    if (c >= 0x80) wide = true
                    at++
                }
            }
        }
        fail()
    }

    private companion object {
        val EMPTY = ByteArray(0)
        val TRUE = "true".encodeToByteArray()
        val FALSE = "false".encodeToByteArray()
        val NULL = "null".encodeToByteArray()
        const val LBRACE: Byte = 0x7B
        const val RBRACE: Byte = 0x7D
        const val LBRACKET: Byte = 0x5B
        const val RBRACKET: Byte = 0x5D
        const val QUOTE: Byte = 0x22
        const val COLON: Byte = 0x3A
        const val COMMA: Byte = 0x2C
        const val BACKSLASH: Byte = 0x5C
        const val MINUS: Byte = 0x2D
        const val PLUS: Byte = 0x2B
        const val DOT: Byte = 0x2E
        const val ZERO: Byte = 0x30
        const val E_LOWER: Byte = 0x65
        const val E_UPPER: Byte = 0x45
        const val T: Byte = 0x74
        const val F: Byte = 0x66
        const val N: Byte = 0x6E

        fun digit(b: Byte): Boolean = b >= ZERO && b <= 0x39

        fun hex(c: Int): Int = when (c) {
            in 0x30..0x39 -> c - 0x30
            in 0x61..0x66 -> c - 0x61 + 10
            in 0x41..0x46 -> c - 0x41 + 10
            else -> -1
        }
    }
}
