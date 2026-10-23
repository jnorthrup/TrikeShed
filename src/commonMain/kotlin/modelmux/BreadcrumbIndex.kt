@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package modelmux

import borg.trikeshed.cas.LineCas
import borg.trikeshed.cursor.*
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.lib.get
import borg.trikeshed.lib.view
import borg.trikeshed.parse.*
import borg.trikeshed.parse.confix.*
import kotlin.jvm.JvmInline
import kotlin.io.encoding.Base64

@JvmInline
value class BreadcrumbRef(val value: String) {
    init { require(value.length == 4 && value.all { it in "0123456789abcdef" }) }
}

/** Heuristic character ceiling, after instructions, current messages and reserved model output. */
data class BreadcrumbBudget(
    val contextTokens: Int = 2048,
    val instructionTokens: Int = 256,
    val currentTokens: Int = 0,
    val outputTokens: Int = 256,
) {
    init { require(contextTokens > 0 && instructionTokens >= 0 && currentTokens >= 0 && outputTokens >= 0) }
    val chars: Int get() = ((contextTokens.toLong() - instructionTokens - currentTokens - outputTokens)
        .coerceAtLeast(0) * 4).coerceAtMost(64_000).toInt()
}

data class BreadcrumbIndexed(
    val id: BreadcrumbId,
    val cid: ContentId,
    val spine: ContentId,
    val ref: BreadcrumbRef,
    val title: String,
    val source: String,
    val children: Series<BreadcrumbId>,
    val spans: Series<Twin<Int>>,
    val metadata: ConfixDoc,
    val heritage: ConfixDoc?,
)

/**
 * Full blobs in the existing CAS, line-spine fingerprints and a once-scanned Confix metadata trie.
 * The 16-bit alias is a bucket only; a collision can never select another full CAS identity.
 */
class BreadcrumbIndex(val cas: CasStore = CasStore.inMemory()) {
    private val entries = linkedMapOf<BreadcrumbId, BreadcrumbIndexed>()

    fun copy(): BreadcrumbIndex = BreadcrumbIndex(cas).also { it.entries.putAll(entries) }

    /** Every entry, in insertion order: what a table over the index reads. */
    fun all(): Series<BreadcrumbIndexed> = entries.values.toList().toSeries()
    fun restore(from: BreadcrumbIndex) { entries.clear(); entries.putAll(from.entries) }
    operator fun get(id: BreadcrumbId): BreadcrumbIndexed? = entries[id]

    fun put(record: BreadcrumbRecord, title: String = if (record.source.startsWith("summary:")) record.content else record.source,
            heritage: ConfixDoc? = null): BreadcrumbIndexed {
        entries[record.id]?.let { return it }
        val bytes = record.content.encodeToByteArray()
        val cid = ContentId.of(bytes)
        if (!cas.holds(cid)) check(cas.put(bytes) == cid)
        val spine = LineCas.spineCid(LineCas.spine(record.content))
        val ref = reference(cid)
        val children = record.children.toTypedArray().toSeries()
        val spans = mutableListOf<Twin<Int>>()
        var start = 0
        for (index in record.content.indices) if (record.content[index] == '\n') {
            spans += start j (index + 1); start = index + 1
        }
        if (start < record.content.length) spans += start j record.content.length
        val heritageCid = heritage?.let { cas.put(it) }
        val metadata = confixDoc(jsonOf(mapOf(
            "id" to record.id.value, "ref" to ref.value, "cid" to cid.value, "spineCid" to spine.value,
            "title" to title, "source" to record.source, "chars" to record.content.length,
            "createdAt" to record.createdAt.value.toString(),
            "children" to children.view.map { it.value }, "heritageCid" to heritageCid?.value,
        )))
        return BreadcrumbIndexed(record.id, cid, spine, ref, title, record.source, children,
            spans.toTypedArray().toSeries(), metadata, heritage).also { entries[record.id] = it }
    }

    /** Metadata trees retain Confix's typed spans, tags and children; no content tree is copied. */
    fun cursor(): Cursor {
        val rows = entries.values.toTypedArray()
        return rows.size j { rows[it].metadata.root!! }
    }

    fun <T> facet(id: BreadcrumbId, key: ConfixIndexK<T>): T? = entries[id]?.metadata?.index?.facet(key)

