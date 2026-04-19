package borg.trikeshed.parse.interop

import borg.trikeshed.common.TypeEvidence
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.MapTypeMemento
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.SeqTypeMemento
import borg.trikeshed.cursor.TreeCursor
import borg.trikeshed.cursor.TypeMemento
import borg.trikeshed.cursor.joins
import borg.trikeshed.cursor.label
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.CharSeries
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.parse.yaml.YamlMappingNode
import borg.trikeshed.parse.yaml.YamlNode
import borg.trikeshed.parse.yaml.YamlParser
import borg.trikeshed.parse.yaml.YamlScalarNode
import borg.trikeshed.parse.yaml.YamlSequenceNode

enum class ReificationFlavor {
    Generic,
    JsonConfix,
    YamlIndent,
}

enum class ExtentTermination {
    ValueDelimiter,
    ConfixClose,
    IndentDrop,
    EndOfInput,
}

sealed interface OpaqueExtent {
    val flavor: ReificationFlavor
    val depth: Int
    val termination: ExtentTermination
}

data class JsonOpaqueExtent(
    val startOffset: Int,
    val endOffsetInclusive: Int,
    override val depth: Int,
    override val termination: ExtentTermination,
) : OpaqueExtent {
    override val flavor: ReificationFlavor = ReificationFlavor.JsonConfix
}

data class YamlOpaqueExtent(
    val startLine: Int,
    val endLine: Int,
    val indentDepth: Int,
    override val depth: Int,
    override val termination: ExtentTermination,
) : OpaqueExtent {
    override val flavor: ReificationFlavor = ReificationFlavor.YamlIndent
}

data class DescriptorFragment(
    val key: String?,
    val depth: Int,
    val memento: TypeMemento,
    val evidence: TypeEvidence,
    val extent: OpaqueExtent? = null,
    val children: List<DescriptorFragment> = emptyList(),
)

fun DescriptorFragment.signature(): String =
    buildString {
        append(key ?: "$")
        append(':')
        append(memento.label)
        if (children.isNotEmpty()) {
            append('{')
            append(children.sortedBy { it.key ?: "" }.joinToString(",") { it.signature() })
            append('}')
        }
    }

data class NdjsonPreparedParser(
    val descriptor: DescriptorFragment,
) {
    fun parse(line: String): Any? = JsonSupport.parse(line)
    fun describeRowTree(line: String): TreeCursor = StructuredParserSupport.describeJsonRowTree(line)
}

object StructuredParserSupport {
    fun describeRootEntry(value: Any?): DescriptorFragment = describeValueFragment(key = null, value = value, depth = 0)

    fun describeJsonText(text: String): DescriptorFragment =
        describeJsonFragment(CharSeries(text), 0, text.length, 0, null, ExtentTermination.EndOfInput)

    fun describeJsonRowTree(text: String): TreeCursor = describeJsonText(text).toTreeCursor()

    fun prepareNdjsonParser(
        sampleLine: String,
        schemaIndex: MutableMap<String, NdjsonPreparedParser>? = null,
    ): NdjsonPreparedParser {
        val descriptor = describeJsonText(sampleLine)
        val signature = descriptor.signature()
        return schemaIndex?.getOrPut(signature) { NdjsonPreparedParser(descriptor) } ?: NdjsonPreparedParser(descriptor)
    }

    fun parseNdjson(
        lines: Sequence<String>,
        schemaIndex: MutableMap<String, NdjsonPreparedParser>? = null,
    ): Sequence<Any?> = sequence {
        for (line in lines) {
            if (line.isBlank()) continue
            val parser = prepareNdjsonParser(line, schemaIndex)
            yield(parser.parse(line))
        }
    }

    fun describeYamlText(text: String): DescriptorFragment {
        val document = YamlParser.parse(text)
        val totalLines = text.replace("\r\n", "\n").lines().size
        return describeYamlNode(null, document.root, 0, totalLines)
    }

    fun describeYamlRowTree(text: String): TreeCursor = describeYamlText(text).toTreeCursor()

