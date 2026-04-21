package borg.trikeshed.couch.api

import borg.trikeshed.couch.miniduck.*
import borg.trikeshed.lib.j

/**
 * CouchDB 1.1 view query result row set.
 */
data class CouchDb11RowSet(
    val totalRows: Int,
    val offset: Int,
    val rows: BlockRowVec,
) {
    companion object {
        /**
         * Parse a CouchDB 1.1 view result JSON into a [CouchDb11RowSet].
         * Uses manual string parsing (no serialization lib).
         */
        fun fromJson(json: String): CouchDb11RowSet {
            val totalRows = extractInt(json, "total_rows")
            val offset = extractInt(json, "offset")

            // Extract the rows array content
            val rowsKeyIndex = json.indexOf("\"rows\"")
            require(rowsKeyIndex >= 0) { "Missing 'rows' in JSON" }
            val arrayStart = json.indexOf('[', rowsKeyIndex)
            val arrayEnd = json.lastIndexOf(']')
            val rowsContent = json.substring(arrayStart + 1, arrayEnd)
            println("DEBUG rowsContent: [$rowsContent]")

            val block = BlockRowVec.mutable()

            // Parse individual row objects
            if (rowsContent.isNotBlank()) {
                var pos = 0
                while (pos < rowsContent.length) {
                    val objStart = rowsContent.indexOf('{', pos)
                    if (objStart < 0) break
                    val objEnd = findMatchingBrace(rowsContent, objStart)
                    val rowJson = rowsContent.substring(objStart, objEnd + 1)

                    val id = extractString(rowJson, "id")
                    val keyStr = extractRawValue(rowJson, "key")
                    val key = parseJsonValue(keyStr)
                    val valueStr = extractRawValue(rowJson, "value")
                    val value = parseJsonValue(valueStr)
                    val docStr = extractRawValueOrNull(rowJson, "doc")

                    val docLoader: (() -> RowVec)? = docStr?.let { rawDoc ->
                        {
                            parseDocRowVec(rawDoc)
                        }
                    }

                    block.append(
                        ViewRowVec(
                            id = id,
                            key = key,
                            value = value,
                            docLoader = docLoader,
                        )
                    )
                    pos = objEnd + 1
                }
            }

            block.seal()
            return CouchDb11RowSet(totalRows = totalRows, offset = offset, rows = block)
        }

        private fun extractInt(json: String, field: String): Int {
            val regex = Regex("\"$field\"\\s*:\\s*(\\d+)")
            val match = regex.find(json) ?: error("Field '$field' not found")
            return match.groupValues[1].toInt()
        }

        private fun extractString(json: String, field: String): String {
            val regex = Regex("\"$field\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
            val match = regex.find(json) ?: error("String field '$field' not found")
            return unescape(match.groupValues[1])
        }

        /**
         * Extract the raw JSON value for a top-level field in a row object.
         * Handles strings, numbers, booleans, objects, arrays, and null.
         */
        private fun extractRawValue(json: String, field: String): String {
            val keyPattern = "\"$field\"\\s*:\\s*"
            val keyMatch = Regex(keyPattern).find(json) ?: error("Field '$field' not found")
            val valueStart = keyMatch.range.last + 1
            return extractRawValueFrom(json, valueStart)
        }

        private fun extractRawValueOrNull(json: String, field: String): String? {
            val keyPattern = "\"$field\"\\s*:\\s*"
            val keyMatch = Regex(keyPattern).find(json) ?: return null
            val valueStart = keyMatch.range.last + 1
            return extractRawValueFrom(json, valueStart)
        }

        private fun extractRawValueFrom(json: String, start: Int): String {
            val c = json[start]
            return when (c) {
                '"' -> {
                    // String value - find the closing quote
                    var i = start + 1
                    val sb = StringBuilder()
                    sb.append('"')
                    while (i < json.length) {
                        if (json[i] == '\\') {
                            sb.append(json[i])
                            sb.append(json[i + 1])
                            i += 2
                        } else if (json[i] == '"') {
                            sb.append('"')
                            return sb.toString()
                        } else {
                            sb.append(json[i])
                            i++
                        }
                    }
                    error("Unterminated string")
                }
                '{' -> {
                    val end = findMatchingBrace(json, start)
                    json.substring(start, end + 1)
                }
                '[' -> {
                    val end = findMatchingBracket(json, start)
                    json.substring(start, end + 1)
                }
                else -> {
                    // number, boolean, null - read until comma or brace
                    var i = start
                    while (i < json.length && json[i] != ',' && json[i] != '}' && json[i] != ']') {
                        i++
                    }
                    json.substring(start, i).trim()
                }
            }
        }

        private fun parseJsonValue(raw: String): Any? = when {
            raw == "null" -> null
            raw == "true" -> true
            raw == "false" -> false
            raw.startsWith('"') && raw.endsWith('"') -> unescape(raw.substring(1, raw.length - 1))
            raw.startsWith('{') -> parseJsonObject(raw)
            raw.startsWith('[') -> parseJsonArray(raw)
            raw.toIntOrNull() != null -> raw.toInt()
            raw.toDoubleOrNull() != null -> raw.toDouble()
            else -> raw
        }

        private fun parseJsonObject(raw: String): Map<String, Any?> {
            val content = raw.substring(1, raw.length - 1).trim()
            if (content.isEmpty()) return emptyMap()
            val result = mutableMapOf<String, Any?>()
            var pos = 0
            while (pos < content.length) {
                // skip whitespace/commas
                while (pos < content.length && (content[pos] == ' ' || content[pos] == '\n' || content[pos] == '\r' || content[pos] == '\t' || content[pos] == ',')) pos++
                if (pos >= content.length) break

                // expect key
                require(content[pos] == '"') { "Expected '\"' at pos $pos in: $content" }
                val keyEnd = content.indexOf('"', pos + 1)
                // handle escaped quotes in key
                var keyEndActual = keyEnd
                while (keyEndActual > 0 && content[keyEndActual - 1] == '\\') {
                    keyEndActual = content.indexOf('"', keyEndActual + 1)
                }
                val key = unescape(content.substring(pos + 1, keyEndActual))
                pos = keyEndActual + 1

                // skip colon
                while (pos < content.length && content[pos] != ':') pos++
                pos++ // skip colon
                while (pos < content.length && content[pos] == ' ') pos++

                // read value
                val valueRaw = extractRawValueFrom(content, pos)
                result[key] = parseJsonValue(valueRaw)
                pos += valueRaw.length
            }
            return result
        }

        private fun parseJsonArray(raw: String): List<Any?> {
            val content = raw.substring(1, raw.length - 1).trim()
            if (content.isEmpty()) return emptyList()
            val result = mutableListOf<Any?>()
            var pos = 0
            while (pos < content.length) {
                while (pos < content.length && (content[pos] == ' ' || content[pos] == ',' || content[pos] == '\n' || content[pos] == '\r' || content[pos] == '\t')) pos++
                if (pos >= content.length) break
                val valueRaw = extractRawValueFrom(content, pos)
                result.add(parseJsonValue(valueRaw))
                pos += valueRaw.length
            }
            return result
        }

        private fun parseDocRowVec(rawDoc: String): DocRowVec {
            val map = parseJsonObject(rawDoc)
            val keys = map.keys.toList()
            val cells = keys.map { map[it] }
            return DocRowVec(keys = keys, cells = cells)
        }

        private fun findMatchingBrace(s: String, start: Int): Int {
            require(s[start] == '{') { "Expected '{' at $start" }
            var depth = 0
            var inString = false
            var i = start
            while (i < s.length) {
                if (inString) {
                    if (s[i] == '\\') i++ // skip escaped char
                } else {
                    when (s[i]) {
                        '"' -> inString = true
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) return i
                        }
                    }
                }
                i++
            }
            error("Unmatched brace")
        }

        private fun findMatchingBracket(s: String, start: Int): Int {
            require(s[start] == '[') { "Expected '[' at $start" }
            var depth = 0
            var inString = false
            var i = start
            while (i < s.length) {
                if (inString) {
                    if (s[i] == '\\') i++ // skip escaped char
                } else {
                    when (s[i]) {
                        '"' -> inString = true
                        '[' -> depth++
                        ']' -> {
                            depth--
                            if (depth == 0) return i
                        }
                    }
                }
                i++
            }
            error("Unmatched bracket")
        }

        private fun unescape(s: String): String = s
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
    }
}
