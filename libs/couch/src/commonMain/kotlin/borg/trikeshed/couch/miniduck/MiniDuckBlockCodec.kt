package borg.trikeshed.couch.miniduck

import borg.trikeshed.lib.*
import borg.trikeshed.parse.json.*

// Minimal JSON serializer for NDJSON block persistence — no external deps.
private fun Any?.toJsonString(): String = when (this) {
    null -> "null"
    is Boolean -> toString()
    is Number -> toString()
    is String -> buildString {
        append('"')
        for (c in this@toJsonString) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n")
            '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> append(c)
        }
        append('"')
    }
    is Map<*, *> -> entries.joinToString(",", "{", "}") { (k, v) ->
        k.toJsonString() + ":" + v.toJsonString()
    }
    is List<*> -> joinToString(",", "[", "]") { it.toJsonString() }
    is ByteArray -> map { it.toInt() }.toJsonString()
    else -> "\"$this\""
}

object MiniDuckBlockCodec {
    fun encode(block: BlockRowVec): String {
        check(block.state == BlockRowVec.State.SEALED) { "MiniDuck block bodies are written only after sealing" }

        val lines = buildList {
            add(
                linkedMapOf<String, Any?>(
                        "kind" to "MiniDuckBlock",
                        "sealed" to true,
                        "rowCount" to block.rowCount,
                    ).toJsonString(),
            )
            for (i in 0 until block.child.size) {
                add(encodeRow(block.child[i]).toJsonString())
            }
        }
        return lines.joinToString("\n")
    }

    fun decode(text: String): BlockRowVec {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.isNotEmpty()) { "MiniDuck block body is empty" }

        val header = JsonParser.reify(lines.first().toSeries()) as? Map<*, *>
            ?: error("MiniDuck block header must be a JSON object")
        require(header["kind"] == "MiniDuckBlock") { "Unexpected block kind: ${header["kind"]}" }
        require(header["sealed"] == true) { "MiniDuck block body must be sealed" }

