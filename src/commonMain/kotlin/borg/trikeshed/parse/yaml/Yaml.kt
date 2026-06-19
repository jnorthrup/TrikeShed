@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.parse.yaml

import borg.trikeshed.lib.TypeEvidence
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.Syntax

/* ─── public API surface — preserved for DescriptorFragments.kt ─────────── */

typealias YamlSpan = Twin<Int>
val YamlSpan.startLine: Int get() = this.a
val YamlSpan.endLine: Int get() = this.b

/** Parse a YAML scalar string value into a primitive */
private fun parseScalar(raw: Series<Char>?): Any? {
    val value = raw?.trim() ?: return null
    if (value.isEmpty() || value.matches("~") || value.matches("null")) return null
    if (value.matches("true")) return true
    if (value.matches("false")) return false
    if (value.isQuoted('"') || value.isQuoted('\'')) {
        return value.slice(1, value.size - 1).asString()
    }
    value.toIntOrNull()?.let { return it }
    value.toLongOrNull()?.let { return it }
    value.toDoubleOrNull()?.let { return it }
    return value.asString()
}

/** AST node types used by DescriptorFragments.describeYamlNode */
sealed interface YamlNode {
    val span: YamlSpan
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
        return entries.view.associate { it.key.asString() to it.value.reify(nodeEvidence, rowVecCallback) }
    }
}

data class YamlDocument(
    val root: YamlNode,
)

/* ─── thin parser layer backed by confix Syntax.YAML.scan ─────────────── */

/** Parse YAML text to a plain Map — mirrors the old openapi/Yaml.kt API */
fun parse(text: String): Map<String, Any?> {
    val doc: YamlDocument = YamlParser.parse(text)
    return (doc.root.reify() as? Map<String, Any?>) ?: emptyMap()
}

class DirectYamlParser(val lines: List<String>) {
    var lineIdx = 0

    data class LineInfo(val lineNum: Int, val indent: Int, val content: String)

    fun peekLine(): LineInfo? {
        var idx = lineIdx
        while (idx < lines.size) {
            val line = lines[idx]
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                idx++
                continue
            }
            val indent = line.length - line.trimStart().length
            return LineInfo(idx + 1, indent, trimmed)
        }
        return null
    }

    fun parseBlock(indent: Int): YamlNode {
        val firstLine = peekLine() ?: return YamlScalarNode(null, lines.size j lines.size)
        if (firstLine.content.startsWith("-")) {
            return parseSequence(firstLine.indent)
        }
        val colonIdx = findColon(firstLine.content)
        if (colonIdx >= 0) {
            return parseMapping(firstLine.indent)
        }
        return parseScalar(firstLine.indent)
    }

    fun findColon(str: String): Int {
        var insideQuote = false
        var quoteChar = '\u0000'
        var i = 0
        while (i < str.length) {
            val c = str[i]
            if (insideQuote) {
                if (c == quoteChar) insideQuote = false
            } else {
                if (c == '"' || c == '\'') {
                    insideQuote = true
                    quoteChar = c
                } else if (c == ':') {
                    if (i + 1 == str.length || str[i + 1].isWhitespace()) {
                        return i
                    }
                }
            }
            i++
        }
        return -1
    }

    fun parseMapping(indent: Int): YamlNode {
        val startLine = lineIdx + 1
        val entries = mutableListOf<YamlMappingEntry>()
        var endLine = startLine
        
        while (true) {
            val line = peekLine() ?: break
            if (line.indent < indent) break
            if (line.indent > indent) {
                lineIdx++
                endLine = line.lineNum
                continue
            }
            val colonIdx = findColon(line.content)
            if (colonIdx < 0) break
            
            lineIdx++
            endLine = line.lineNum
            
            val keyStr = line.content.substring(0, colonIdx).trim()
            val valStr = line.content.substring(colonIdx + 1).trim()
            
            val keySeries = keyStr.toSeries()
            val valNode: YamlNode
            
            if (valStr.isNotEmpty()) {
                if (valStr.startsWith("[") || valStr.startsWith("{") || valStr.startsWith("\"") || valStr.startsWith("'")) {
                    val slice = valStr.toSeries()
                    valNode = if (valStr.startsWith("[")) {
                        YamlSequenceNode(YamlParser.parseInlineSequence(slice), line.lineNum j line.lineNum)
                    } else if (valStr.startsWith("{")) {
                        YamlMappingNode(YamlParser.parseInlineMapping(slice), line.lineNum j line.lineNum)
                    } else {
                        YamlScalarNode(slice, line.lineNum j line.lineNum)
                    }
                } else {
                    valNode = YamlScalarNode(valStr.toSeries(), line.lineNum j line.lineNum)
                }
            } else {
                val nextLine = peekLine()
                if (nextLine != null && nextLine.indent > indent) {
                    valNode = parseBlock(nextLine.indent)
                    endLine = valNode.span.b
                } else {
                    valNode = YamlScalarNode(null, line.lineNum j line.lineNum)
                }
            }
            entries.add(YamlMappingEntry(keySeries, valNode, line.lineNum j endLine))
        }
        return YamlMappingNode(entries.toSeries(), startLine j endLine)
    }

    fun parseSequence(indent: Int): YamlNode {
        val startLine = lineIdx + 1
        val items = mutableListOf<YamlNode>()
        var endLine = startLine
        
        while (true) {
            val line = peekLine() ?: break
            if (line.indent < indent) break
            if (!line.content.startsWith("-")) break
            
            lineIdx++
            endLine = line.lineNum
            
            val rest = line.content.substring(1).trim()
            val valNode: YamlNode
            
            if (rest.isNotEmpty()) {
                val colonIdx = findColon(rest)
                if (colonIdx >= 0) {
                    val savedLines = lines.toMutableList()
                    val savedIdx = lineIdx
                    savedLines[savedIdx - 1] = " ".repeat(indent + 2) + rest
                    val parser = DirectYamlParser(savedLines)
                    parser.lineIdx = savedIdx - 1
                    valNode = parser.parseBlock(indent + 2)
                    lineIdx = parser.lineIdx
                    endLine = valNode.span.b
                } else if (rest.startsWith("[") || rest.startsWith("{") || rest.startsWith("\"") || rest.startsWith("'")) {
                    val slice = rest.toSeries()
                    valNode = if (rest.startsWith("[")) {
                        YamlSequenceNode(YamlParser.parseInlineSequence(slice), line.lineNum j line.lineNum)
                    } else if (rest.startsWith("{")) {
                        YamlMappingNode(YamlParser.parseInlineMapping(slice), line.lineNum j line.lineNum)
                    } else {
                        YamlScalarNode(slice, line.lineNum j line.lineNum)
                    }
                } else {
                    valNode = YamlScalarNode(rest.toSeries(), line.lineNum j line.lineNum)
                }
            } else {
                val nextLine = peekLine()
                if (nextLine != null && nextLine.indent > indent) {
                    valNode = parseBlock(nextLine.indent)
                    endLine = valNode.span.b
                } else {
                    valNode = YamlScalarNode(null, line.lineNum j line.lineNum)
                }
            }
            items.add(valNode)
        }
        return YamlSequenceNode(items.toSeries(), startLine j endLine)
    }

    fun parseScalar(indent: Int): YamlNode {
        val startLine = lineIdx + 1
        val line = peekLine() ?: return YamlScalarNode(null, startLine j startLine)
        lineIdx++
        var endLine = line.lineNum
        val sb = StringBuilder(line.content)
        while (true) {
            val nextLine = peekLine() ?: break
            if (nextLine.indent <= indent) break
            val colonIdx = findColon(nextLine.content)
            if (colonIdx >= 0) break
            lineIdx++
            endLine = nextLine.lineNum
            sb.append(" ").append(nextLine.content)
        }
        val text = sb.toString().trim()
        return YamlScalarNode(text.toSeries(), startLine j endLine)
    }
}

