@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.parse.yaml

import borg.trikeshed.lib.CharSeries
import borg.trikeshed.lib.Series

data class YamlSpan(
    val startLine: Int,
    val endLine: Int,
)

sealed interface YamlNode {
    val span: YamlSpan

    fun reify(): Any?
}

data class YamlScalarNode(
    val value: String?,
    override val span: YamlSpan,
) : YamlNode {
    override fun reify(): Any? = parseScalar(value)
}

data class YamlSequenceNode(
    val items: List<YamlNode>,
    override val span: YamlSpan,
) : YamlNode {
    override fun reify(): Any? = items.map { it.reify() }
}

data class YamlMappingEntry(
    val key: String,
    val value: YamlNode,
    val span: YamlSpan,
)

data class YamlMappingNode(
    val entries: List<YamlMappingEntry>,
    override val span: YamlSpan,
) : YamlNode {
    override fun reify(): Any? = entries.associate { it.key to it.value.reify() }
}

data class YamlDocument(
    val root: YamlNode,
)

private data class YamlLine(
    val number: Int,
    val indent: Int,
    val content: String,
)

object YamlParser {
    fun parse(src: Series<Char>): YamlDocument = parse(CharSeries(src).asString())

    fun parse(text: String): YamlDocument {
        val lines = tokenize(text)
        require(lines.isNotEmpty()) { "YAML input is empty" }
        val parser = Parser(lines)
        return YamlDocument(parser.parseDocument())
    }

    fun reify(src: Series<Char>): Any? = parse(src).root.reify()

    fun reify(text: String): Any? = parse(text).root.reify()
}

private class Parser(
    private val lines: List<YamlLine>,
) {
    private var index = 0

    fun parseDocument(): YamlNode = parseBlock(lines.first().indent)

    private fun parseBlock(expectedIndent: Int): YamlNode {
        val line = current() ?: error("Expected YAML content")
        require(line.indent == expectedIndent) {
            "Unexpected indentation at line ${line.number}: expected $expectedIndent but found ${line.indent}"
        }
        return if (line.content.startsWith("- ")) parseSequence(expectedIndent) else parseMapping(expectedIndent)
    }

    private fun parseSequence(expectedIndent: Int): YamlSequenceNode {
        val items = mutableListOf<YamlNode>()
        val start = current()!!.number
        var end = start

        while (true) {
            val line = current() ?: break
            if (line.indent != expectedIndent || !line.content.startsWith("- ")) break

            val payload = line.content.removePrefix("- ").trimStart()
            advance()
            val node = parseSequenceItem(line, payload, expectedIndent)
            items += node
            end = node.span.endLine
        }

        return YamlSequenceNode(items, YamlSpan(start, end))
    }

    private fun parseSequenceItem(line: YamlLine, payload: String, expectedIndent: Int): YamlNode {
        if (payload.isEmpty()) {
            val child = current()
            return if (child != null && child.indent > expectedIndent) parseBlock(child.indent)
            else YamlScalarNode(null, YamlSpan(line.number, line.number))
        }

        val separator = keyValueSeparator(payload)
        if (separator >= 0) {
            val key = payload.substring(0, separator).trim()
            val rest = payload.substring(separator + 1).trimStart()
            val inlineNode =
                if (rest.isEmpty()) {
                    val child = current()
                    if (child != null && child.indent > expectedIndent) parseBlock(child.indent)
                    else YamlScalarNode(null, YamlSpan(line.number, line.number))
                } else {
                    parseInlineValue(rest, line.number)
                }

            val entries = mutableListOf(YamlMappingEntry(key, inlineNode, YamlSpan(line.number, inlineNode.span.endLine)))
            val child = current()
            if (child != null && child.indent > expectedIndent) {
                val nested = parseBlock(child.indent)
                if (nested is YamlMappingNode) {
                    entries += nested.entries
                    return YamlMappingNode(entries, YamlSpan(line.number, nested.span.endLine))
                }
            }
            return YamlMappingNode(entries, YamlSpan(line.number, entries.maxOf { it.span.endLine }))
        }

        return parseInlineValue(payload, line.number)
    }

    private fun parseMapping(expectedIndent: Int): YamlMappingNode {
        val entries = mutableListOf<YamlMappingEntry>()
        val start = current()!!.number
        var end = start

        while (true) {
            val line = current() ?: break
            if (line.indent != expectedIndent || line.content.startsWith("- ")) break

            val separator = keyValueSeparator(line.content)
            require(separator >= 0) { "Expected key/value pair at line ${line.number}" }
            val key = line.content.substring(0, separator).trim()
            val rest = line.content.substring(separator + 1).trimStart()
            advance()

            val valueNode =
                if (rest.isEmpty()) {
                    val child = current()
                    if (child != null && child.indent > expectedIndent) parseBlock(child.indent)
                    else YamlScalarNode(null, YamlSpan(line.number, line.number))
                } else {
                    parseInlineValue(rest, line.number)
                }

            entries += YamlMappingEntry(key, valueNode, YamlSpan(line.number, valueNode.span.endLine))
            end = valueNode.span.endLine
        }

        return YamlMappingNode(entries, YamlSpan(start, end))
    }

    private fun parseInlineValue(text: String, lineNumber: Int): YamlNode =
        when {
            text == "[]" -> YamlSequenceNode(emptyList(), YamlSpan(lineNumber, lineNumber))
            text == "{}" -> YamlMappingNode(emptyList(), YamlSpan(lineNumber, lineNumber))
            text.startsWith("[") && text.endsWith("]") -> {
                val parts = splitInlineList(text.substring(1, text.lastIndex))
                YamlSequenceNode(parts.map { YamlScalarNode(it, YamlSpan(lineNumber, lineNumber)) }, YamlSpan(lineNumber, lineNumber))
            }
            else -> YamlScalarNode(text, YamlSpan(lineNumber, lineNumber))
        }

    private fun current(): YamlLine? = lines.getOrNull(index)

    private fun advance() {
        index++
    }
}

