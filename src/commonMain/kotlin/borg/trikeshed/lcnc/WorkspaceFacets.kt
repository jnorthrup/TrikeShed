package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.collections.associative.toAny
import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.parse.ValueBudget
import borg.trikeshed.parse.reify

/** The workspace selects projections of one program; a facet never owns an executor. */
enum class WorkspaceFacet(val id: String, val title: String, val description: String) {
    CURATION("curation", "Use cases", "Document and tuple curation, shared evidence, and role-based simulations."),
    CONFIX("confix", "Confix & facets", "Inspect the program's indexed structure, cursor rows, and content identity."),
    LANGUAGE("language", "Low code", "Place language and Camel nodes backed by the host's installed runtimes."),
    EXECUTION("execution", "Evidence", "Follow node bindings, causal evidence, pointcuts, and run receipts."),
}

/** Row-axis inspection of Confix's existing tree cursor, not an ISAM schema. */
data class CursorFacetQuery(
    val path: Series<Int> = emptySeriesOf(),
    val axes: Series<IntArray> = s_[intArrayOf(2)],
    val selections: Series<Twin<Int>> = emptySeriesOf(),
    val offset: Int = 0,
    val groupOffset: Int = 0,
    val sourceCid: String? = null,
)

/** Bounded projection of the real Confix index, retaining byte offsets and child indices. */
object WorkspaceFacets {
    const val MAX_BYTES = 1_048_576
    const val MAX_ROWS = 128
    const val MAX_TOKENS = 65_536
    const val MAX_VALUE_BYTES = 16_384

    fun valueBudget(): ValueBudget = ValueBudget(
        maxNodes = MAX_TOKENS, maxChars = MAX_BYTES, maxDepth = 64, maxMillis = 250,
    )

    fun catalog(): Series<Map<String, String>> = WorkspaceFacet.entries.toSeries() α {
        mapOf("id" to it.id, "title" to it.title, "description" to it.description)
    }

    fun inspect(source: String, path: Series<Any> = emptySeriesOf(), offset: Int = 0, limit: Int = 32,
                cursor: CursorFacetQuery = CursorFacetQuery()): Map<String, Any?> {
        val bytes = source.encodeToByteArray()
        require(bytes.size <= MAX_BYTES) { "Workspace document exceeds $MAX_BYTES bytes" }
        return inspect(confixDoc(bytes, Syntax.JSON), Syntax.JSON, path, offset, limit, cursor)
    }

    /** The same facets over persisted geometry; no JSON rescan or scalar ISAM schema inference. */
    fun inspect(doc: ConfixDoc, syntax: Syntax, path: Series<Any> = emptySeriesOf(), offset: Int = 0,
                limit: Int = 32, cursor: CursorFacetQuery = CursorFacetQuery()): Map<String, Any?> {
        require(doc.src.size <= MAX_BYTES) { "Workspace document exceeds $MAX_BYTES bytes" }
        require(offset >= 0 && limit in 1..MAX_ROWS) { "Invalid facet window" }
        require(path.size <= 32 && path.view.all { it is String || it is Int && it >= 0 }) { "Invalid Confix path" }
        val bytes = ByteArray(doc.src.size) { doc.src[it] }
        val cid = ContentId.of(bytes).value
        require(cursor.sourceCid == null || cursor.sourceCid == cid) { "Cursor source changed; refresh its indexes" }
        val index = doc.index
        val spans = index.facet(ConfixIndexK.Spans)
        require(spans.size <= MAX_TOKENS) { "work_limit" }
        val tags = index.facet(ConfixIndexK.Tags)
        val depths = index.facet(ConfixIndexK.Depths)
        val children = index.facet(ConfixIndexK.DirectChildren)
        require((depths.view.maxOrNull() ?: 0) <= 64) { "Confix inspection exceeds depth limit" }
        val structural = index.facet(ConfixIndexK.StructuralNodes)
        val end = minOf(spans.size.toLong(), offset.toLong() + limit).toInt()
        val rows = maxOf(0, end - offset) j { row: Int ->
            val i = offset + row
            mapOf("token" to i, "open" to spans[i].a, "close" to spans[i].b,
                "tag" to tags[i].name, "depth" to depths[i],
                "children" to children(i).take(MAX_ROWS).view.toList(), "childCount" to children(i).size, "cid" to structural[i])
        }
        val steps = path.view.toList().toTypedArray()
        val selected = doc.docAt(*steps)
        val valueBytes = selected?.let { it.row.close - it.row.open + 1 } ?: 0
        val valueOmitted = valueBytes > MAX_VALUE_BYTES
        return mapOf(
            "cid" to cid, "bytes" to bytes.size, "syntax" to syntax.name,
            "tokens" to spans.size, "roots" to doc.roots.size,
            "maxDepth" to (depths.view.maxOrNull() ?: 0),
            "keys" to listOf("Spans", "Tags", "Depths", "DirectChildren", "TreeCursor", "KeyToChild", "StructuralNodes"),
            "offset" to offset, "rows" to rows.view.toList(), "next" to if (end < spans.size) end else null,
            "path" to path.view.toList(), "found" to (selected != null),
            "valueOmitted" to valueOmitted, "valueBytes" to valueBytes, "valueLimit" to MAX_VALUE_BYTES,
            // Bound the selected source span before materializing its value at the response boundary.
            "value" to selected?.takeUnless { valueOmitted }?.let {
                if (syntax == Syntax.JSON) reify(bytes.copyOfRange(it.row.open, it.row.close + 1).decodeToString())
                else jsonValue(CanonicalCbor.item(it).toAny())
            },
            "cursor" to cursorFacet(doc, cursor, limit),
        )
    }

