@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.parse.yaml

import borg.trikeshed.common.TypeEvidence
import borg.trikeshed.common.toRowVec
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*

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
    val doc = YamlParser.parse(text)
    return (doc.root.reify() as? Map<String, Any?>) ?: emptyMap()
}

object YamlParser {
    /** Parse a YAML string into a YamlNode AST, then wrap as YamlDocument */
    fun parse(text: String): YamlDocument {
        val src = text.asSeries()
        val elems = YamlScan.scan(src)
        val root = buildYamlNode(elems, src, 0)
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

    /** Convert a JsElement subtree rooted at element [elemIdx] into a YamlNode.
     *  elems[i].a = (open j close) for that element's span.
     *  commas track child membership.
     */
    private fun buildYamlNode(elems: Series<JsElement>, src: Series<Char>, elemIdx: Int): YamlNode {
        if (elemIdx >= elems.size) return YamlScalarNode(null, 0 j 0)
        val elem = elems[elemIdx]
        val tag = Combinators.tagOf(elem, src)
        val (open, close) = elem.a
        val commas = elem.b
        return when (tag) {
            Tag.NULL -> YamlScalarNode(null, open j close)
            Tag.ARRAY -> {
                // children are the comma-delimited sub-elements
                val childIndices = extractChildIndices(elemIdx, elems, src)
                val children = childIndices α { childIdx -> buildYamlNode(elems, src, childIdx) }
                YamlSequenceNode(children, open j close)
            }
            Tag.OBJECT -> {
                // children alternate key/value; group into YamlMappingEntry pairs
                val childIndices = extractChildIndices(elemIdx, elems, src)
                val entries = buildMappingEntries(childIndices, elems, src)
                YamlMappingNode(entries, open j close)
            }
            else -> {
                // scalar
                val text = Combinators.textOf(elem, src)
                YamlScalarNode(text.asSeries(), open j close)
            }
        }
    }

    private fun extractChildIndices(parentIdx: Int, elems: Series<JsElement>, src: Series<Char>): Series<Int> {
        val parent = elems[parentIdx]
        val storedCommas = parent.b  // parser-recorded child positions
        // filter to positive entries only (skip tag sentinels)
        var count = 0; var k = 0
        while (k < storedCommas.size) { if (storedCommas[k] >= 0) count++; k++ }
        return count j { i: Int ->
            var idx = 0; var seen = 0
            while (idx < storedCommas.size) {
                val openPos = storedCommas[idx]
                if (openPos >= 0) {
                    if (seen == i) {
                        // Search forward from parentIdx+1 for element with matching open position
                        var kk = parentIdx + 1
                        while (kk < elems.size) {
                            if (elems[kk].a.a == openPos) return@j kk
                            kk++
                        }
                        return@j -1
                    }
                    seen++
                }
                idx++
            }
            -1 // not found
        }
    }

    private fun buildMappingEntries(
        childIndices: Series<Int>,
        elems: Series<JsElement>,
        src: Series<Char>,
    ): Series<YamlMappingEntry> {
        val n = childIndices.size
        if (n == 0) return Join.emptySeriesOf<YamlMappingEntry>()
        val list = ArrayList<YamlMappingEntry>(n)
        var i = 0
        while (i < n) {
            val keyIdx = childIndices[i]
            val valIdx = keyIdx + 1  // value follows key by element index
            val keyElem = elems[keyIdx]
            val valElem = elems[valIdx]
            val keySpan = keyElem.a
            val keyText = Combinators.textOf(keyElem, src)
            val valNode = buildYamlNode(elems, src, valIdx)
            list.add(YamlMappingEntry(keyText.asSeries(), valNode, keySpan.a j valElem.a.b))
            i++
        }
        return list.toSeries()
    }

}
