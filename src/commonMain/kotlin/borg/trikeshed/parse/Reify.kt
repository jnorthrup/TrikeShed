package borg.trikeshed.parse

import borg.trikeshed.collections.associative.trie.LinearArrayMap
import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*

/**
 * Confix reification of one JSON value straight off the flat index ([ConfixIndexK.Spans],
 * [ConfixIndexK.Tags], [ConfixIndexK.DirectChildren]); no tree cursor. Objects come back as
 * [LinearArrayMap] in source order, arrays as lists, numbers as Double.
 */
fun reify(json: CharSequence): Any? = reify(utf8(json))

/** [reify] over UTF-8 JSON bytes as they arrive off the wire; no text decode first. */
fun reify(bytes: ByteArray): Any? {
    val src: Series<Byte> = bytes.size j { bytes[it] }
    val index = Syntax.JSON.scanIndex(src)
    val spans = index.facet(ConfixIndexK.Spans)
    val tags = index.facet(ConfixIndexK.Tags)
    val childOf = index.facet(ConfixIndexK.DirectChildren)
    if (spans.size == 0) return null

    fun value(k: Int): Any? {
        val span = spans[k]
        return when (tags[k]) {
            IOMemento.IoObject -> {
                val kids = childOf(k)
                val pairs = kids.size / 2
                val keys = arrayOfNulls<String>(pairs); val values = arrayOfNulls<Any?>(pairs)
                for (p in 0 until pairs) {
                    val key = spans[kids[2 * p]]
                    keys[p] = decodeTextSpan(src, key.a + 1, key.b - 1)
                    values[p] = value(kids[2 * p + 1])
                }
                @Suppress("UNCHECKED_CAST")
                LinearArrayMap(keys as Array<String>, values)
            }
            IOMemento.IoArray -> { val kids = childOf(k); List(kids.size) { value(kids[it]) } }
            IOMemento.IoString -> decodeTextSpan(src, span.a + 1, span.b - 1)
            IOMemento.IoDouble -> bytes.decodeToString(span.a, span.b + 1).toDoubleOrNull()
            IOMemento.IoBoolean -> bytes[span.a] == 't'.code.toByte()
            else -> null
        }
    }
    return value(0)
}

/** JSON text of [value]: maps, iterables, arrays, strings, numbers, booleans, null; anything else as its string. */
fun jsonOf(value: Any?): String = StringBuilder().also { jsonOf(value, it) }.toString()

fun jsonOf(value: Any?, out: StringBuilder) {
    when (value) {
        null -> out.append("null")
        is CharSequence -> {
            out.append('"')
            for (char in value) when (char) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (char.code < 0x20) out.append("\\u").append(char.code.toString(16).padStart(4, '0')) else out.append(char)
            }
            out.append('"')
        }
        is Number, is Boolean -> out.append(value.toString())
        is Map<*, *> -> {
            out.append('{')
            var first = true
            for ((key, item) in value.entries) {
                if (!first) out.append(", ")
                first = false
                jsonOf(key.toString(), out); out.append(':'); jsonOf(item, out)
            }
            out.append('}')
        }
        is Iterable<*> -> elements(value.iterator(), out)
        is Array<*> -> elements(value.iterator(), out)
        else -> jsonOf(value.toString(), out)
    }
}

private fun elements(items: Iterator<*>, out: StringBuilder) {
    out.append('[')
    var first = true
    for (item in items) { if (!first) out.append(", "); first = false; jsonOf(item, out) }
    out.append(']')
}

/** [reify] of a JSON object. */
fun reifyMap(json: CharSequence): Map<String, Any?> = objectOf(reify(json))

/** [reifyMap] over UTF-8 JSON bytes. */
fun reifyMap(bytes: ByteArray): Map<String, Any?> = objectOf(reify(bytes))

private fun objectOf(value: Any?): Map<String, Any?> {
    require(value is Map<*, *>) { "Expected JSON object but got ${value?.let { it::class.simpleName } ?: "null"}" }
    @Suppress("UNCHECKED_CAST")
    return value as Map<String, Any?>
}

/** [reify], after checking [json] is exactly one complete JSON value (RFC 8259 grammar, nesting ≤ 256). */
fun reifyStrict(json: CharSequence): Any? { conform(json); return reify(json) }