    private fun describeValueFragment(
        key: String?,
        value: Any?,
        depth: Int,
    ): DescriptorFragment {
        val evidence = sampleEvidence(value)
        val children =
            when (value) {
                is Map<*, *> -> value.entries.map { (childKey, childValue) ->
                    describeValueFragment(childKey.toString(), childValue, depth + 1)
                }
                is List<*> -> value.mapIndexed { index, childValue ->
                    describeValueFragment(index.toString(), childValue, depth + 1)
                }
                else -> emptyList()
            }
        return DescriptorFragment(
            key = key,
            depth = depth,
            memento = TypeEvidence.deduceMemento(evidence),
            evidence = evidence,
            children = children,
        )
    }

    private fun describeYamlNode(
        key: String?,
        node: YamlNode,
        depth: Int,
        totalLines: Int,
    ): DescriptorFragment {
        val value = node.reify()
        val children =
            when (node) {
                is YamlMappingNode -> node.entries.map { entry ->
                    describeYamlNode(entry.key, entry.value, depth + 1, totalLines)
                }
                is YamlSequenceNode -> node.items.mapIndexed { index, child ->
                    describeYamlNode(index.toString(), child, depth + 1, totalLines)
                }
                is YamlScalarNode -> emptyList()
            }
        val termination = if (node.span.endLine >= totalLines) ExtentTermination.EndOfInput else ExtentTermination.IndentDrop
        val extent = YamlOpaqueExtent(node.span.startLine, node.span.endLine, depth, depth, termination)
        val evidence = sampleEvidence(value)
        return DescriptorFragment(
            key = key,
            depth = depth,
            memento = TypeEvidence.deduceMemento(evidence),
            evidence = evidence,
            extent = extent,
            children = children,
        )
    }

    private fun describeJsonFragment(
        text: CharSeries,
        startInclusive: Int,
        endExclusive: Int,
        depth: Int,
        key: String?,
        termination: ExtentTermination,
    ): DescriptorFragment {
        val (start, end) = trimBounds(text, startInclusive, endExclusive)
        require(start < end) { "Expected non-empty JSON fragment" }

        val fragment = CharSeries(text[start until end])
        val evidence = TypeEvidence.sample(fragment)
        val children =
            when (fragment[0]) {
                '{' -> splitObjectMembers(text, start, end).map { member ->
                    describeJsonFragment(text, member.valueStart, member.valueEndExclusive, depth + 1, member.key, member.termination)
                }
                '[' -> splitArrayElements(text, start, end).mapIndexed { index, element ->
                    describeJsonFragment(text, element.first, element.second, depth + 1, index.toString(), jsonTermination(text, element.second, end))
                }
                else -> emptyList()
            }
        val extent =
            JsonOpaqueExtent(
                startOffset = start,
                endOffsetInclusive = end - 1,
                depth = depth,
                termination = if (fragment[0] == '{' || fragment[0] == '[') ExtentTermination.ConfixClose else termination,
            )
        return DescriptorFragment(
            key = key,
            depth = depth,
            memento = TypeEvidence.deduceMemento(evidence),
            evidence = evidence,
            extent = extent,
            children = children,
        )
    }

    private fun sampleEvidence(value: Any?): TypeEvidence =
        when (value) {
            is Map<*, *> -> TypeEvidence.sample("{}".toSeries())
            is List<*> -> TypeEvidence.sample("[]".toSeries())
            is String -> TypeEvidence.sample("\"${escape(value)}\"".toSeries())
            is Boolean, is Int, is Long, is Double, is Float -> TypeEvidence.sample(value.toString().toSeries())
            null -> TypeEvidence().apply {
                empty = 1U
                recordColumnLength(0)
            }
            else -> TypeEvidence.sample(value.toString().toSeries())
        }
}

fun DescriptorFragment.toTreeCursor(path: String = "$"): TreeCursor =
    TreeCursor(
        row = toRowVec(path),
        children =
            children.asSequence().map { child ->
                child.toTreeCursor(childPath(path, child.key))
            },
    )

