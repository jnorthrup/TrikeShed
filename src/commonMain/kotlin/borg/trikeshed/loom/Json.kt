package borg.trikeshed.loom

/**
 * JSON as serde_json 1.0 (default features, no `preserve_order` or `float_roundtrip`) reads and
 * writes it for the cocaine-rats loom tools. A value is [Null], Boolean, Long (serde `NegInt`, and
 * `PosInt` up to Long.MAX_VALUE), ULong (`PosInt` above it), Double (`Float`), String, List<Any> or
 * MutableMap<String, Any>. Kotlin null is an absent value (serde `None`), never JSON null.
 */
object Null {
    override fun toString(): String = "null"
}

/**
 * `serde_json::from_slice` into a `Value`, or null where serde_json refuses the bytes. [unique]
 * refuses a repeated object key at any depth (loomctl `strict::Strict`); otherwise the last wins.
 */
fun json(bytes: ByteArray, unique: Boolean): Any? = try {
    JsonReader(bytes, unique).run {
        value().also {
            whitespace()
            require(at == bytes.size)
        }
    }
} catch (refused: IllegalArgumentException) {
    null
}

/** loom-mesh `x402::strict_json`: unique keys at every depth and nothing after the value. */
fun strictJson(raw: ByteArray): Any {
    if (raw.size > 2 * 1024 * 1024) error("JSON too large")
    return json(raw, unique = true) ?: error("invalid JSON")
}

/** `Value::as_u64`. */
fun asU64(value: Any?): ULong? = when (value) {
    is Long -> if (value >= 0) value.toULong() else null
    is ULong -> value
    else -> null
}

/** `PartialEq for Value`: numbers compare within their serde variant, floats by IEEE equality. */
fun same(a: Any?, b: Any?): Boolean = when (a) {
    is Double -> b is Double && a == b
    is List<*> -> b is List<*> && a.size == b.size && a.indices.all { same(a[it], b[it]) }
    is Map<*, *> -> b is Map<*, *> && a.size == b.size && a.all { (k, v) -> b.containsKey(k) && same(v, b[k]) }
    else -> a == b
}

/** `serde_json::to_string`: compact, object keys in byte order (its map is a BTreeMap). */
fun jsonText(value: Any): String = StringBuilder().also { it.jsonValue(value, null) }.toString()

/** `serde_json::to_string_pretty`: two-space indent, `": "` after each key. */
fun jsonPretty(value: Any): String = StringBuilder().also { it.jsonValue(value, "") }.toString()

fun StringBuilder.jsonValue(value: Any, indent: String?) {
    when (value) {
        Null, is Boolean, is Long, is ULong -> append(value.toString())
        is Double -> TODO("serde_json writes f64 through ryu; a shortest round-trip f64 formatter is not ported")
        is String -> quoted(value)
        is List<*> -> container('[', ']', value.size, indent) { i, inner -> jsonValue(value[i]!!, inner) }
        is Map<*, *> -> {
            val keys = value.keys.map { it as String }.sortedWith { x, y -> codePointOrder(x, y) }
            container('{', '}', keys.size, indent) { i, inner ->
                quoted(keys[i])
                append(if (inner == null) ":" else ": ")
                jsonValue(value[keys[i]]!!, inner)
            }
        }
        else -> error("not a JSON value: ${value::class}")
    }
}

fun StringBuilder.container(open: Char, close: Char, size: Int, indent: String?, item: StringBuilder.(Int, String?) -> Unit) {
    append(open)
    val inner = indent?.let { "$it  " }
    for (i in 0 until size) {
        if (i > 0) append(',')
        if (inner != null) append('\n').append(inner)
        item(i, inner)
    }
    if (indent != null && size > 0) append('\n').append(indent)
    append(close)
}

fun StringBuilder.quoted(text: String) {
    append('"')
    for (c in text) when {
        c == '"' -> append("\\\"")
        c == '\\' -> append("\\\\")
        c == '\b' -> append("\\b")
        c == '\u000C' -> append("\\f")
        c == '\n' -> append("\\n")
        c == '\r' -> append("\\r")
        c == '\t' -> append("\\t")
        c.code < 0x20 -> append("\\u00").append("0123456789abcdef"[c.code shr 4]).append("0123456789abcdef"[c.code and 0xF])
        else -> append(c)
    }
    append('"')
}

/** UTF-8 byte order of two strings (Rust `String: Ord`), from their UTF-16 chars. */
fun codePointOrder(a: String, b: String): Int {
    for (i in 0 until minOf(a.length, b.length)) {
        if (a[i] != b[i]) return utf16Rank(a[i]) - utf16Rank(b[i])
    }
    return a.length - b.length
}

