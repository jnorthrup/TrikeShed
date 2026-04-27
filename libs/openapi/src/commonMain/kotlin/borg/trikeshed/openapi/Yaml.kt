package borg.trikeshed.openapi

/**
 * Pure-Kotlin YAML 1.2 parser for OpenAPI specs.
 * Handles: indented blocks, flow `[]`/`{}`, multi-line strings, directives, comments.
 */
object Yaml {

    private val DirRegex = Regex("^%[A-Z]+.*")
    private val DocStartRegex = Regex("^\\.\\.\\.")
    private val DocEndRegex = Regex("^---")
    private val CommentRegex = Regex("^#.*")
    private val BlankRegex = Regex("^[ \t]*$")

    fun parse(text: String): Any? {
        val allLines = text.lineSequence()
            .dropWhile { DirRegex.matches(it) || BlankRegex.matches(it) }
            .dropWhile { DocStartRegex.matches(it) || DocEndRegex.matches(it) }
            .toList()

        return parseBlock(allLines, 0).first
    }

    private data class ParseResult(val value: Any?, val consumed: Int)

    private fun parseBlock(lines: List<String>, startIdx: Int): ParseResult {
        var i = startIdx
        val map = linkedMapOf<String, Any?>()

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            val indent = line.length - trimmed.length

            if (trimmed.isEmpty() || CommentRegex.matches(trimmed)) { i++; continue }
            if (DocStartRegex.matches(trimmed) || DocEndRegex.matches(trimmed)) break

            // List item
            if (trimmed.startsWith("- ")) {
                val list = map[""] as? MutableList<Any?> ?: mutableListOf<Any?>().also { map[""] = it }
                val itemVal = trimmed.removePrefix("- ")
                val scalar = parseScalar(itemVal)
                if (itemVal.isEmpty() || !isScalarStr(scalar.toString())) {
                    val block = parseBlock(lines, i + 1)
                    list.add(block.value); i += block.consumed
                } else {
                    list.add(scalar); i++
                }
                continue
            }

            // Empty list item (just "-")
            if (trimmed == "-") {
                val list = map[""] as? MutableList<Any?> ?: mutableListOf<Any?>().also { map[""] = it }
                val block = parseBlock(lines, i + 1)
                list.add(block.value); i += block.consumed
                continue
            }

            // Key-value
            val colonIdx = trimmed.indexOf(':')
            if (colonIdx > 0) {
                val key = trimmed.substring(0, colonIdx).trim()
                val rest = trimmed.substring(colonIdx + 1).trim()

                when {
                    rest.isEmpty() -> {
                        val block = parseBlock(lines, i + 1)
                        map[key] = block.value; i += block.consumed
                    }
                    rest == "|" || rest == ">" -> {
                        val (scalar, ni) = parseBlockScalar(lines, i + 1, indent)
                        map[key] = scalar; i = ni
                    }
                    rest.startsWith("[") -> {
                        map[key] = parseFlowList(rest); i++
                    }
                    rest.startsWith("{") -> {
                        map[key] = parseFlowMap(rest); i++
                    }
                    rest == "null" || rest == "~" -> { map[key] = null; i++ }
                    rest == "true" -> { map[key] = true; i++ }
                    rest == "false" -> { map[key] = false; i++ }
                    rest.toDoubleOrNull() != null -> {
                        val d = rest.toDouble()
                        map[key] = if (d == d.toInt().toDouble()) d.toInt() else d
                        i++
                    }
                    else -> { map[key] = parseScalar(rest); i++ }
                }
                continue
            }

            // Plain scalar at top level
            if (trimmed.startsWith("[")) return ParseResult(parseFlowList(trimmed), i - startIdx + 1)
            if (trimmed.startsWith("{")) return ParseResult(parseFlowMap(trimmed), i - startIdx + 1)
            return ParseResult(parseScalar(trimmed), i - startIdx + 1)
        }

