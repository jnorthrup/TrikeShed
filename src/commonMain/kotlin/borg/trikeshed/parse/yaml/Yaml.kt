@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.parse.yaml

import borg.trikeshed.TypeEvidence
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.toRowVec

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

private fun Series<Char>.isQuoted(q: Char): Boolean =
    size >= 2 && this[0] == q && this[size - 1] == q

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

/* ─── thin parser layer backed by confix YamlScan + Reify ──────────────── */

/** Parse YAML text to a plain Map — mirrors the old openapi/Yaml.kt API */
fun parse(text: String): Map<String, Any?> {
    val doc: YamlDocument = YamlParser.parse(text)
    return (doc.root.reify() as? Map<String, Any?>) ?: emptyMap()
}

object YamlParser {
    /** Parse a YAML string into a YamlNode AST, then wrap as YamlDocument */
    fun parse(text: String): YamlDocument {
        val src = text.asSeries()
        val elems = YamlScan.scan(src)
        val lineStarts = buildLineStarts(src)
        val root = buildYamlNode(elems, src, 0, lineStarts)
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

    /** Build an IntArray of character positions at the start of each line (1-indexed: result[0]=0 for line 1). */
    private fun buildLineStarts(src: Series<Char>): IntArray {
        val buf = IntBuf(16)
        buf.add(0)
        var i = 0
        while (i < src.size) {
            if (src[i] == '\n') buf.add(i + 1)
            i++
        }
        return buf.snapshot()
    }

    /** 1-based line number for a character offset using a pre-built lineStarts array. */
    private fun lineOf(lineStarts: IntArray, offset: Int): Int {
        var lo = 0; var hi = lineStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (lineStarts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo + 1
    }

    /** Convert a JsElement subtree rooted at element [elemIdx] into a YamlNode.
     *  elems[i].a = (open j close) character offsets; lineStarts converts them to 1-based line numbers.
     */
    private fun buildYamlNode(elems: Series<JsElement>, src: Series<Char>, elemIdx: Int, lineStarts: IntArray): YamlNode {
        if (elemIdx >= elems.size) return YamlScalarNode(null, 1 j 1)
        val elem = elems[elemIdx]
        val tag = Combinators.tagOf(elem, src)
        val (open, close) = elem.a
        val span = lineOf(lineStarts, open) j lineOf(lineStarts, close)
        return when (tag) {
            Tag.NULL -> YamlScalarNode(null, span)
            Tag.ARRAY -> {
                val childIndices = extractChildIndices(elemIdx, elems, src)
                val children = if (childIndices.size > 0) {
                    childIndices α { childIdx -> buildYamlNode(elems, src, childIdx, lineStarts) }
                } else {
                    // Inline flow sequence (parseFlowLine emits no children): parse YAML-aware
                    parseInlineSequence(src.slice(open, close + 1))
                }
                YamlSequenceNode(children, span)
            }
            Tag.OBJECT -> {
                val childIndices = extractChildIndices(elemIdx, elems, src)
                val entries = if (childIndices.size > 0) {
                    buildMappingEntries(childIndices, elems, src, lineStarts)
                } else {
                    // Inline flow mapping: parse YAML-aware
                    parseInlineMapping(src.slice(open, close + 1))
                }
                YamlMappingNode(entries, span)
            }
            else -> {
                val text = Combinators.textOf(elem, src)
                YamlScalarNode(text.asSeries(), span)
            }
        }
    }

    private fun extractChildIndices(parentIdx: Int, elems: Series<JsElement>, src: Series<Char>): Series<Int> {
        val parent = elems[parentIdx]
        val parentOpen = parent.a.a
        val parentClose = parent.a.b
        val result = mutableListOf<Int>()
        var kk = parentIdx + 1
        while (kk < elems.size) {
            val elem = elems[kk]
            val eOpen = elem.a.a
            val eClose = elem.a.b
            if (eOpen >= parentClose) break
            if (eOpen >= parentOpen && eClose <= parentClose) {
                result.add(kk)
                val childClose = eClose
                kk++
                while (kk < elems.size && elems[kk].a.a <= childClose) {
                    kk++
                }
            } else {
                kk++
            }
        }
        return result.size j { i: Int -> result[i] }
    }

    private fun buildMappingEntries(
        childIndices: Series<Int>,
        elems: Series<JsElement>,
        src: Series<Char>,
        lineStarts: IntArray,
    ): Series<YamlMappingEntry> {
        val n = childIndices.size
        if (n == 0) return Join.emptySeriesOf<YamlMappingEntry>()
        val list = ArrayList<YamlMappingEntry>(n / 2)
        var i = 0
        while (i + 1 < n) {
            val keyIdx = childIndices[i]
            val valIdx = childIndices[i + 1]
            val keyElem = elems[keyIdx]
            val valElem = elems[valIdx]
            val keyText = Combinators.textOf(keyElem, src)
            val valNode = buildYamlNode(elems, src, valIdx, lineStarts)
            val entryStart = lineOf(lineStarts, keyElem.a.a)
            val entryEnd = lineOf(lineStarts, valElem.a.b)
            list.add(YamlMappingEntry(keyText.asSeries(), valNode, entryStart j entryEnd))
            i += 2
        }
        return list.toSeries()
    }

    /** Parse a YAML inline flow sequence `[a, b, c]` into child YamlNodes.
     *  Handles unquoted strings, numbers, booleans, and nested `[...]`.
     *  src must span from `[` to `]` inclusive.
     */
    private fun parseInlineSequence(src: Series<Char>): Series<YamlNode> {
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

    private fun parseInlineMapping(src: Series<Char>): Series<YamlMappingEntry> {
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