    /** Titles and typed non-text provenance survive the ordinary breadcrumb store checkpoint. */
    fun metadataState(): List<Map<String, Any?>> = entries.values.map { entry ->
        mapOf("id" to entry.id.value, "title" to entry.title,
            "heritage" to entry.heritage?.let { Base64.encode(CanonicalCbor.encode(it)) })
    }

    fun restoreMetadata(value: Any?) {
        val rows = value as? List<*> ?: throw IllegalArgumentException("breadcrumb index array required")
        require(rows.size <= BreadcrumbContext.RECORD_LIMIT)
        val seen = mutableSetOf<BreadcrumbId>()
        for (value in rows) {
            val row = value as? Map<*, *> ?: throw IllegalArgumentException("breadcrumb index metadata required")
            require(row.keys == setOf("id", "title", "heritage"))
            val id = BreadcrumbId(row["id"] as? String ?: throw IllegalArgumentException("breadcrumb index id required"))
            require(seen.add(id))
            val entry = requireNotNull(entries[id]) { "breadcrumb index record missing" }
            val title = row["title"] as? String ?: throw IllegalArgumentException("breadcrumb title required")
            require(title.length <= BreadcrumbContext.CONTENT_LIMIT)
            val heritage = row["heritage"]?.let {
                val encoded = it as? String ?: throw IllegalArgumentException("breadcrumb heritage encoding required")
                confixDoc(Base64.decode(encoded), Syntax.CBOR)
            }
            val heritageCid = heritage?.let(cas::put)
            val metadata = confixDoc(jsonOf(mapOf(
                "id" to entry.id.value, "ref" to entry.ref.value, "cid" to entry.cid.value, "spineCid" to entry.spine.value,
                "title" to title, "source" to entry.source, "chars" to entry.metadata.value("chars"),
                "createdAt" to entry.metadata.value("createdAt"), "children" to entry.children.view.map { it.value },
                "heritageCid" to heritageCid?.value,
            )))
            entries[id] = entry.copy(title = title, metadata = metadata, heritage = heritage)
        }
    }

    fun search(query: String, budget: BreadcrumbBudget, limit: Int = 8): String {
        require(query.length <= 256 && limit in 1..32)
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val matches = entries.values.map { entry ->
            val text = if (words.isEmpty()) "" else cas.get(entry.cid)!!.decodeToString().lowercase()
            entry j words.sumOf { word ->
                (if (word in entry.title.lowercase()) 4 else 0) +
                    (if (word in entry.source.lowercase()) 2 else 0) + (if (word in text) 1 else 0)
            }
        }.filter { words.isEmpty() || it.b > 0 }.sortedWith(
            compareByDescending<Join<BreadcrumbIndexed, Int>> { it.b }.thenByDescending { it.a.metadata.value("createdAt").toString() })
        val rows = mutableListOf<Map<String, Any?>>()
        for ((entry, _) in matches.take(limit)) {
            val next = descriptor(entry)
            rows += next
            if (jsonOf(mapOf("matches" to rows, "total" to matches.size, "omitted" to matches.size - rows.size)).length > budget.chars) {
                rows.removeAt(rows.lastIndex); break
            }
        }
        return bounded(mapOf("matches" to rows, "total" to matches.size, "omitted" to matches.size - rows.size), budget)
    }