/** Surrogates rank above U+E000..U+FFFF, as their code points do. */
fun utf16Rank(c: Char): Int = c.code.let { if (it < 0xD800) it else if (it < 0xE000) it + 0x2000 else it - 0x800 }

/** serde_json `Deserializer<SliceRead>` reduced to `deserialize_any` into a `Value`. */
class JsonReader(val bytes: ByteArray, val unique: Boolean) {
    var at = 0
    var remainingDepth = 128
    var scratch = ByteArray(64)
    var scratchSize = 0

    fun peek(): Int = if (at < bytes.size) bytes[at].toInt() and 0xFF else -1

    fun next(): Int = peek().also { require(it >= 0); at++ }

    fun whitespace() {
        while (peek().let { it == 0x20 || it == 0x0A || it == 0x09 || it == 0x0D }) at++
    }

    fun value(): Any {
        whitespace()
        return when (peek()) {
            'n'.code -> ident("null", Null)
            't'.code -> ident("true", true)
            'f'.code -> ident("false", false)
            '-'.code -> { at++; parseInteger(false) }
            in DIGITS -> parseInteger(true)
            '"'.code -> { at++; string() }
            '['.code -> nested { array() }
            '{'.code -> nested { map() }
            else -> throw IllegalArgumentException()
        }
    }

    fun ident(text: String, value: Any): Any {
        for (c in text) require(next() == c.code)
        return value
    }

    fun <T> nested(container: () -> T): T {
        require(--remainingDepth != 0)
        at++
        return container().also { remainingDepth++ }
    }

    fun array(): List<Any> {
        val items = ArrayList<Any>()
        whitespace()
        if (peek() == ']'.code) { at++; return items }
        while (true) {
            items.add(value())
            whitespace()
            when (next()) {
                ','.code -> {}
                ']'.code -> return items
                else -> throw IllegalArgumentException()
            }
        }
    }

    fun map(): MutableMap<String, Any> {
        val entries = LinkedHashMap<String, Any>()
        whitespace()
        if (peek() == '}'.code) { at++; return entries }
        while (true) {
            whitespace()
            require(next() == '"'.code)
            val key = string()
            require(!unique || key !in entries)
            whitespace()
            require(next() == ':'.code)
            entries[key] = value()
            whitespace()
            when (next()) {
                ','.code -> {}
                '}'.code -> return entries
                else -> throw IllegalArgumentException()
            }
        }
    }

    fun string(): String {
        scratchSize = 0
        while (true) {
            val c = next()
            when {
                c == '"'.code -> break
                c == '\\'.code -> escape()
                else -> { require(c >= 0x20); push(c) }
            }
        }
        require(utf8(scratch, scratchSize))
        return scratch.decodeToString(0, scratchSize)
    }

    fun push(byte: Int) {
        if (scratchSize == scratch.size) scratch = scratch.copyOf(scratchSize * 2)
        scratch[scratchSize++] = byte.toByte()
    }

    fun escape() {
        when (next()) {
            '"'.code -> push('"'.code)
            '\\'.code -> push('\\'.code)
            '/'.code -> push('/'.code)
            'b'.code -> push(0x08)
            'f'.code -> push(0x0C)
            'n'.code -> push(0x0A)
            'r'.code -> push(0x0D)
            't'.code -> push(0x09)
            'u'.code -> {
                var n = hex4()
                require(n !in 0xDC00..0xDFFF)
                if (n in 0xD800..0xDBFF) {
                    require(next() == '\\'.code && next() == 'u'.code)
                    val low = hex4()
                    require(low in 0xDC00..0xDFFF)
                    n = 0x10000 + ((n - 0xD800) shl 10) + (low - 0xDC00)
                }
                when {
                    n < 0x80 -> push(n)
                    n < 0x800 -> { push(0xC0 or (n shr 6)); push(0x80 or (n and 0x3F)) }
                    n < 0x10000 -> { push(0xE0 or (n shr 12)); push(0x80 or ((n shr 6) and 0x3F)); push(0x80 or (n and 0x3F)) }
                    else -> {
                        push(0xF0 or (n shr 18)); push(0x80 or ((n shr 12) and 0x3F))
                        push(0x80 or ((n shr 6) and 0x3F)); push(0x80 or (n and 0x3F))
                    }
                }
            }
            else -> throw IllegalArgumentException()
        }
    }

    fun hex4(): Int {
        var n = 0
        repeat(4) {
            val c = next()
            n = n * 16 + when (c) {
                in DIGITS -> c - '0'.code
                in 'a'.code..'f'.code -> c - 'a'.code + 10
                in 'A'.code..'F'.code -> c - 'A'.code + 10
                else -> throw IllegalArgumentException()
            }
        }
        return n
    }