private fun tokenize(text: String): List<YamlLine> =
    text
        .replace("\r\n", "\n")
        .lineSequence()
        .mapIndexedNotNull { idx, raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed == "---" || trimmed == "...") return@mapIndexedNotNull null
            if (trimmed.startsWith("#")) return@mapIndexedNotNull null

            val indent = raw.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: 0
            YamlLine(idx + 1, indent, stripInlineComment(raw.substring(indent)).trimEnd())
        }
        .toList()

private fun stripInlineComment(text: String): String {
    var inSingle = false
    var inDouble = false
    for (index in text.indices) {
        when (val ch = text[index]) {
            '\'' -> if (!inDouble) inSingle = !inSingle
            '"' -> if (!inSingle) inDouble = !inDouble
            '#' -> if (!inSingle && !inDouble && (index == 0 || text[index - 1].isWhitespace())) {
                return text.substring(0, index)
            }
        }
    }
    return text
}

private fun keyValueSeparator(text: String): Int {
    var inSingle = false
    var inDouble = false
    for (index in text.indices) {
        when (val ch = text[index]) {
            '\'' -> if (!inDouble) inSingle = !inSingle
            '"' -> if (!inSingle) inDouble = !inDouble
            ':' -> if (!inSingle && !inDouble) return index
        }
    }
    return -1
}

private fun splitInlineList(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val items = mutableListOf<String>()
    val current = StringBuilder()
    var inSingle = false
    var inDouble = false
    for (ch in text) {
        when (ch) {
            '\'' -> if (!inDouble) {
                inSingle = !inSingle
                current.append(ch)
            } else current.append(ch)
            '"' -> if (!inSingle) {
                inDouble = !inDouble
                current.append(ch)
            } else current.append(ch)
            ',' -> if (!inSingle && !inDouble) {
                items += current.toString().trim()
                current.clear()
            } else {
                current.append(ch)
            }
            else -> current.append(ch)
        }
    }
    items += current.toString().trim()
    return items.filter { it.isNotEmpty() }
}

private fun parseScalar(raw: String?): Any? {
    val value = raw?.trim() ?: return null
    if (value.isEmpty() || value == "~" || value == "null") return null
    if (value == "true") return true
    if (value == "false") return false

    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith('\'') && value.endsWith('\''))) {
        return value.substring(1, value.lastIndex)
    }

    value.toIntOrNull()?.let { return it }
    value.toLongOrNull()?.let { return it }
    value.toDoubleOrNull()?.let { return it }
    return value
}