fun DescriptorFragment.rowVecTree(path: String = "$"): Sequence<RowVec> = toTreeCursor(path).flatten()

private fun DescriptorFragment.toRowVec(path: String): RowVec {
    val extentFlavor = extent?.flavor?.name ?: ReificationFlavor.Generic.name
    val (extentStart, extentEnd, indentDepth) =
        when (val value = extent) {
            is JsonOpaqueExtent -> Triple(value.startOffset, value.endOffsetInclusive, 0)
            is YamlOpaqueExtent -> Triple(value.startLine, value.endLine, value.indentDepth)
            null -> Triple(-1, -1, 0)
        }
    val values = arrayOf<Any?>(
        "($path,$depth,${evidence.confix.ifEmpty { "scalar" }},${memento.label})",
        path,
        key ?: "$",
        depth,
        extentFlavor,
        extentStart,
        extentEnd,
        indentDepth,
        extent?.termination?.name ?: ExtentTermination.EndOfInput.name,
        evidence.confix,
        memento.label,
        children.size,
        evidence.digits.toInt(),
        evidence.periods.toInt(),
        evidence.exponent.toInt(),
        evidence.signs.toInt(),
        evidence.special.toInt(),
        evidence.alpha.toInt(),
        evidence.truefalse.toInt(),
        evidence.empty.toInt(),
        evidence.quotes.toInt(),
        evidence.dquotes.toInt(),
        evidence.whitespaces.toInt(),
        evidence.backslashes.toInt(),
        evidence.linefeed.toInt(),
        evidence.maxColumnLength.toInt(),
        if (evidence.minColumnLength == UShort.MAX_VALUE) 0 else evidence.minColumnLength.toInt(),
    )
    val meta = DESCRIPTOR_ROW_COLUMNS.size j { index: Int -> { DESCRIPTOR_ROW_COLUMNS[index] } }
    return values.size j { index: Int -> values[index] } joins meta
}

private fun childPath(parent: String, key: String?): String =
    when {
        key == null -> parent
        key.all(Char::isDigit) -> "$parent[$key]"
        parent == "$" -> "$.$key"
        else -> "$parent.$key"
    }

private val DESCRIPTOR_ROW_COLUMNS = arrayOf(
    ColumnMeta("identity", IOMemento.IoString),
    ColumnMeta("path", IOMemento.IoString),
    ColumnMeta("key", IOMemento.IoString),
    ColumnMeta("depth", IOMemento.IoInt),
    ColumnMeta("extentFlavor", IOMemento.IoString),
    ColumnMeta("extentStart", IOMemento.IoInt),
    ColumnMeta("extentEnd", IOMemento.IoInt),
    ColumnMeta("indentDepth", IOMemento.IoInt),
    ColumnMeta("termination", IOMemento.IoString),
    ColumnMeta("confix", IOMemento.IoString),
    ColumnMeta("typeMemento", IOMemento.IoString),
    ColumnMeta("childCount", IOMemento.IoInt),
    ColumnMeta("digits", IOMemento.IoInt),
    ColumnMeta("periods", IOMemento.IoInt),
    ColumnMeta("exponent", IOMemento.IoInt),
    ColumnMeta("signs", IOMemento.IoInt),
    ColumnMeta("special", IOMemento.IoInt),
    ColumnMeta("alpha", IOMemento.IoInt),
    ColumnMeta("truefalse", IOMemento.IoInt),
    ColumnMeta("empty", IOMemento.IoInt),
    ColumnMeta("quotes", IOMemento.IoInt),
    ColumnMeta("dquotes", IOMemento.IoInt),
    ColumnMeta("whitespaces", IOMemento.IoInt),
    ColumnMeta("backslashes", IOMemento.IoInt),
    ColumnMeta("linefeed", IOMemento.IoInt),
    ColumnMeta("maxColumnLength", IOMemento.IoInt),
    ColumnMeta("minColumnLength", IOMemento.IoInt),
)