    fun recall(ref: BreadcrumbRef, cid: ContentId?, presence: BreadcrumbPresence,
               budget: BreadcrumbBudget, query: String = ""): String {
        require(query.length <= 256)
        val candidates = entries.values.filter { it.ref == ref && (cid == null || it.cid == cid) }
        if (candidates.isEmpty()) return bounded(mapOf("error" to "unknown breadcrumb reference"), budget)
        val distinct = candidates.distinctBy { it.cid }
        if (distinct.size > 1) return bounded(mapOf("error" to "ambiguous 16-bit reference; supply cid",
            "candidates" to distinct.map { descriptor(it) }), budget)
        val entry = candidates.last()
        val visited = mutableSetOf<BreadcrumbId>()
        val leaves = linkedMapOf<ContentId, BreadcrumbIndexed>()
        fun lineage(node: BreadcrumbIndexed) {
            if (!visited.add(node.id)) return
            val children = node.children.view.mapNotNull { entries[it] }
            if (children.isEmpty()) {
                if (node.cid !in leaves) leaves[node.cid] = node
            }
            else for (child in children) lineage(child)
        }
        lineage(entry)
        // A cycle or incomplete imported lineage must still resolve its own immutable evidence.
        if (leaves.isEmpty()) leaves[entry.cid] = entry
        val texts = leaves.mapValues { (_, node) ->
            cas.get(node.cid)?.decodeToString() ?: error("Breadcrumb CAS blob is unavailable")
        }
        val totalChars = texts.values.sumOf { it.length }
        val desired = (totalChars.toDouble() * presence.value).toInt()
        val ranked = leaves.values.flatMap { node -> node.spans.view.map { node j it } }.sortedByDescending { (node, span) ->
            if (query.isBlank()) 0 else query.lowercase().split(Regex("\\s+")).count {
                it.isNotBlank() && texts.getValue(node.cid).substring(span.a, span.b).contains(it, ignoreCase = true)
            }
        }
        val heritage = entry.heritage?.let { doc ->
            val spans = doc.index.facet(ConfixIndexK.Spans)
            val tags = doc.index.facet(ConfixIndexK.Tags)
            val depths = doc.index.facet(ConfixIndexK.Depths)
            val children = doc.index.facet(ConfixIndexK.DirectChildren)
            val rows = mutableMapOf<Pair<Int, Int>, RowVec>()
            fun retain(row: RowVec) {
                rows[row.open to row.close] = row
                for (child in row.kids.view) retain(child)
            }
            for (row in doc.roots.view) retain(row)
            mapOf("rangeUnit" to "bytes; end exclusive", "nodes" to spans.view.mapIndexed { index, span ->
                val value = when (tags[index]) {
                    IOMemento.IoArray, IOMemento.IoObject -> null
                    else -> rows.getValue(span.a to span.b).reify(doc.src).let { scalar ->
                        if (scalar is ByteArray) mapOf("encoding" to "base64", "bytes" to scalar.size,
                            "value" to Base64.encode(scalar)) else scalar
                    }
                }
                mapOf("token" to index, "tag" to tags[index].name, "start" to span.a, "end" to span.b + 1,
                    "depth" to depths[index], "children" to children(index).view.toList(), "value" to value)
            })
        }
        fun envelope(chars: Int): Map<String, Any?> {
            var remaining = chars
            val selected = mutableListOf<Map<String, Any?>>()
            for ((node, span) in ranked) {
                if (remaining <= 0) break
                val text = texts.getValue(node.cid)
                val content = text.substring(span.a, span.b).breadcrumbPrefix(remaining)
                if (content.isNotEmpty()) selected += mapOf("id" to node.id.value, "cid" to node.cid.value,
                    "start" to span.a, "end" to span.a + content.length, "content" to content)
                remaining -= content.length
            }
            selected.sortBy { it.getValue("start") as Int }
            val extent = chars - remaining
            return descriptor(entry) + mapOf(
                "presence" to presence.value, "selectedChars" to extent, "omittedChars" to totalChars - extent,
                "ranges" to selected, "rangeUnit" to "UTF-16 code units",
                "budgetChars" to budget.chars, "budgetHeuristic" to "4 characters/token after instructions, input and output reserve",
                "provenance" to leaves.values.map { mapOf("id" to it.id.value, "cid" to it.cid.value, "source" to it.source) },
                "heritage" to heritage,
            )
        }
        var low = 0
        var high = minOf(desired, budget.chars)
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (jsonOf(envelope(middle)).length <= budget.chars) low = middle else high = middle - 1
        }
        return bounded(envelope(low), budget)
    }

    fun descriptor(entry: BreadcrumbIndexed): Map<String, Any?> = mapOf(
        "id" to entry.id.value, "ref" to entry.ref.value, "cid" to entry.cid.value,
        "spineCid" to entry.spine.value, "title" to entry.title.breadcrumbPrefix(600),
        "source" to entry.source.breadcrumbPrefix(160), "children" to entry.children.view.map { child ->
            entries[child]?.let { mapOf("id" to child.value, "ref" to it.ref.value, "cid" to it.cid.value) }
                ?: mapOf("id" to child.value)
        }, "heritageCid" to entry.metadata.value("heritageCid"),
    )

    companion object {
        fun reference(cid: ContentId): BreadcrumbRef =
            BreadcrumbRef(ContentId.of(cid.value.encodeToByteArray()).hex.take(4))

        fun bounded(value: Map<String, Any?>, budget: BreadcrumbBudget): String {
            val json = jsonOf(value)
            if (json.length <= budget.chars) return json
            val error = "{\"error\":\"insufficient context budget for reference metadata\"}"
            return if (error.length <= budget.chars) error else if (budget.chars >= 2) "{}" else ""
        }
    }
}