        val listVal = map.remove("") as? MutableList<Any?>
        return when {
            listVal != null && map.isEmpty() -> ParseResult(listVal, i - startIdx)
            map.isEmpty() -> ParseResult(listVal, i - startIdx)
            else -> ParseResult(map, i - startIdx)
        }
    }

    private fun parseBlockScalar(lines: List<String>, startIdx: Int, baseIndent: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var i = startIdx
        val minIndent = baseIndent + 1
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            val indent = line.length - trimmed.length
            if (trimmed.isEmpty()) { sb.append('\n'); i++; continue }
            if (indent < minIndent && trimmed.isNotEmpty()) break
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(trimmed)
            i++
        }
        return Pair(sb.toString(), i)
    }

    private fun isScalarStr(s: String): Boolean = s.isNotEmpty() &&
        (s.toDoubleOrNull() != null || s == "true" || s == "false" || s == "null" || s == "~")

    private fun parseFlowList(text: String): List<Any?> {
        val items = mutableListOf<Any?>()
        val cs = text.trimStart().drop(1).dropLast(1).toList()
        var i = 0
        while (i < cs.size) {
            i = skipWs(cs, i)
            if (i >= cs.size || cs[i] == ']') break
            val (v, ni) = flowVal(cs, i)
            items.add(v); i = ni
            i = skipWs(cs, i)
            if (i < cs.size && cs[i] == ',') i++
        }
        return items
    }

    private fun parseFlowMap(text: String): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>()
        val cs = text.trimStart().drop(1).dropLast(1).toList()
        var i = 0
        while (i < cs.size) {
            i = skipWs(cs, i)
            if (i >= cs.size || cs[i] == '}') break
            val (k, ni) = flowVal(cs, i)
            i = ni
            i = skipWs(cs, i)
            if (i < cs.size && cs[i] == ':') {
                i++
                i = skipWs(cs, i)
                val (v, nvi) = flowVal(cs, i)
                m[k.toString()] = v; i = nvi
            }
            i = skipWs(cs, i)
            if (i < cs.size && cs[i] == ',') i++
        }
        return m
    }

    private fun skipWs(cs: List<Char>, i: Int): Int {
        var j = i
        while (j < cs.size && cs[j] in " \t") j++
        return j
    }

    private fun flowVal(cs: List<Char>, i: Int): Pair<Any?, Int> {
        var j = skipWs(cs, i)
        if (j >= cs.size) return Pair(null, j)

        when (cs[j]) {
            '[' -> {
                val start = j + 1; var depth = 1; j++
                while (j < cs.size && depth > 0) {
                    when (cs[j]) {
                        '[' -> depth++
                        ']' -> depth--
                        '"', '\'' -> { val q = cs[j]; j++; while (j < cs.size && cs[j] != q) { if (cs[j] == '\\') j++; j++ } }
                    }
                    j++
                }
                val inner = cs.subList(start, j - 1).joinToString("")
                j++ // skip ]
                return Pair(parseFlowList("[$inner]"), j)
            }
            '{' -> {
                val start = j + 1; var depth = 1; j++
                while (j < cs.size && depth > 0) {
                    when (cs[j]) {
                        '{' -> depth++
                        '}' -> depth--
                        '"', '\'' -> { val q = cs[j]; j++; while (j < cs.size && cs[j] != q) { if (cs[j] == '\\') j++; j++ } }
                    }
                    j++
                }
                val inner = cs.subList(start, j - 1).joinToString("")
                j++ // skip }
                return Pair(parseFlowMap("{$inner}"), j)
            }
            '"', '\'' -> {
                val q = cs[j]; j++; val start = j
                while (j < cs.size && cs[j] != q) { if (cs[j] == '\\') j++; j++ }
                val raw = cs.subList(start, j).joinToString(""); j++
                return Pair(raw, j)
            }
            else -> {
                val start = j
                while (j < cs.size && cs[j] !in ",]} \t") j++
                val raw = cs.subList(start, j).joinToString("")
                return Pair(parseScalar(raw), j)
            }
        }
    }

    private fun parseScalar(v: String): Any {
        val s = v.trim()
        return when {
            s.isEmpty() || s == "null" || s == "~" -> ""
            s == "true" -> true
            s == "false" -> false
            s.toDoubleOrNull() != null -> {
                val d = s.toDouble()
                if (d == d.toInt().toDouble()) d.toInt() else d
            }
            s.startsWith("\"") && s.endsWith("\"") -> s.removeSurrounding("\"").replace("\\\"", "\"")
            s.startsWith("'") && s.endsWith("'") -> s.removeSurrounding("'")
            else -> s
        }
    }
}
