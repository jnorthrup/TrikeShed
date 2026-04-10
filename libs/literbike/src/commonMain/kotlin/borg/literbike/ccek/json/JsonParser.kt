package borg.literbike.ccek.json

/**
 * JsonParser - Port of TrikeShed Json.kt
 *
 * JsElement: (openIdx, closeIdx) x commaIndices
 * JsIndex: bounds x source chars
 * JsContext: JsElement x source chars
 */

/** JsElement: (openIdx, closeIdx) with comma indices */
data class JsElement(
    val openIdx: Int,
    val closeIdx: Int,
    val commaIdxs: List<Int>,
) {
    companion object {
        fun create(openIdx: Int, closeIdx: Int, commaIdxs: List<Int>): JsElement {
            return JsElement(openIdx, closeIdx, commaIdxs)
        }
    }
}

/** JsIndex: bounds with source reference */
data class JsIndex(
    val start: Int,
    val end: Int,
    val src: String,
) {
    /** Get segment as string slice */
    fun asStr(): String = src.substring(start, end)
}

/** JsContext: Element indices with source */
data class JsContext(
    val element: JsElement,
    val src: String,
) {
    /** Get segments (delimiter-exclusive) */
    fun segments(): List<JsIndex> {
        val boundaries = mutableListOf(element.openIdx)
        boundaries.addAll(element.commaIdxs)
        boundaries.add(element.closeIdx)

        return boundaries.windowed(2) { window ->
            JsIndex(
                start = window[0] + 1,
                end = window[1],
                src = src,
            )
        }
    }
}

/** JsonParser - Indexes and reifies JSON */
object JsonParser {
    /** Parse JSON string to value */
    fun parse(json: String): JsonValue {
        val trimmed = json.trim()
        return reify(trimmed)
    }

    /** Index JSON: find braces and commas */
    fun index(src: String): JsElement {
        val chars = src.toCharArray()
        var depth = 0
        var openIdx = 0
        var closeIdx = 0
        val commaIdxs = mutableListOf<Int>()
        var insideQuote = false
        var escapeNext = false

        for (i in chars.indices) {
            val c = chars[i]
            if (insideQuote) {
                if (escapeNext) {
                    escapeNext = false
                } else if (c == '\\') {
                    escapeNext = true
                } else if (c == '"') {
                    insideQuote = false
                }
            } else {
                when (c) {
                    '{', '[' -> {
                        depth += 1
                        if (depth == 1) {
                            openIdx = i
                        }
                    }
                    '}', ']' -> {
                        depth -= 1
                        if (depth == 0) {
                            closeIdx = i
                            break
                        }
                    }
                    ',' -> {
                        if (depth == 1) {
                            commaIdxs.add(i)
                        }
                    }
                    '"' -> {
                        insideQuote = true
                    }
                    else -> {}
                }
            }
        }

        return JsElement(openIdx, closeIdx, commaIdxs)
    }

    /** Reify JSON: convert to Kotlin values */
    private fun reify(src: String): JsonValue {
        val trimmed = src.trim()
        val firstChar = trimmed.firstOrNull()

        return when (firstChar) {
            '{' -> reifyObject(trimmed)
            '[' -> reifyArray(trimmed)
            '"'' -> reifyString(trimmed)
            't', 'f' -> JsonValue.Bool(trimmed.startsWith('t'))
            'n' -> JsonValue.Null
            else -> if (firstChar != null) reifyNumber(trimmed) else JsonValue.Null
        }
    }

    private fun reifyObject(src: String): JsonValue {
        val element = index(src)
        val ctx = JsContext(element, src)
        val map = mutableMapOf<String, JsonValue>()

        for (segment in ctx.segments()) {
            val segStr = segment.asStr().trim()
            if (segStr.isEmpty()) continue

            // Parse key:value pair
            val colonIdx = findColonInObj(segStr)
            if (colonIdx != null) {
                val keyPart = segStr.substring(0, colonIdx).trim()
                val valuePart = segStr.substring(colonIdx + 1).trim()

                // Extract key (remove quotes)
                val key = extractKey(keyPart)
                val value = reify(valuePart)
                map[key] = value
            }
        }

        return JsonValue.Object(map)
    }

    private fun reifyArray(src: String): JsonValue {
        val element = index(src)
        val ctx = JsContext(element, src)
        val values = mutableListOf<JsonValue>()

        for (segment in ctx.segments()) {
            val segStr = segment.asStr().trim()
            if (segStr.isNotEmpty()) {
                values.add(reify(segStr))
            }
        }

        return JsonValue.Array(values)
    }

    private fun reifyString(src: String): JsonValue {
        // Remove surrounding quotes
        if (src.length >= 2 && src.startsWith('"') && src.endsWith('"')) {
            return JsonValue.Str(src.substring(1, src.length - 1))
        }
        return JsonValue.Str(src)
    }

    private fun reifyNumber(src: String): JsonValue {
        return try {
            JsonValue.Number(src.toDouble())
        } catch (e: NumberFormatException) {
            JsonValue.Null
        }
    }

    private fun findColonInObj(seg: String): Int? {
        var inQuote = false
        var escape = false

        seg.forEachIndexed { i, c ->
            if (inQuote) {
                if (escape) {
                    escape = false
                } else if (c == '\\') {
                    escape = true
                } else if (c == '"') {
                    inQuote = false
                }
            } else if (c == '"') {
                inQuote = true
            } else if (c == ':') {
                return i
            }
        }
        return null
    }

    private fun extractKey(keyPart: String): String {
        val trimmed = keyPart.trim()
        if (trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"')) {
            return trimmed.substring(1, trimmed.length - 1)
        }
        return trimmed
    }
}
