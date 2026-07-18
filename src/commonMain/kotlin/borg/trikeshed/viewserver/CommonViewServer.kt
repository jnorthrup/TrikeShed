package borg.trikeshed.viewserver

/** JSON value subset accepted by the CouchDB JSON-lines view-server protocol. */
sealed interface ViewValue {
    data object Null : ViewValue
    data class Text(val value: String) : ViewValue
    data class Number(val value: Double) : ViewValue
    data class Bool(val value: Boolean) : ViewValue
    data class ArrayValue(val values: List<ViewValue>) : ViewValue
    data class ObjectValue(val fields: Map<String, ViewValue>) : ViewValue
}

data class ViewEmission(val key: ViewValue, val value: ViewValue)

/**
 * Common view-server engine. Node.js owns only line I/O and JSON conversion.
 *
 * Functions are deliberately captured as analyzable `emit(doc.x, doc.y)` plans;
 * arbitrary JavaScript is never evaluated.
 */
class CommonViewServer(
    tools: List<ViewServerTool> = listOf(CouchDbCascadeTool),
) {
    private val toolsById = tools.associateBy { it.id }
    private val mappers = mutableListOf<ViewServerMapper>()

    val functionCount: Int get() = mappers.size

    fun reset() {
        mappers.clear()
    }

    fun addFunction(source: String) {
        if (source.startsWith(TOOL_PREFIX)) {
            val selector = source.removePrefix(TOOL_PREFIX)
            val toolId = selector.substringBefore('/')
            val mapperName = selector.substringAfter('/', missingDelimiterValue = "")
            require(mapperName.isNotEmpty()) { "tool mapper selector must be tool:<id>/<mapper>" }
            mappers += resolveTool(toolId).mapper(mapperName)
            return
        }
        val scalar = EMIT_PATTERN.find(source)
        if (scalar != null) {
            val keyField = scalar.groupValues[1]
            val valueField = scalar.groupValues[2]
            mappers += ViewServerMapper { document ->
                listOf(
                    ViewEmission(
                        key = document[keyField] ?: ViewValue.Null,
                        value = document[valueField] ?: ViewValue.Null,
                    ),
                )
            }
            return
        }
        throw IllegalArgumentException("map function must contain emit(doc.<key>, doc.<value>)")
    }

    fun mapDocument(document: Map<String, ViewValue>): List<List<ViewEmission>> =
        mappers.map { it.map(document) }

    /** CouchDB 1.x built-in reduce functions over Confix JSON values. */
    fun reduce(name: String, values: List<ViewValue>): ViewValue {
        if (name.startsWith(TOOL_PREFIX)) {
            return resolveTool(name.removePrefix(TOOL_PREFIX)).reduce(values)
        }
        return when (name) {
            "_count" -> ViewValue.Number(values.size.toDouble())
            "_sum" -> ViewValue.Number(values.sumOf { it.numberOrZero() })
            "_stats" -> stats(values.map { it.numberOrZero() })
            else -> throw IllegalArgumentException("unsupported reducer: $name")
        }
    }

    /** Combine prior built-in reduction results without replaying source rows. */
    fun rereduce(name: String, values: List<ViewValue>): ViewValue {
        if (name.startsWith(TOOL_PREFIX)) {
            return resolveTool(name.removePrefix(TOOL_PREFIX)).rereduce(values)
        }
        return when (name) {
            "_count", "_sum" -> ViewValue.Number(values.sumOf { it.numberOrZero() })
            "_stats" -> combineStats(values)
            else -> throw IllegalArgumentException("unsupported reducer: $name")
        }
    }

    private fun resolveTool(id: String): ViewServerTool =
        toolsById[id] ?: throw IllegalArgumentException("unknown view-server tool: $id")

    private fun stats(values: List<Double>): ViewValue.ObjectValue {
        val sum = values.sum()
        return ViewValue.ObjectValue(
            mapOf(
                "sum" to ViewValue.Number(sum),
                "count" to ViewValue.Number(values.size.toDouble()),
                "min" to ViewValue.Number(values.minOrNull() ?: 0.0),
                "max" to ViewValue.Number(values.maxOrNull() ?: 0.0),
                "sumsqr" to ViewValue.Number(values.sumOf { it * it }),
            ),
        )
    }

    private fun combineStats(values: List<ViewValue>): ViewValue.ObjectValue {
        val parts = values.mapNotNull { it as? ViewValue.ObjectValue }
        val count = parts.sumOf { it.fields["count"].numberOrZero() }
        val sum = parts.sumOf { it.fields["sum"].numberOrZero() }
        val sumsqr = parts.sumOf { it.fields["sumsqr"].numberOrZero() }
        val mins = parts.mapNotNull { (it.fields["min"] as? ViewValue.Number)?.value }
        val maxes = parts.mapNotNull { (it.fields["max"] as? ViewValue.Number)?.value }
        return ViewValue.ObjectValue(
            mapOf(
                "sum" to ViewValue.Number(sum),
                "count" to ViewValue.Number(count),
                "min" to ViewValue.Number(mins.minOrNull() ?: 0.0),
                "max" to ViewValue.Number(maxes.maxOrNull() ?: 0.0),
                "sumsqr" to ViewValue.Number(sumsqr),
            ),
        )
    }

    private companion object {
        val EMIT_PATTERN = Regex(
            """emit\s*\(\s*doc\.([A-Za-z_][A-Za-z0-9_]*)\s*,\s*doc\.([A-Za-z_][A-Za-z0-9_]*)\s*\)""",
        )
        const val TOOL_PREFIX = "tool:"
    }
}

private fun ViewValue?.numberOrZero(): Double =
    (this as? ViewValue.Number)?.value ?: 0.0