object YamlParser {
    /** Parse a YAML string into a YamlNode AST, then wrap as YamlDocument */
    fun parse(text: String): YamlDocument {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').lines()
        val parser = DirectYamlParser(lines)
        val root = parser.parseBlock(0)
        return YamlDocument(root)
    }

    fun parse(src: Series<Char>): YamlDocument = parse(src.asString())

    fun reify(
        src: Series<Char>,
        nodeEvidence: MutableList<TypeEvidence> = mutableListOf(),
        rowVecCallback: (RowVec) -> Unit = {},
    ): Any? = parse(src.asString()).root.reify(nodeEvidence, rowVecCallback)

    fun reify(
        text: String,
        nodeEvidence: MutableList<TypeEvidence> = mutableListOf(),
        rowVecCallback: (RowVec) -> Unit = {},
    ): Any? = parse(text).root.reify(nodeEvidence, rowVecCallback)

    /** Parse a YAML inline flow sequence `[a, b, c]` into child YamlNodes. */
    internal fun parseInlineSequence(src: Series<Char>): Series<YamlNode> {
        val items = mutableListOf<YamlNode>()
        var start = 1
        val end = src.size - 1
        var depth = 0
        var i = start
        while (i <= end) {
            when (src[i]) {
                '[', '{' -> depth++
                ']', '}' -> if (depth > 0) depth-- else { /* closing bracket of this seq */ }
                ',' -> if (depth == 0) {
                    items.add(parseInlineToken(src.slice(start, i)))
                    start = i + 1
                }
            }
            i++
        }
        if (start < end) items.add(parseInlineToken(src.slice(start, end)))
        return items.toSeries()
    }

    internal fun parseInlineMapping(src: Series<Char>): Series<YamlMappingEntry> {
        val entries = mutableListOf<YamlMappingEntry>()
        var start = 1
        val end = src.size - 1
        var depth = 0
        var i = start
        while (i <= end) {
            when (src[i]) {
                '[', '{' -> depth++
                ']', '}' -> if (depth > 0) depth--
                ',' -> if (depth == 0) {
                    parseInlinePair(src.slice(start, i))?.let { entries.add(it) }
                    start = i + 1
                }
            }
            i++
        }
        if (start < end) parseInlinePair(src.slice(start, end))?.let { entries.add(it) }
        return entries.toSeries()
    }

    private fun parseInlinePair(token: Series<Char>): YamlMappingEntry? {
        val trimmed = token.trimInlineWhitespace()
        val colon = (0 until trimmed.size).firstOrNull { trimmed[it] == ':' } ?: return null
        val key = trimmed.slice(0, colon).trimInlineWhitespace()
        val value = parseInlineToken(trimmed.slice(colon + 1, trimmed.size))
        return YamlMappingEntry(key, value, 1 j 1)
    }

    private fun parseInlineToken(token: Series<Char>): YamlNode {
        val t = token.trimInlineWhitespace()
        if (t.size == 0) return YamlScalarNode(null, 1 j 1)
        return when (t[0]) {
            '[' -> YamlSequenceNode(parseInlineSequence(t), 1 j 1)
            '{' -> YamlMappingNode(parseInlineMapping(t), 1 j 1)
            else -> YamlScalarNode(t, 1 j 1)
        }
    }

    private fun Series<Char>.trimInlineWhitespace(): Series<Char> {
        var s = 0; var e = size
        while (s < e && (this[s] == ' ' || this[s] == '\t')) s++
        while (e > s && (this[e - 1] == ' ' || this[e - 1] == '\t')) e--
        return if (s == 0 && e == size) this else slice(s, e)
    }
}
