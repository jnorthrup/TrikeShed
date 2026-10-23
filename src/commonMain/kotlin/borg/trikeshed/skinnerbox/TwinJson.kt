package borg.trikeshed.skinnerbox

import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyStrict
import borg.trikeshed.parse.confix.*

/** Exact decimal text when a JSON number cannot be represented by Long or ULong. */
class TwinNumber private constructor(val text: String) {
    override fun toString(): String = text
    override fun equals(other: Any?): Boolean = other is TwinNumber && text == other.text
    override fun hashCode(): Int = text.hashCode()

    companion object {
        fun of(text: String): TwinNumber = TwinNumber(TwinJson.number(text))
    }
}

/** Strict JSON through the existing Confix index; its lossy Double reification never supplies values. */
object TwinJson {
    const val MAX_BYTES = 128 * 1024
    const val MAX_VALUES = 2048
    const val MAX_DEPTH = 16
    private val numberPattern = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    fun parse(bytes: ByteArray, limit: Int = MAX_BYTES): Any? {
        require(limit in 1..MAX_BYTES && bytes.size in 1..limit) { "twin JSON byte bound" }
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        // Bound nesting before the general grammar validator or Confix recursion can allocate a tree.
        var depth = 0
        var separators = 0
        var quoted = false
        var escaped = false
        for (char in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= MAX_DEPTH + 1) { "twin JSON depth bound" } }
                '}', ']' -> depth--
                ',', ':' -> { separators++; require(separators <= MAX_VALUES * 2) { "twin JSON complexity bound" } }
            }
        }
        // This validates RFC 8259 grammar; the returned floating-point projection is intentionally discarded.
        reifyStrict(text)
        val doc = confixDoc(bytes, Syntax.JSON)
        require(doc.roots.size == 1) { "twin JSON must have one Confix root" }
        var remaining = MAX_VALUES
        fun read(row: RowVec, level: Int): Any? {
            require(level <= MAX_DEPTH && remaining-- > 0) { "twin JSON complexity bound" }
            val children = row.kids
            return when (row.tag) {
                IOMemento.IoObject -> {
                    require(children.size % 2 == 0) { "twin Confix object shape" }
                    val values = linkedMapOf<String, Any?>()
                    for (i in 0 until children.size step 2) {
                        val keyRow = children[i]
                        require(keyRow.tag == IOMemento.IoString && keyRow.kids.size == 0) { "twin JSON object key" }
                        val key = keyRow.reify(doc.src) as String
                        unicode(key)
                        require(!values.containsKey(key)) { "duplicate twin JSON key" }
                        values[key] = read(children[i + 1], level + 1)
                    }
                    values
                }
                IOMemento.IoArray -> List(children.size) { read(children[it], level + 1) }
                IOMemento.IoString -> (row.reify(doc.src) as String).also(::unicode)
                IOMemento.IoDouble, IOMemento.IoInt, IOMemento.IoLong -> {
                    val canonical = number(bytes.decodeToString(row.open, row.close + 1))
                    canonical.toLongOrNull() ?: canonical.toULongOrNull() ?: TwinNumber.of(canonical)
                }
                IOMemento.IoBoolean -> bytes[row.open] == 't'.code.toByte()
                IOMemento.IoNothing -> null
                else -> error("unsupported twin JSON Confix tag")
            }
        }
        return read(requireNotNull(doc.root), 0)
    }

    fun encode(value: Any?, limit: Int = MAX_BYTES): ByteArray {
        require(limit in 1..MAX_BYTES) { "twin JSON byte bound" }
        val out = StringBuilder()
        var remaining = MAX_VALUES
        fun emit(value: Any?, level: Int) {
            require(level <= MAX_DEPTH && remaining-- > 0) { "twin JSON complexity bound" }
            when (value) {
                null -> out.append("null")
                is String -> { require(value.length <= limit) { "twin JSON byte bound" }; unicode(value); out.append(jsonOf(value)) }
                is Boolean -> out.append(value)
                is TwinNumber -> out.append(value.text)
                is Byte, is Short, is Int, is Long, is UByte, is UShort, is UInt, is ULong -> out.append(value.toString())
                is Float -> { require(value.isFinite()); out.append(number(value.toString())) }
                is Double -> { require(value.isFinite()); out.append(number(value.toString())) }
                is Map<*, *> -> {
                    require(value.size <= remaining) { "twin JSON complexity bound" }
                    require(value.keys.all { it is String }) { "twin JSON object keys must be strings" }
                    val keys = value.keys.map { it as String }.sorted()
                    out.append('{')
                    for ((i, key) in keys.withIndex()) {
                        if (i > 0) out.append(',')
                        require(key.length <= limit) { "twin JSON key bound" }
                        unicode(key)
                        out.append(jsonOf(key)).append(':')
                        emit(value[key], level + 1)
                    }
                    out.append('}')
                }
                is List<*> -> {
                    require(value.size <= remaining) { "twin JSON complexity bound" }
                    out.append('[')
                    for ((i, item) in value.withIndex()) {
                        if (i > 0) out.append(',')
                        emit(item, level + 1)
                    }
                    out.append(']')
                }
                else -> error("unsupported twin JSON value")
            }
            require(out.length <= limit) { "twin JSON byte bound" }
        }
        emit(value, 0)
        return out.toString().encodeToByteArray().also { require(it.size <= limit) { "twin JSON byte bound" } }
    }

    /** Mathematical decimal normalization, exact even beyond binary64 precision. No exponent-sized allocation. */
    internal fun number(text: String): String {
        require(text.length <= MAX_BYTES && text.matches(numberPattern)) { "invalid twin JSON number" }
        val negative = text.startsWith('-')
        val unsigned = text.removePrefix("-")
        val exponentAt = unsigned.indexOfFirst { it == 'e' || it == 'E' }
        val mantissa = if (exponentAt < 0) unsigned else unsigned.substring(0, exponentAt)
        val exponent = if (exponentAt < 0) 0 else requireNotNull(unsigned.substring(exponentAt + 1).toIntOrNull()) { "twin JSON exponent bound" }
        require(exponent in -1_000_000..1_000_000) { "twin JSON exponent bound" }
        val dot = mantissa.indexOf('.')
        val fraction = if (dot < 0) 0 else mantissa.length - dot - 1
        val coefficient = mantissa.replace(".", "").trimStart('0')
        if (coefficient.isEmpty()) return "0"
        val digits = coefficient.trimEnd('0')
        val scale = exponent - fraction + coefficient.length - digits.length
        val point = digits.length + scale
        val body = when {
            scale >= 0 && point <= 21 -> digits + "0".repeat(scale)
            point in 1..21 -> digits.take(point) + "." + digits.drop(point)
            point in -6..0 -> "0." + "0".repeat(-point) + digits
            else -> digits.first() + (if (digits.length > 1) "." + digits.drop(1) else "") + "e" + (point - 1)
        }
        return if (negative) "-$body" else body
    }

    private fun unicode(text: String) {
        var i = 0
        while (i < text.length) {
            val char = text[i++]
            if (char in '\uD800'..'\uDBFF') {
                require(i < text.length && text[i] in '\uDC00'..'\uDFFF') { "unpaired twin JSON surrogate" }
                i++
            } else require(char !in '\uDC00'..'\uDFFF') { "unpaired twin JSON surrogate" }
        }
    }
}
