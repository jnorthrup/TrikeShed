@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.parse.yaml

import borg.trikeshed.common.TypeEvidence
import borg.trikeshed.common.toRowVec
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.*

typealias YamlSpan = Twin<Int>

// convenience accessors for previous field names
val YamlSpan.startLine: Int get() = this.a
val YamlSpan.endLine: Int get() = this.b

sealed interface YamlNode {
    val span: YamlSpan

    // non-null defaults: avoid nullable plumbing at call sites
    fun reify(
        nodeEvidence: MutableList<TypeEvidence> = mutableListOf(),
        rowVecCallback: (RowVec) -> Unit = {},
    ): Any?
}

data class YamlScalarNode(
    val value: Series<Char>?,
    override val span: YamlSpan,
) : YamlNode {
    override fun reify(
        nodeEvidence: MutableList<TypeEvidence>,
        rowVecCallback: (RowVec) -> Unit,
    ): Any? {
        val evidence = TypeEvidence.sample(value ?: "".toSeries())
        nodeEvidence.add(evidence)
        rowVecCallback(evidence.toRowVec())
        return parseScalar(value)
    }
}

data class YamlSequenceNode(
    val items: Series<YamlNode>,
    override val span: YamlSpan,
) : YamlNode {
    override fun reify(
        nodeEvidence: MutableList<TypeEvidence>,
        rowVecCallback: (RowVec) -> Unit,
    ): Any? {
        val evidence = TypeEvidence.sample("[]".toSeries())
        nodeEvidence.add(evidence)
        rowVecCallback(evidence.toRowVec())
        // materialize lazily-composed values as a List for callers
        return (items α { it.reify(nodeEvidence, rowVecCallback) }).toList()
    }
}

data class YamlMappingEntry(
    val key: Series<Char>,
    val value: YamlNode,
    val span: YamlSpan,
)

data class YamlMappingNode(
    val entries: Series<YamlMappingEntry>,
    override val span: YamlSpan,
) : YamlNode {
    override fun reify(
        nodeEvidence: MutableList<TypeEvidence>,
        rowVecCallback: (RowVec) -> Unit,
    ): Any? {
        val evidence = TypeEvidence.sample("{}".toSeries())
        nodeEvidence.add(evidence)
        rowVecCallback(evidence.toRowVec())
        // materialize mapping into a standard Kotlin Map
        return entries.view.associate { it.key.asString() to it.value.reify(nodeEvidence, rowVecCallback) }
    }
}

data class YamlDocument(
    val root: YamlNode,
)

private data class YamlLine(
    val number: Int,
    val indent: Int,
    val content: Series<Char>,
)

object YamlParser {
    fun parse(src: Series<Char>): YamlDocument {
        val lines = tokenize(src)
        require(lines.isNotEmpty()) { "YAML input is empty" }
        val parser = Parser(lines)
        return YamlDocument(parser.parseDocument())
    }

    fun parse(text: String): YamlDocument = parse(text.toSeries())

    fun reify(
        src: Series<Char>,
        nodeEvidence: MutableList<TypeEvidence> = mutableListOf(),
        rowVecCallback: (RowVec) -> Unit = {},
    ): Any? = parse(src).root.reify(nodeEvidence, rowVecCallback)

    fun reify(
        text: String,
        nodeEvidence: MutableList<TypeEvidence> = mutableListOf(),
        rowVecCallback: (RowVec) -> Unit = {},
    ): Any? = parse(text).root.reify(nodeEvidence, rowVecCallback)
}