    fun parseInteger(positive: Boolean): Any {
        val first = next()
        if (first == '0'.code) {
            require(peek() !in DIGITS)
            return parseNumber(positive, 0uL)
        }
        require(first in DIGITS)
        var significand = (first - '0'.code).toULong()
        while (true) {
            val c = peek()
            if (c !in DIGITS) return parseNumber(positive, significand)
            val digit = (c - '0'.code).toULong()
            if (significand >= ULong.MAX_VALUE / 10uL && (significand > ULong.MAX_VALUE / 10uL || digit > ULong.MAX_VALUE % 10uL)) {
                return parseLongInteger(positive, significand)
            }
            at++
            significand = significand * 10uL + digit
        }
    }

    fun parseNumber(positive: Boolean, significand: ULong): Any = when (peek()) {
        '.'.code -> parseDecimal(positive, significand, 0)
        'e'.code, 'E'.code -> parseExponent(positive, significand, 0)
        else -> if (positive) {
            if (significand <= Long.MAX_VALUE.toULong()) significand.toLong() else significand
        } else {
            val negative = -significand.toLong()
            if (negative >= 0) -significand.toDouble() else negative
        }
    }

    fun parseDecimal(positive: Boolean, start: ULong, exponentBeforeDecimalPoint: Int): Double {
        at++
        var significand = start
        var exponentAfterDecimalPoint = 0
        while (true) {
            val c = peek()
            if (c !in DIGITS) break
            val digit = (c - '0'.code).toULong()
            if (significand >= ULong.MAX_VALUE / 10uL && (significand > ULong.MAX_VALUE / 10uL || digit > ULong.MAX_VALUE % 10uL)) {
                // parse_decimal_overflow: the digits past u64 are dropped.
                while (peek() in DIGITS) at++
                val exponent = exponentBeforeDecimalPoint + exponentAfterDecimalPoint
                return if (peek() == 'e'.code || peek() == 'E'.code) parseExponent(positive, significand, exponent)
                else f64FromParts(positive, significand, exponent)
            }
            at++
            significand = significand * 10uL + digit
            exponentAfterDecimalPoint -= 1
        }
        require(exponentAfterDecimalPoint != 0)
        val exponent = exponentBeforeDecimalPoint + exponentAfterDecimalPoint
        return if (peek() == 'e'.code || peek() == 'E'.code) parseExponent(positive, significand, exponent)
        else f64FromParts(positive, significand, exponent)
    }