        val block = BlockRowVec.mutable()
        lines.drop(1).forEach { line ->
            block.append(decodeRow(JsonParser.reify(line.toSeries())))
        }
        return block.seal()
    }

    private fun encodeRow(row: MiniRowVec): Map<String, Any?> = when (row) {
        is DocRowVec -> linkedMapOf(
            "type" to "DocRowVec",
            "keys" to row.keys,
            "cells" to row.cells,
            "child" to encodeChildren(row.child),
        )
        is ViewRowVec -> linkedMapOf(
            "type" to "ViewRowVec",
            "id" to row.id,
            "key" to row.key,
            "value" to row.value,
            "child" to encodeChildren(row.child),
        )
        is JsonRowVec -> linkedMapOf(
            "type" to "JsonRowVec",
            "nodeType" to row.nodeType,
            "rawValue" to row.rawValue,
            "child" to encodeChildren(row.child),
        )
        is YamlRowVec -> linkedMapOf(
            "type" to "YamlRowVec",
            "nodeKind" to row.nodeKind,
            "scalarValue" to row.scalarValue,
            "child" to encodeChildren(row.child),
        )
        is BlobRowVec -> linkedMapOf(
            "type" to "BlobRowVec",
            "bytes" to row.bytes.map { it.toInt() },
            "mimeType" to row.mimeType,
            "child" to encodeChildren(row.child),
        )
        is BlockRowVec -> linkedMapOf(
            "type" to "BlockRowVec",
            "sealed" to (row.state == BlockRowVec.State.SEALED),
            "child" to encodeChildren(row.child),
        )
        is ManifoldConcept -> linkedMapOf(
            "type" to "ManifoldConcept",
            "angular" to row.angular,
            "budget" to row.budget.packed.toInt(),
            "child" to encodeChildren(row.child),
        )
        else -> linkedMapOf(
            "type" to "Unknown",
            "class" to (row?.toString() ?: "null"),
            "child" to encodeChildren(row?.child),
        )
    }

    private fun decodeRow(value: Any?): MiniRowVec {
        val map = value as? Map<*, *> ?: error("MiniDuck row body must be a JSON object")
        return when (map.string("type")) {
            "DocRowVec" -> DocRowVec(
                keys = map.stringList("keys"),
                cells = map.anyList("cells"),
                child = map.childSeries(),
            )
            "ViewRowVec" -> {
                val childRows = map.childRows()
                ViewRowVec(
                    id = map.string("id"),
                    key = map["key"],
                    value = map["value"],
                    docLoader = childRows.singleOrNull()?.let { child -> { child } },
                )
            }
            "JsonRowVec" -> JsonRowVec(
                nodeType = map.string("nodeType"),
                rawValue = map.string("rawValue"),
                childFactory = map.childRows().takeIf { it.isNotEmpty() }?.let { rows ->
                    { rows.toSeries() }
                },
            )
            "YamlRowVec" -> YamlRowVec(
                nodeKind = map.string("nodeKind"),
                scalarValue = map["scalarValue"] as String?,
                childFactory = map.childRows().takeIf { it.isNotEmpty() }?.let { rows ->
                    { rows.toSeries() }
                },
            )
            "BlobRowVec" -> BlobRowVec(
                bytes = run {
                    val ints = map.intList("bytes")
                    ByteArray(ints.size) { idx -> ints[idx].toByte() }
                },
                mimeType = map["mimeType"] as String?,
                childFactory = map.childRows().takeIf { it.isNotEmpty() }?.let { rows ->
                    { rows.toSeries() }
                },
            )
            "BlockRowVec" -> {
                val block = BlockRowVec.mutable()
                map.childRows().forEach(block::append)
                if (map["sealed"] == true) block.seal()
                else block
            }
            "ManifoldConcept" -> {
                val payload = map.childRows().firstOrNull() ?: DocRowVec(emptyList(), emptyList())
                ManifoldConcept(
                    angular = (map["angular"] as? Number)?.toLong() ?: 0L,
                    budget = BudgetCoord.unpack(((map["budget"] as? Number)?.toInt() ?: 0).toUInt()),
                    payload = payload,
                )
            }
            else -> error("Unsupported MiniDuck row type: ${map["type"]}")
        }
    }

    private fun encodeChildren(child: Series<MiniRowVec>?): List<Any?>? = child?.let { series ->
        buildList {
            for (i in 0 until series.size) add(encodeRow(series[i]))
        }
    }

    private fun Map<*, *>.string(name: String): String = this[name] as? String
        ?: error("Missing string field '$name'")

    private fun Map<*, *>.stringList(name: String): List<String> = this[name].asList(name).map { it as? String ?: error("Field '$name' must contain strings") }

    private fun Map<*, *>.anyList(name: String): List<Any?> = this[name].asList(name)

    private fun Map<*, *>.intList(name: String): List<Int> = this[name].asList(name).map {
        when (it) {
            is Number -> it.toInt()
            else -> error("Field '$name' must contain numbers")
        }
    }

    private fun Map<*, *>.childRows(): List<MiniRowVec> =
        (this["child"] as? List<*>)?.map { decodeRow(it) } ?: emptyList()

    private fun Map<*, *>.childSeries(): Series<MiniRowVec>? {
        val rows = childRows()
        return if (rows.isEmpty()) null else rows.toSeries()
    }

    private fun Any?.asList(name: String): List<Any?> = this as? List<Any?>
        ?: error("Field '$name' must be an array")

    private fun List<MiniRowVec>.toSeries(): Series<MiniRowVec> = size j { idx -> this[idx] }

    private fun unescapeJson(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val next = s[i + 1]
                when (next) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    'u' -> {
                        if (i + 5 <= s.length) {
                            val hex = s.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16) ?: throw Exception("Invalid unicode escape: \\$hex")
                            sb.append(code.toChar())
                            i += 4
                        } else throw Exception("Invalid unicode escape")
                    }
                    else -> sb.append(next)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