private fun escape(value: String): String =
    buildString(value.length) {
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }

private data class JsonMember(
    val key: String,
    val valueStart: Int,
    val valueEndExclusive: Int,
    val termination: ExtentTermination,
)

private fun trimBounds(text: Series<Char>, startInclusive: Int, endExclusive: Int): Pair<Int, Int> {
    var start = startInclusive
    var end = endExclusive
    while (start < end && text[start].isWhitespace()) start++
    while (end > start && text[end - 1].isWhitespace()) end--
    return start to end
}

private fun splitObjectMembers(text: CharSeries, startInclusive: Int, endExclusive: Int): List<JsonMember> {
    val bodyStart = startInclusive + 1
    val bodyEnd = endExclusive - 1
    val segments = splitTopLevelSegments(text, bodyStart, bodyEnd)
    return segments.map { (segmentStart, segmentEnd) ->
        val separator = findTopLevelColon(text, segmentStart, segmentEnd)
        require(separator >= 0) { "Expected key/value separator in JSON object segment" }
        val (keyStart, keyEnd) = trimBounds(text, segmentStart, separator)
        val rawKey = CharSeries(text[keyStart until keyEnd]).asString()
        val valueStart = separator + 1
        JsonMember(
            key = rawKey.removeSurrounding("\""),
            valueStart = valueStart,
            valueEndExclusive = segmentEnd,
            termination = jsonTermination(text, segmentEnd, endExclusive),
        )
    }
}

private fun splitArrayElements(text: CharSeries, startInclusive: Int, endExclusive: Int): List<Pair<Int, Int>> =
    splitTopLevelSegments(text, startInclusive + 1, endExclusive - 1)

private fun splitTopLevelSegments(text: Series<Char>, startInclusive: Int, endExclusive: Int): List<Pair<Int, Int>> {
    val segments = mutableListOf<Pair<Int, Int>>()
    var insideQuote = false
    var escapeNext = false
    var nesting = 0
    var segmentStart = startInclusive
    var index = startInclusive

    while (index < endExclusive) {
        val ch = text[index]
        if (insideQuote) {
            when {
                escapeNext -> escapeNext = false
                ch == '\\' -> escapeNext = true
                ch == '"' -> insideQuote = false
            }
        } else {
            when (ch) {
                '"' -> insideQuote = true
                '{', '[' -> nesting++
                '}', ']' -> nesting--
                ',' -> if (nesting == 0) {
                    segments += trimBounds(text, segmentStart, index)
                    segmentStart = index + 1
                }
            }
        }
        index++
    }

    val tail = trimBounds(text, segmentStart, endExclusive)
    if (tail.first < tail.second) segments += tail
    return segments
}

private fun findTopLevelColon(text: Series<Char>, startInclusive: Int, endExclusive: Int): Int {
    var insideQuote = false
    var escapeNext = false
    var nesting = 0
    var index = startInclusive
    while (index < endExclusive) {
        val ch = text[index]
        if (insideQuote) {
            when {
                escapeNext -> escapeNext = false
                ch == '\\' -> escapeNext = true
                ch == '"' -> insideQuote = false
            }
        } else {
            when (ch) {
                '"' -> insideQuote = true
                '{', '[' -> nesting++
                '}', ']' -> nesting--
                ':' -> if (nesting == 0) return index
            }
        }
        index++
    }
    return -1
}

private fun jsonTermination(text: Series<Char>, segmentEndExclusive: Int, parentEndExclusive: Int): ExtentTermination {
    var index = segmentEndExclusive
    while (index < parentEndExclusive && text[index].isWhitespace()) index++
    return when {
        index >= parentEndExclusive -> ExtentTermination.EndOfInput
        text[index] == ',' -> ExtentTermination.ValueDelimiter
        text[index] == '}' || text[index] == ']' -> ExtentTermination.ConfixClose
        else -> ExtentTermination.EndOfInput
    }
}