    fun parseExponent(positive: Boolean, significand: ULong, startingExponent: Int): Double {
        at++
        val positiveExponent = when (peek()) {
            '+'.code -> { at++; true }
            '-'.code -> { at++; false }
            else -> true
        }
        val first = next()
        require(first in DIGITS)
        var exp = first - '0'.code
        while (true) {
            val c = peek()
            if (c !in DIGITS) break
            at++
            val digit = c - '0'.code
            if (exp >= Int.MAX_VALUE / 10 && (exp > Int.MAX_VALUE / 10 || digit > Int.MAX_VALUE % 10)) {
                // parse_exponent_overflow: an error instead of an infinity, zero below the range.
                require(significand == 0uL || !positiveExponent)
                while (peek() in DIGITS) at++
                return if (positive) 0.0 else -0.0
            }
            exp = exp * 10 + digit
        }
        val total = if (positiveExponent) startingExponent.toLong() + exp else startingExponent.toLong() - exp
        return f64FromParts(positive, significand, total.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
    }

    fun parseLongInteger(positive: Boolean, significand: ULong): Double {
        var exponent = 0
        while (true) when (peek()) {
            in DIGITS -> { at++; exponent += 1 }
            '.'.code -> return parseDecimal(positive, significand, exponent)
            'e'.code, 'E'.code -> return parseExponent(positive, significand, exponent)
            else -> return f64FromParts(positive, significand, exponent)
        }
    }

    /** `f64_from_parts` without `float_roundtrip`: the significand scaled through [POW10]. */
    fun f64FromParts(positive: Boolean, significand: ULong, startingExponent: Int): Double {
        var f = significand.toDouble()
        var exponent = startingExponent
        while (true) {
            val index = if (exponent < 0) -exponent else exponent
            if (index in 0..308) {
                if (exponent >= 0) {
                    f *= POW10[index]
                    require(!f.isInfinite())
                } else f /= POW10[index]
                break
            }
            if (f == 0.0) break
            require(exponent < 0)
            f /= 1e308
            exponent += 308
        }
        return if (positive) f else -f
    }

    companion object {
        val DIGITS = '0'.code..'9'.code

        val POW10 = doubleArrayOf(
            1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9,
            1e10, 1e11, 1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19,
            1e20, 1e21, 1e22, 1e23, 1e24, 1e25, 1e26, 1e27, 1e28, 1e29,
            1e30, 1e31, 1e32, 1e33, 1e34, 1e35, 1e36, 1e37, 1e38, 1e39,
            1e40, 1e41, 1e42, 1e43, 1e44, 1e45, 1e46, 1e47, 1e48, 1e49,
            1e50, 1e51, 1e52, 1e53, 1e54, 1e55, 1e56, 1e57, 1e58, 1e59,
            1e60, 1e61, 1e62, 1e63, 1e64, 1e65, 1e66, 1e67, 1e68, 1e69,
            1e70, 1e71, 1e72, 1e73, 1e74, 1e75, 1e76, 1e77, 1e78, 1e79,
            1e80, 1e81, 1e82, 1e83, 1e84, 1e85, 1e86, 1e87, 1e88, 1e89,
            1e90, 1e91, 1e92, 1e93, 1e94, 1e95, 1e96, 1e97, 1e98, 1e99,
            1e100, 1e101, 1e102, 1e103, 1e104, 1e105, 1e106, 1e107, 1e108, 1e109,
            1e110, 1e111, 1e112, 1e113, 1e114, 1e115, 1e116, 1e117, 1e118, 1e119,
            1e120, 1e121, 1e122, 1e123, 1e124, 1e125, 1e126, 1e127, 1e128, 1e129,
            1e130, 1e131, 1e132, 1e133, 1e134, 1e135, 1e136, 1e137, 1e138, 1e139,
            1e140, 1e141, 1e142, 1e143, 1e144, 1e145, 1e146, 1e147, 1e148, 1e149,
            1e150, 1e151, 1e152, 1e153, 1e154, 1e155, 1e156, 1e157, 1e158, 1e159,
            1e160, 1e161, 1e162, 1e163, 1e164, 1e165, 1e166, 1e167, 1e168, 1e169,
            1e170, 1e171, 1e172, 1e173, 1e174, 1e175, 1e176, 1e177, 1e178, 1e179,
            1e180, 1e181, 1e182, 1e183, 1e184, 1e185, 1e186, 1e187, 1e188, 1e189,
            1e190, 1e191, 1e192, 1e193, 1e194, 1e195, 1e196, 1e197, 1e198, 1e199,
            1e200, 1e201, 1e202, 1e203, 1e204, 1e205, 1e206, 1e207, 1e208, 1e209,
            1e210, 1e211, 1e212, 1e213, 1e214, 1e215, 1e216, 1e217, 1e218, 1e219,
            1e220, 1e221, 1e222, 1e223, 1e224, 1e225, 1e226, 1e227, 1e228, 1e229,
            1e230, 1e231, 1e232, 1e233, 1e234, 1e235, 1e236, 1e237, 1e238, 1e239,
            1e240, 1e241, 1e242, 1e243, 1e244, 1e245, 1e246, 1e247, 1e248, 1e249,
            1e250, 1e251, 1e252, 1e253, 1e254, 1e255, 1e256, 1e257, 1e258, 1e259,
            1e260, 1e261, 1e262, 1e263, 1e264, 1e265, 1e266, 1e267, 1e268, 1e269,
            1e270, 1e271, 1e272, 1e273, 1e274, 1e275, 1e276, 1e277, 1e278, 1e279,
            1e280, 1e281, 1e282, 1e283, 1e284, 1e285, 1e286, 1e287, 1e288, 1e289,
            1e290, 1e291, 1e292, 1e293, 1e294, 1e295, 1e296, 1e297, 1e298, 1e299,
            1e300, 1e301, 1e302, 1e303, 1e304, 1e305, 1e306, 1e307, 1e308,
        )

        /** Rust `str::from_utf8`: the well-formed sequences of Unicode table 3-7. */
        fun utf8(bytes: ByteArray, size: Int): Boolean {
            var i = 0
            while (i < size) {
                val lead = bytes[i].toInt() and 0xFF
                if (lead < 0x80) { i++; continue }
                val trail: Int
                var low = 0x80
                var high = 0xBF
                when (lead) {
                    in 0xC2..0xDF -> trail = 1
                    0xE0 -> { trail = 2; low = 0xA0 }
                    0xED -> { trail = 2; high = 0x9F }
                    in 0xE1..0xEF -> trail = 2
                    0xF0 -> { trail = 3; low = 0x90 }
                    0xF4 -> { trail = 3; high = 0x8F }
                    in 0xF1..0xF3 -> trail = 3
                    else -> return false
                }
                if (i + trail >= size) return false
                if ((bytes[i + 1].toInt() and 0xFF) !in low..high) return false
                for (k in 2..trail) if ((bytes[i + k].toInt() and 0xFF) !in 0x80..0xBF) return false
                i += trail + 1
            }
            return true
        }
    }
}