    /** JSON has neither byte strings nor non-finite numbers; retain their kind in the display value. */
    fun jsonValue(value: Any?): Any? = when (value) {
        is ByteArray -> mapOf("type" to "bytes", "value" to value.map { it.toInt() and 255 })
        is Double -> if (value.isFinite()) value else mapOf("type" to "float", "value" to value.toString())
        is Float -> if (value.isFinite()) value else mapOf("type" to "float", "value" to value.toString())
        is Map<*, *> -> value.mapValues { jsonValue(it.value) }
        is List<*> -> value.map(::jsonValue)
        else -> value
    }

    fun cursorFacet(doc: ConfixDoc, query: CursorFacetQuery, limit: Int): Map<String, Any?> {
        require(limit in 1..MAX_ROWS && query.offset >= 0 && query.groupOffset >= 0) { "Invalid cursor window" }
        require(query.path.size <= 32 && query.path.view.all { it >= 0 }) { "Invalid cursor path" }
        require(query.axes.size in 1..4 && query.axes.view.all { axis ->
            axis.size in 1..3 && axis.all { it in 0..2 } && axis.distinct().size == axis.size
        }) { "Index columns must be distinct open (0), close (1), or kind (2) ordinals; at most four indexes" }
        require(query.selections.size <= 4) { "At most four index selections" }
        var base = CursorScope(doc.roots).index()
        for (row in query.path.view) base = base.descend(row, 3) { it.kids }
        // Construct each independent index once; selections intersect in the same source scope.
        val indexes = Array(query.axes.size) { base.groups(*query.axes[it]) }
        var selected = base
        for ((index, group) in query.selections.view) {
            require(index in indexes.indices && group in 0 until indexes[index].size) { "Index selection outside cursor" }
            selected = selected.intersect(indexes[index][group])
        }
        val rows = selected.cursor
        fun endAt(offset: Int, size: Int) = minOf(size.toLong(), offset.toLong() + limit).toInt()
        val rowEnd = endAt(query.offset, rows.size)
        val window = maxOf(0, rowEnd - query.offset) j { i: Int ->
            val row = rows[query.offset + i]
            mapOf("ordinal" to selected.sourceRow(query.offset + i), "open" to row.open, "close" to row.close,
                "tag" to row.tag.name, "childCount" to row.kids.size)
        }
        return mapOf(
            "path" to query.path.view.toList(), "columns" to listOf("open", "close", "kind", "children"),
            "rowCount" to base.size, "selectedCount" to selected.size, "offset" to query.offset,
            "rows" to window.view.toList(), "next" to if (rowEnd < rows.size) rowEnd else null,
            "indexes" to indexes.mapIndexed { i, groups ->
                val stop = endAt(query.groupOffset, groups.size)
                val page = maxOf(0, stop - query.groupOffset) j { n: Int ->
                    val group = groups[query.groupOffset + n]
                    val exemplar = group.cursor[0]
                    mapOf("group" to query.groupOffset + n,
                        "key" to query.axes[i].map { column ->
                            val value = exemplar.values[column]
                            if (value is IOMemento) value.name else value
                        },
                        "count" to group.size, "ordinals" to group.ordinals.take(limit).view.toList(),
                        "truncated" to (group.size > limit))
                }
                mapOf("columns" to query.axes[i].toList(), "groupCount" to groups.size,
                    "offset" to query.groupOffset, "groups" to page.view.toList(),
                    "next" to if (stop < groups.size) stop else null)
            },
        )
    }
}