private class Parser(
    private val lines: List<YamlLine>,
) {
    private var index = 0

    fun parseDocument(): YamlNode = parseBlock(lines.first().indent)

    private fun parseBlock(expectedIndent: Int): YamlNode {
        val line = current() ?: error("Expected YAML content")
        require(line.indent == expectedIndent) { "Unexpected indentation at line ${line.number}: expected $expectedIndent but found ${line.indent}" }
        return if (line.content.startsWith("- ")) parseSequence(expectedIndent) else parseMapping(expectedIndent)
    }

    private fun parseSequence(expectedIndent: Int): YamlSequenceNode {
        val items = mutableListOf<YamlNode>()
        val start = current()!!.number
        var end = start

        while (true) {
            val line = current() ?: break
            if (line.indent != expectedIndent || !line.content.startsWith("- ")) break

            val payload = line.content.drop(2).trimStart()
            advance()
            val node = parseSequenceItem(line, payload, expectedIndent)
            items += node
            end = node.span.endLine
        }

        return YamlSequenceNode(items.toSeries(), YamlSpan(start, end))
    }

    private fun parseSequenceItem(line: YamlLine, payload: Series<Char>, expectedIndent: Int): YamlNode {
        if (payload.isEmpty()) {
            val child = current()
            return if (child != null && child.indent > expectedIndent) parseBlock(child.indent)
            else YamlScalarNode(null, YamlSpan(line.number, line.number))
        }

        val separator = keyValueSeparator(payload)
        if (separator >= 0) {
            val key = payload.slice(0, separator).trim()
            val rest = payload.drop(separator + 1).trimStart()
            val inlineNode: YamlNode =
                if (rest.isEmpty()) {
                    val child = current()
                    if (child != null && child.indent > expectedIndent) parseBlock(child.indent)
                    else YamlScalarNode(null, YamlSpan(line.number, line.number))
                } else {
                    parseInlineValue(rest, line.number)
                }

            val entriesList = mutableListOf(YamlMappingEntry(key, inlineNode, YamlSpan(line.number, inlineNode.span.endLine)))
            val child = current()
            if (child != null && child.indent > expectedIndent) {
                val nested = parseBlock(child.indent)
                if (nested is YamlMappingNode) {
                    // materialize nested entries and append
                    entriesList.addAll(nested.entries.toList())
                    return YamlMappingNode(entriesList.toSeries(), YamlSpan(line.number, nested.span.endLine))
                }
            }
            return YamlMappingNode(entriesList.toSeries(), YamlSpan(line.number, entriesList.maxOf { it.span.endLine }))
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
            val key = line.content.slice(0, separator).trim()
            val rest = line.content.drop(separator + 1).trimStart()
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

        return YamlMappingNode(entries.toSeries(), YamlSpan(start, end))
    }

    private fun parseInlineValue(text: Series<Char>, lineNumber: Int): YamlNode =
        when {
            text.matches("[]") -> YamlSequenceNode(emptyList<YamlNode>().toSeries(), YamlSpan(lineNumber, lineNumber))
            text.matches("{}") -> YamlMappingNode(emptyList<YamlMappingEntry>().toSeries(), YamlSpan(lineNumber, lineNumber))
            text.startsWith("[") && text.endsWith("]") -> {
                val parts: Series<Series<Char>> = splitInlineList(text.slice(1, text.size - 1))
                val nodes: Series<YamlNode> = parts.α { it: Series<Char> -> YamlScalarNode(it, YamlSpan(lineNumber, lineNumber)) }
                YamlSequenceNode(nodes, YamlSpan(lineNumber, lineNumber))
            }
            else -> YamlScalarNode(text, YamlSpan(lineNumber, lineNumber))
        }

    private fun current(): YamlLine? = lines.getOrNull(index)

    private fun advance() {
        index++
    }
}

private fun tokenize(text: Series<Char>): List<YamlLine> {
    val lines = mutableListOf<YamlLine>()
    var lineNumber = 1
    var lineStart = 0

    fun emitLine(lineEndExclusive: Int) {
        val raw = text.slice(lineStart, lineEndExclusive)
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.matches("---") || trimmed.matches("...")) return
        if (trimmed.startsWith("#")) return

        val indent = raw.leadingWhitespace()
        val content = stripInlineComment(raw.drop(indent)).trimEnd()
        lines += YamlLine(lineNumber, indent, content)
    }

    for (index in 0 until text.size) {
        if (text[index] == '\n') {
            val lineEnd = if (index > lineStart && text[index - 1] == '\r') index - 1 else index
            emitLine(lineEnd)
            lineStart = index + 1
            lineNumber++
        }
    }
    if (lineStart <= text.size) emitLine(text.size)
    return lines
}

private fun stripInlineComment(text: Series<Char>): Series<Char> {
    var inSingle = false
    var inDouble = false
    for (index in 0 until text.size) {
        when (text[index]) {
            '\'' -> if (!inDouble) inSingle = !inSingle
            '"' -> if (!inSingle) inDouble = !inDouble
            '#' -> if (!inSingle && !inDouble && (index == 0 || text[index - 1].isWhitespace())) {
                return text.slice(0, index)
            }
        }
    }
    return text
}

private fun keyValueSeparator(text: Series<Char>): Int {
    var inSingle = false
    var inDouble = false
    for (index in 0 until text.size) {
        when (text[index]) {
            '\'' -> if (!inDouble) inSingle = !inSingle
            '"' -> if (!inSingle) inDouble = !inDouble
            ':' -> if (!inSingle && !inDouble) return index
        }
    }
    return -1
}

private fun splitInlineList(text: Series<Char>): Series<Series<Char>> {
    if (text.trim().isEmpty()) return 0 j {TODO()}
    val items = mutableListOf<Series<Char>>()
    var itemStart = 0
    var inSingle = false
    var inDouble = false
    for (index in 0 until text.size) {
        when (text[index]) {
            '\'' -> if (!inDouble) inSingle = !inSingle
            '"' -> if (!inSingle) inDouble = !inDouble
            ',' -> if (!inSingle && !inDouble) {
                val item = text.slice(itemStart, index).trim()
                if (!item.isEmpty()) items += item
                itemStart = index + 1
            }
        }
    }
    val tail: Series<Char> = text.slice(itemStart).trim()
    if (!tail.isEmpty()) items += tail
    return items.toSeries()
}

private fun parseScalar(raw: Series<Char>?): Any? {
    val value = raw?.trim() ?: return null
    if (value.isEmpty() || value.matches("~") || value.matches("null")) return null
    if (value.matches("true")) return true
    if (value.matches("false")) return false

    if ((value.isQuoted('"')) || (value.isQuoted('\''))) {
        return value.slice(1, value.size - 1).asString()
    }

    value.toIntOrNull()?.let { return it }
    value.toLongOrNull()?.let { return it }
    value.toDoubleOrNull()?.let { return it }
    return value.asString()
}
