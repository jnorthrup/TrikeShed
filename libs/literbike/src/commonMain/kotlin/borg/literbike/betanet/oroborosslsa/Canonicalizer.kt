package borg.literbike.betanet.oroborosslsa

/**
 * Canonicalizer - Recursively sort JSON object keys for canonical representation.
 * Ported from literbike/src/betanet/oroboros_slsa/canonicalizer.rs.
 */

/**
 * Canonicalize a JSON string: parse, recursively sort object keys, serialize compactly.
 * Returns empty string if input is not valid JSON.
 */
fun canonicalize(json: String): String {
    val value = try {
        parseJsonValue(json.trim())
    } catch (e: Exception) {
        return ""
    }
    val sorted = sortValue(value)
    return toJsonString(sorted)
}

/**
 * Minimal JSON value representation.
 */
sealed class JsonValue {
    data class Str(val value: String) : JsonValue()
    data class Num(val value: String) : JsonValue()
    object True : JsonValue()
    object False : JsonValue()
    object Null : JsonValue()
    data class Arr(val items: MutableList<JsonValue>) : JsonValue()
    data class Obj(val entries: MutableList<Pair<String, JsonValue>>) : JsonValue()
}

private fun sortValue(v: JsonValue): JsonValue = when (v) {
    is JsonValue.Obj -> {
        val sorted = v.entries
            .map { (k, vv) -> k to sortValue(vv) }
            .sortedBy { it.first }
            .toMutableList()
        JsonValue.Obj(sorted)
    }
    is JsonValue.Arr -> JsonValue.Arr(v.items.map { sortValue(it) }.toMutableList())
    else -> v
}

/**
 * Minimal JSON parser (subset sufficient for canonicalization).
 */
private fun parseJsonValue(s: String): JsonValue {
    val trimmed = s.trim()
    return when {
        trimmed.isEmpty() -> throw IllegalArgumentException("Empty input")
        trimmed.startsWith("{") -> parseObject(trimmed)
        trimmed.startsWith("[") -> parseArray(trimmed)
        trimmed.startsWith("\"") -> JsonValue.Str(parseJsonString(trimmed))
        trimmed == "true" -> JsonValue.True
        trimmed == "false" -> JsonValue.False
        trimmed == "null" -> JsonValue.Null
        else -> JsonValue.Num(trimmed)
    }
}

private fun parseObject(s: String): JsonValue {
    val entries = mutableListOf<Pair<String, JsonValue>>()
    // Strip braces
    val inner = s.trim().removePrefix("{").removeSuffix("}").trim()
    if (inner.isEmpty()) return JsonValue.Obj(entries)

    val items = splitJsonTopLevel(inner)
    for (item in items) {
        val colonIdx = item.indexOf(':')
        if (colonIdx < 0) continue
        val keyStr = item.substring(0, colonIdx).trim()
        val valStr = item.substring(colonIdx + 1).trim()
        val key = parseJsonString(keyStr)
        val value = parseJsonValue(valStr)
        entries.add(key to value)
    }
    return JsonValue.Obj(entries)
}

private fun parseArray(s: String): JsonValue {
    val items = mutableListOf<JsonValue>()
    val inner = s.trim().removePrefix("[").removeSuffix("]").trim()
    if (inner.isEmpty()) return JsonValue.Arr(items)

    val parts = splitJsonTopLevel(inner)
    for (part in parts) {
        items.add(parseJsonValue(part.trim()))
    }
    return JsonValue.Arr(items)
}

private fun parseJsonString(s: String): String {
    val stripped = s.trim()
    if (stripped.length < 2 || stripped[0] != '"' || stripped.last() != '"') {
        return stripped
    }
    return stripped.substring(1, stripped.length - 1)
}

/**
 * Split JSON at top-level commas (respecting nesting).
 */
private fun splitJsonTopLevel(s: String): List<String> {
    val result = mutableListOf<String>()
    var depth = 0
    var inString = false
    var escape = false
    val current = StringBuilder()

    for (ch in s) {
        when {
            escape -> {
                current.append(ch)
                escape = false
            }
            ch == '\\' && inString -> {
                current.append(ch)
                escape = true
            }
            ch == '"' && !escape -> {
                inString = !inString
                current.append(ch)
            }
            inString -> current.append(ch)
            ch == '{' || ch == '[' -> {
                depth++
                current.append(ch)
            }
            ch == '}' || ch == ']' -> {
                depth--
                current.append(ch)
            }
            ch == ',' && depth == 0 -> {
                result.add(current.toString())
                current.clear()
            }
            else -> current.append(ch)
        }
    }
    if (current.isNotEmpty()) {
        result.add(current.toString())
    }
    return result
}

/**
 * Serialize JsonValue to compact JSON string.
 */
private fun toJsonString(v: JsonValue): String = when (v) {
    is JsonValue.Str -> "\"${v.value}\""
    is JsonValue.Num -> v.value
    JsonValue.True -> "true"
    JsonValue.False -> "false"
    JsonValue.Null -> "null"
    is JsonValue.Arr -> v.items.joinToString(",", prefix = "[", postfix = "]") { toJsonString(it) }
    is JsonValue.Obj -> v.entries.joinToString(",", prefix = "{", postfix = "}") { (k, vv) ->
        "\"$k\":${toJsonString(vv)}"
    }
}