private fun conform(src: CharSequence) {
    var offset = 0
    fun peek(): Char? = if (offset < src.length) src[offset] else null
    fun whitespace() { while (peek() == ' ' || peek() == '\t' || peek() == '\r' || peek() == '\n') offset++ }
    fun expect(char: Char) { require(peek() == char) { "Expected '$char' at JSON offset $offset" }; offset++ }
    fun string() {
        expect('"')
        while (true) {
            val char = peek() ?: error("Unterminated JSON string at offset $offset")
            offset++
            when {
                char == '"' -> return
                char == '\\' -> {
                    val escape = peek() ?: error("Unterminated JSON escape at offset $offset")
                    offset++
                    if (escape == 'u') repeat(4) {
                        val hex = peek()
                        require(hex != null && (hex in '0'..'9' || hex in 'a'..'f' || hex in 'A'..'F')) { "Invalid JSON Unicode escape at offset $offset" }
                        offset++
                    } else require(escape in "\"\\/bfnrt") { "Invalid JSON escape at offset ${offset - 1}" }
                }
                char.code < 0x20 -> error("Unescaped JSON control character at offset ${offset - 1}")
            }
        }
    }
    fun digits() { require(peek() in '0'..'9') { "Expected JSON digit at offset $offset" }; while (peek() in '0'..'9') offset++ }
    fun number() {
        if (peek() == '-') offset++
        if (peek() == '0') offset++ else digits()
        if (peek() == '.') { offset++; digits() }
        if (peek() == 'e' || peek() == 'E') { offset++; if (peek() == '+' || peek() == '-') offset++; digits() }
    }
    fun value(depth: Int) {
        require(depth <= 256) { "JSON nesting exceeds 256 levels" }
        whitespace()
        when (peek()) {
            '{', '[' -> {
                val objectValue = peek() == '{'; val close = if (objectValue) '}' else ']'
                offset++; whitespace()
                if (peek() == close) { offset++; return }
                while (true) {
                    if (objectValue) { string(); whitespace(); expect(':') }
                    value(depth + 1); whitespace()
                    if (peek() == close) { offset++; return }
                    expect(','); whitespace()
                }
            }
            '"' -> string()
            't', 'f', 'n' -> for (char in when (peek()) { 't' -> "true"; 'f' -> "false"; else -> "null" }) expect(char)
            '-', in '0'..'9' -> number()
            else -> error("Expected JSON value at offset $offset")
        }
    }
    value(0); whitespace()
    require(offset == src.length) { "Trailing JSON input at offset $offset" }
}

/** UTF-8 bytes of [text] in one pass over its chars (surrogate pairs as 4-byte sequences); no String copy. */
fun utf8(text: CharSequence): ByteArray {
    val n = text.length
    var size = 0; var i = 0
    while (i < n) {
        val c = text[i++].code
        size += when {
            c < 0x80 -> 1
            c < 0x800 -> 2
            c in 0xD800..0xDBFF && i < n && text[i].code in 0xDC00..0xDFFF -> { i++; 4 }
            else -> 3
        }
    }
    val out = ByteArray(size); var o = 0; i = 0
    while (i < n) {
        var c = text[i++].code
        when {
            c < 0x80 -> out[o++] = c.toByte()
            c < 0x800 -> { out[o++] = (0xC0 or (c ushr 6)).toByte(); out[o++] = (0x80 or (c and 0x3F)).toByte() }
            c in 0xD800..0xDBFF && i < n && text[i].code in 0xDC00..0xDFFF -> {
                c = 0x10000 + ((c - 0xD800) shl 10) + (text[i++].code - 0xDC00)
                out[o++] = (0xF0 or (c ushr 18)).toByte(); out[o++] = (0x80 or ((c ushr 12) and 0x3F)).toByte()
                out[o++] = (0x80 or ((c ushr 6) and 0x3F)).toByte(); out[o++] = (0x80 or (c and 0x3F)).toByte()
            }
            else -> {
                out[o++] = (0xE0 or (c ushr 12)).toByte(); out[o++] = (0x80 or ((c ushr 6) and 0x3F)).toByte()
                out[o++] = (0x80 or (c and 0x3F)).toByte()
            }
        }
    }
    return out
}
