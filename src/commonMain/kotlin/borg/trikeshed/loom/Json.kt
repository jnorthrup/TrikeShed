package borg.trikeshed.loom

import borg.trikeshed.lib.*

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
 * refuses a repeated object key at any depth (loomctl `strict::Strict`); otherwise the last wins, and
 * [repeated] collects each object a key repeated in with the value it replaced: a derived struct refuses
 * the object, a map still deserializes the replaced value.
 */
fun json(bytes: ByteArray, unique: Boolean, repeated: MutableList<Join<Map<String, Any>, Any>>? = null): Any? = try {
    JsonReader(bytes, unique, repeated).run {
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
        // Serializer::serialize_f64: NaN and the infinities as null, every other value through zmij.
        is Double -> append(if (value.isFinite()) zmij.format_finite(value) else "null")
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

/**
 * zmij 1.0.23, the float formatter serde_json 1.0.151's `write_f64` prints every finite f64 with: the shortest decimal
 * that rounds back to the value, by Schubfach with Xiang JunBo's extra-digit scaling. The portable path of `write` and
 * its compressed power-of-ten table; the SIMD digit paths print the same bytes.
 */
object zmij {
    /**
     * `Buffer::format_finite` for an f64: fixed notation for decimal exponents -5..=15 ("0.0", "-0.0", "0.00001",
     * "1000000000000000.0"), otherwise `d.ddde±x` ("1e+16", "1.5e-7", "5e-324").
     */
    fun format_finite(value: Double): String {
        val bits = value.toRawBits().toULong()
        val bin_exp = (bits shl 1 shr 53).toLong()
        val bin_sig = bits and (IMPLICIT_BIT - 1uL)
        val out = StringBuilder(24)
        if (bits shr 63 != 0uL) out.append('-')
        var dec: ToDecimalResult
        if (bin_exp == 0L) {
            if (bin_sig == 0uL) return out.append("0.0").toString()
            dec = to_decimal(bin_sig, 1, true)
            var dec_sig = dec.sig * 10 + if (dec.has_last_digit) dec.last_digit else 0
            var dec_exp = dec.exp
            while (dec_sig < THRESHOLD) {
                dec_sig *= 10
                dec_exp -= 1
            }
            val last_digit = (dec_sig % 10).toInt()
            dec = ToDecimalResult(dec_sig / 10, dec_exp, last_digit, last_digit != 0)
        } else dec = to_decimal(bin_sig or IMPLICIT_BIT, bin_exp, bin_sig != 0uL)
        val has_extra_digit = dec.sig >= THRESHOLD
        var dec_exp = dec.exp + 17 - 2 + if (has_extra_digit) 1 else 0
        // The significand's 16-digit BCD, written one place early when it has 15 digits, then the last digit or none.
        val bcd = dec.sig.toString().padStart(16, '0').let { if (has_extra_digit) it else it.substring(1) }
        val digits = if (dec.has_last_digit) bcd + ('0' + dec.last_digit) else bcd.trimEnd('0')
        val length = digits.length
        if (dec_exp in -5..15) {
            when {
                // 1234e7 -> 12340000000.0
                length - 1 <= dec_exp -> out.append(digits).append("0".repeat(dec_exp + 1 - length)).append(".0")
                // 1234e-2 -> 12.34
                0 <= dec_exp -> out.append(digits.substring(0, dec_exp + 1)).append('.').append(digits.substring(dec_exp + 1))
                // 1234e-6 -> 0.001234
                else -> out.append("0.").append("0".repeat(-dec_exp - 1)).append(digits)
            }
            return out.toString()
        }
        // 1234e30 -> 1.234e+33
        out.append(digits[0])
        if (length > 1) out.append('.').append(digits.substring(1))
        out.append('e').append(if (dec_exp >= 0) '+' else '-')
        if (dec_exp < 0) dec_exp = -dec_exp
        return out.append(dec_exp.toString()).toString()
    }

    /** f64 `IMPLICIT_BIT`, 2^52. */
    const val IMPLICIT_BIT: ULong = 0x10000000000000uL

    /** f64 `EXP_OFFSET`, the exponent bias plus the 52 significand bits. */
    const val EXP_OFFSET: Long = 1075

    /** `Data::threshold`: a significand at least this large has the extra (16th) digit. */
    const val THRESHOLD: Long = 1_000_000_000_000_000

    /** `ExpShiftTable::EXTRA_SHIFT`. */
    const val EXTRA_SHIFT: Int = 6

    /** zmij `uint128`. */
    class uint128(val hi: ULong, val lo: ULong)

    /** zmij `ToDecimalResult`: [sig] and, when [has_last_digit], one more digit, times 10^([exp] + 1 - 17 + 2). */
    class ToDecimalResult(val sig: Long, val exp: Int, val last_digit: Int, val has_last_digit: Boolean)

    /** The high 64 bits of the 128-bit product of [x] and [y]. */
    fun umul128_hi64(x: ULong, y: ULong): ULong {
        val x0 = x and 0xffffffffuL
        val x1 = x shr 32
        val y0 = y and 0xffffffffuL
        val y1 = y shr 32
        val p01 = x0 * y1
        val p10 = x1 * y0
        val middle = (x0 * y0 shr 32) + (p01 and 0xffffffffuL) + (p10 and 0xffffffffuL)
        return x1 * y1 + (p01 shr 32) + (p10 shr 32) + (middle shr 32)
    }

    /** ([x] * [y] + [c]) >> 64. */
    fun umul128_add_hi64(x: ULong, y: ULong, c: ULong): ULong {
        val lo = x * y
        return umul128_hi64(x, y) + if (lo + c < lo) 1uL else 0uL
    }

    fun umul192_hi128(x_hi: ULong, x_lo: ULong, y: ULong): uint128 {
        val p_lo = x_hi * y
        val lo = p_lo + umul128_hi64(x_lo, y)
        return uint128(umul128_hi64(x_hi, y) + if (lo < p_lo) 1uL else 0uL, lo)
    }

    /** floor(log10(2^[bin_exp])) if [regular], else floor(log10(3/4 * 2^[bin_exp])). */
    fun compute_dec_exp(bin_exp: Int, regular: Boolean): Int = (bin_exp * 315_653 - (if (regular) 0 else 131_072)) shr 20

    /** The shift that gives the scaled significand a fixed 128-bit fractional part, as zmij's u8 arithmetic wraps it. */
    fun compute_exp_shift(bin_exp: Int, dec_exp: Int): Int = (bin_exp + ((-dec_exp * 217_707) shr 16) + 1) and 0xff

    val POW10_MINOR: ULongArray = ulongArrayOf(
        0x8000000000000000uL, 0xa000000000000000uL, 0xc800000000000000uL,
        0xfa00000000000000uL, 0x9c40000000000000uL, 0xc350000000000000uL,
        0xf424000000000000uL, 0x9896800000000000uL, 0xbebc200000000000uL,
        0xee6b280000000000uL, 0x9502f90000000000uL, 0xba43b74000000000uL,
        0xe8d4a51000000000uL, 0x9184e72a00000000uL, 0xb5e620f480000000uL,
        0xe35fa931a0000000uL, 0x8e1bc9bf04000000uL, 0xb1a2bc2ec5000000uL,
        0xde0b6b3a76400000uL, 0x8ac7230489e80000uL, 0xad78ebc5ac620000uL,
        0xd8d726b7177a8000uL, 0x878678326eac9000uL, 0xa968163f0a57b400uL,
        0xd3c21bcecceda100uL, 0x84595161401484a0uL, 0xa56fa5b99019a5c8uL,
        0xcecb8f27f4200f3auL,
    )

    /** `POW10_MAJOR`: hi, lo of every 28th power of ten from 10^-303. */
    val POW10_MAJOR: ULongArray = ulongArrayOf(
        0xaf8e5410288e1b6fuL, 0x07ecf0ae5ee44ddauL, 0xb1442798f49ffb4auL, 0x99cd11cfdf41779duL,
        0xb2fe3f0b8599ef07uL, 0x861fa7e6dcb4aa15uL, 0xb4bca50b065abe63uL, 0x0fed077a756b53aauL,
        0xb67f6455292cbf08uL, 0x1a3bc84c17b1d543uL, 0xb84687c269ef3bfbuL, 0x3d5d514f40eea742uL,
        0xba121a4650e4ddebuL, 0x92f34d62616ce413uL, 0xbbe226efb628afeauL, 0x890489f70a55368cuL,
        0xbdb6b8e905cb600fuL, 0x5400e987bbc1c921uL, 0xbf8fdb78849a5f96uL, 0xde98520472bdd034uL,
        0xc16d9a0095928a27uL, 0x75b7053c0f178294uL, 0xc350000000000000uL, 0x0000000000000000uL,
        0xc5371912364ce305uL, 0x6c28000000000000uL, 0xc722f0ef9d80aad6uL, 0x424d3ad2b7b97ef6uL,
        0xc913936dd571c84cuL, 0x03bc3a19cd1e38eauL, 0xcb090c8001ab551cuL, 0x5cadf5bfd3072cc6uL,
        0xcd036837130890a1uL, 0x36dba887c37a8c10uL, 0xcf02b2c21207ef2euL, 0x94f967e45e03f4bcuL,
        0xd106f86e69d785c7uL, 0xe13336d701beba52uL, 0xd31045a8341ca07cuL, 0x1ede48111209a051uL,
        0xd51ea6fa85785631uL, 0x552a74227f3ea566uL, 0xd732290fbacaf133uL, 0xa97c177947ad4096uL,
        0xd94ad8b1c7380874uL, 0x18375281ae7822bcuL,
    )

    val POW10_FIXUPS: UIntArray = uintArrayOf(
        0x0a4e363fu, 0x00001840u, 0x00006400u, 0x24200040u, 0x00000000u,
        0x0c000000u, 0x82c81380u, 0x5e4ce01fu, 0xd730f60fu, 0x0000001bu,
        0x00000000u, 0xcdf7fffcu, 0x6e8201d8u, 0x40cd3fd1u, 0xdb642501u,
        0x00000d0du, 0x14042400u, 0x53713840u, 0x11781db4u, 0x00000000u,
    )

    /**
     * `Pow10SignificandTable::get_unchecked`: the 128-bit significand of 10^[dec_exp] rounded down, computed from the
     * minor and major powers by Dougall Johnson's method as the compressed table does (the full table holds these values).
     */
    fun pow10_significand(dec_exp: Int): uint128 {
        val i = dec_exp + 293
        val m = POW10_MINOR[(i + 10) % POW10_MINOR.size]
        val h_hi = POW10_MAJOR[(i + 10) / POW10_MINOR.size * 2]
        val h_lo = POW10_MAJOR[(i + 10) / POW10_MINOR.size * 2 + 1]
        val h1 = umul128_hi64(h_lo, m)
        val c0 = h_lo * m
        val c1 = h1 + h_hi * m
        val c2 = (if (c1 < h1) 1uL else 0uL) + umul128_hi64(h_hi, m)
        val fixup = ((POW10_FIXUPS[i shr 5] shr (i and 31)) and 1u).toULong()
        return if (c2 shr 63 != 0uL) uint128(c2, c1 - fixup)
        else uint128((c2 shl 1) or (c1 shr 63), ((c1 shl 1) or (c0 shr 63)) - fixup)
    }

    /** zmij `to_decimal` for an f64 significand [bin_sig] and biased exponent [raw_exp]. */
    fun to_decimal(bin_sig: ULong, raw_exp: Long, regular: Boolean): ToDecimalResult {
        val bin_exp = (raw_exp - EXP_OFFSET).toInt()
        if (!regular) {
            val dec_exp = compute_dec_exp(bin_exp, false)
            val shift = (compute_exp_shift(bin_exp, dec_exp + 1) + EXTRA_SHIFT) and 0xff
            val pow10 = pow10_significand(-dec_exp - 1)
            val p = umul192_hi128(pow10.hi, pow10.lo, bin_sig shl shift)
            var integral = p.hi shr EXTRA_SHIFT
            val fractional = (p.hi shl (64 - EXTRA_SHIFT)) or (p.lo shr EXTRA_SHIFT)
            val half_ulp = pow10.hi shr (EXTRA_SHIFT + 1 - shift)
            val round_up = half_ulp > ULong.MAX_VALUE - fractional
            val round_down = (half_ulp shr 1) > fractional
            if (round_up) integral += 1uL
            var digit = umul128_add_hi64(fractional, 10uL, (1uL shl 63) - 1uL).toInt()
            val lo = umul128_add_hi64(fractional - (half_ulp shr 1), 10uL, ULong.MAX_VALUE).toInt()
            if (digit < lo) digit = lo
            return ToDecimalResult(integral.toLong(), dec_exp, digit, !(round_up || round_down))
        }
        val dec_exp = compute_dec_exp(bin_exp, true)
        val shift = (compute_exp_shift(bin_exp, dec_exp + 1) + EXTRA_SHIFT) and 0xff
        val even = 1uL - (bin_sig and 1uL)
        val pow10 = pow10_significand(-dec_exp - 1)
        val p = umul192_hi128(pow10.hi, pow10.lo, bin_sig shl shift)
        var integral = p.hi shr EXTRA_SHIFT
        val fractional = (p.hi shl (64 - EXTRA_SHIFT)) or (p.lo shr EXTRA_SHIFT)
        val half_ulp = (pow10.hi shr (EXTRA_SHIFT + 1 - shift)) + even
        val round_up = fractional + half_ulp < fractional
        val round_down = half_ulp > fractional
        if (round_up) integral += 1uL
        // Data::biased_half: +6 for boundary cases found by verify.py.
        var digit = umul128_add_hi64(fractional, 10uL, (1uL shl 63) + 6uL).toInt()
        if (fractional == 1uL shl 62) digit = 2 // Round 2.5 to 2.
        return ToDecimalResult(integral.toLong(), dec_exp, digit, !(round_up || round_down))
    }
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
class JsonReader(val bytes: ByteArray, val unique: Boolean, val repeated: MutableList<Join<Map<String, Any>, Any>>? = null) {
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
            entries.put(key, value())?.let { replaced -> repeated?.add(entries j replaced) }
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
