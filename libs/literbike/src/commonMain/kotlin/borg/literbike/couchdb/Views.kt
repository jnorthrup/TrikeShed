package borg.literbike.couchdb

import kotlinx.datetime.Clock
import kotlinx.serialization.json.*
import kotlin.collections.TreeMap

/**
 * View server for CouchDB map/reduce functionality
 */
class ViewServer(
    private val config: ViewServerConfig = ViewServerConfig.default()
) {
    private val views: MutableMap<String, CompiledView> = mutableMapOf()
    private val javascriptEngine: JavaScriptEngine? = if (config.enableJavascript) JavaScriptEngine() else null

    companion object {
        fun new(config: ViewServerConfig = ViewServerConfig.default()) = ViewServer(config)
    }

    /**
     * Update view index for a design document
     */
    fun updateViewIndex(db: DatabaseInstance, designDoc: DesignDocument): CouchResult<Unit> {
        designDoc.views?.forEach { (viewName, viewDef) ->
            val viewKey = "${designDoc.id}:$viewName"
            val compiledView = compileView(db, designDoc, viewName, viewDef)
            compiledView.onSuccess { views[viewKey] = it }
        }
        return Result.success(Unit)
    }

    /**
     * Compile a view and build its index
     */
    private fun compileView(
        db: DatabaseInstance,
        designDoc: DesignDocument,
        viewName: String,
        viewDef: ViewDefinition
    ): CouchResult<CompiledView> {
        val index = mutableMapOf<String, MutableList<ViewIndexEntry>>()

        // Get all documents and apply map function
        val allDocs = db.getAllDocuments(ViewQuery(includeDocs = true))
        val currentSeq = allDocs.getOrNull()?.updateSeq ?: 0uL

        javascriptEngine?.let { engine ->
            allDocs.getOrNull()?.rows?.forEach { row ->
                row.doc?.let { doc ->
                    // Skip design documents in regular views
                    if (doc.id.startsWith("_design/")) return@forEach

                    // Execute map function
                    engine.executeMap(viewDef.map, doc).onSuccess { mapResults ->
                        mapResults.forEach { mapResult ->
                            val entry = ViewIndexEntry(
                                docId = doc.id,
                                key = mapResult.key,
                                value = mapResult.value,
                                docSeq = currentSeq // Simplified - should be actual doc seq
                            )

                            val keyString = mapResult.key.toString()
                            index.getOrPut(keyString) { mutableListOf() }.add(entry)
                        }
                    }
                }
            }
        }

        return Result.success(CompiledView(
            designDocId = designDoc.id,
            viewName = viewName,
            mapFunction = viewDef.map,
            reduceFunction = viewDef.reduce,
            compiledAt = Clock.System.now(),
            lastSeq = currentSeq,
            index = index
        ))
    }

    /**
     * Query a view
     */
    fun queryView(
        designDocId: String,
        viewName: String,
        query: ViewQuery
    ): CouchResult<ViewResult> {
        val viewKey = "$designDocId:$viewName"
        val view = views[viewKey]
            ?: return Result.failure(CouchException(CouchError.notFound("View not found: $designDocId/$viewName")))

        // Apply query filters and limits
        val results = mutableListOf<ViewRow>()
        val limit = query.limit?.toInt() ?: 25
        val skip = query.skip?.toInt() ?: 0
        val includeDocs = query.includeDocs ?: false
        val descending = query.descending ?: false

        // Get keys in order
        val keys = if (descending) view.index.keys.toList().asReversed() else view.index.keys.toList()

        // Pre-convert query keys to strings for comparison
        val startkeyStr = query.startkey?.toString()
        val endkeyStr = query.endkey?.toString()
        val keyFilterStr = query.key?.toString()

        // Apply key range filters
        val filteredKeys = keys.filter { key ->
            // Filter by startkey
            startkeyStr?.let { startkey ->
                if (descending) {
                    if (key > startkey) return@filter false
                } else if (key < startkey) return@filter false
            }

            // Filter by endkey
            endkeyStr?.let { endkey ->
                if (descending) {
                    if (key < endkey) return@filter false
                } else if (key > endkey) return@filter false
            }

            // Filter by specific key
            keyFilterStr?.let { keyFilter ->
                return@filter key == keyFilter
            }

            true
        }

        // Handle reduce
        if (query.reduce == true && view.reduceFunction != null) {
            return executeReduceQuery(view, filteredKeys, query)
        }

        // Collect matching entries
        var currentSkip = 0
        for (key in filteredKeys) {
            view.index[key]?.forEach { entry ->
                if (currentSkip < skip) {
                    currentSkip++
                    return@forEach
                }

                if (results.size >= limit) return@forEach

                results.add(ViewRow(
                    id = entry.docId,
                    key = entry.key,
                    value = entry.value,
                    doc = if (includeDocs) null else null // Simplified for demo
                ))
            }

            if (results.size >= limit) break
        }

        return Result.success(ViewResult(
            totalRows = view.index.values.sumOf { it.size }.toULong(),
            offset = skip.toUInt(),
            rows = results,
            updateSeq = view.lastSeq,
            nextCursor = null // TODO: Implement cursor support
        ))
    }

    /**
     * Execute reduce query
     */
    private fun executeReduceQuery(
        view: CompiledView,
        keys: List<String>,
        query: ViewQuery
    ): CouchResult<ViewResult> {
        val reduceFunction = view.reduceFunction
            ?: return Result.failure(CouchException(CouchError.badRequest("View does not have a reduce function")))

        val engine = javascriptEngine
            ?: return Result.failure(CouchException(CouchError.internalServerError("JavaScript engine not available")))

        val group = query.group ?: false
        val groupLevel = query.groupLevel

        return if (group || groupLevel != null) {
            executeGroupReduce(engine, view, keys, reduceFunction, groupLevel)
        } else {
            executeGlobalReduce(engine, view, keys, reduceFunction)
        }
    }

    /**
     * Execute global reduce (no grouping)
     */
    private fun executeGlobalReduce(
        engine: JavaScriptEngine,
        view: CompiledView,
        keys: List<String>,
        reduceFunction: String
    ): CouchResult<ViewResult> {
        val allKeys = mutableListOf<JsonElement>()
        val allValues = mutableListOf<JsonElement>()

        keys.forEach { key ->
            view.index[key]?.forEach { entry ->
                allKeys.add(entry.key)
                allValues.add(entry.value)
            }
        }

        val result = engine.executeReduce(reduceFunction, allKeys, allValues, rereduce = false)

        val row = ViewRow(
            id = null,
            key = JsonNull,
            value = result.getOrNull()?.value ?: JsonNull,
            doc = null
        )

        return Result.success(ViewResult(
            totalRows = 1uL,
            offset = 0u,
            rows = listOf(row),
            updateSeq = view.lastSeq,
            nextCursor = null
        ))
    }

    /**
     * Execute group reduce
     */
    private fun executeGroupReduce(
        engine: JavaScriptEngine,
        view: CompiledView,
        keys: List<String>,
        reduceFunction: String,
        groupLevel: UInt?
    ): CouchResult<ViewResult> {
        val groups = mutableMapOf<String, Pair<MutableList<JsonElement>, MutableList<JsonElement>>>()

        keys.forEach { key ->
            view.index[key]?.forEach { entry ->
                val groupKey = if (groupLevel != null) {
                    getGroupKey(entry.key, groupLevel).toString()
                } else {
                    entry.key.toString()
                }

                val (groupKeys, groupValues) = groups.getOrPut(groupKey) { mutableListOf() to mutableListOf() }
                groupKeys.add(entry.key)
                groupValues.add(entry.value)
            }
        }

        val results = groups.mapNotNull { (groupKeyStr, pair) ->
            val (groupKeys, groupValues) = pair
            val result = engine.executeReduce(reduceFunction, groupKeys, groupValues, rereduce = false).getOrNull()
                ?: return@mapNotNull null

            val groupKey = runCatching { Json.decodeFromString<JsonElement>(groupKeyStr) }.getOrNull() ?: JsonNull

            ViewRow(
                id = null,
                key = groupKey,
                value = result.value,
                doc = null
            )
        }

        return Result.success(ViewResult(
            totalRows = results.size.toULong(),
            offset = 0u,
            rows = results,
            updateSeq = view.lastSeq,
            nextCursor = null
        ))
    }

    /**
     * Get group key for a given level
     */
    private fun getGroupKey(key: JsonElement, level: UInt): JsonElement {
        return if (key is JsonArray) {
            JsonArray(key.toList().take(level.toInt()))
        } else {
            key
        }
    }

    /**
     * Get view server statistics
     */
    fun getStats(): Map<String, JsonElement> {
        val totalIndexSize = views.values.sumOf { view ->
            view.index.values.sumOf { it.size }
        }

        return mapOf(
            "total_views" to JsonPrimitive(views.size),
            "javascript_enabled" to JsonPrimitive(config.enableJavascript),
            "total_index_entries" to JsonPrimitive(totalIndexSize)
        )
    }

    /**
     * Clear all view caches
     */
    fun clearCaches() {
        views.clear()
    }
}

/**
 * View server configuration
 */
data class ViewServerConfig(
    val enableJavascript: Boolean = true,
    val maxMapResults: Int = 10000,
    val maxReduceDepth: UInt = 10u,
    val timeoutMs: ULong = 30000uL,
    val cacheViews: Boolean = true,
    val allowBuiltinReduces: Boolean = true
) {
    companion object {
        fun default() = ViewServerConfig()
    }
}

/**
 * Compiled view with cached map/reduce functions
 */
data class CompiledView(
    val designDocId: String,
    val viewName: String,
    val mapFunction: String,
    val reduceFunction: String?,
    val compiledAt: kotlinx.datetime.Instant,
    val lastSeq: ULong,
    val index: MutableMap<String, MutableList<ViewIndexEntry>>
)

/**
 * View index entry
 */
data class ViewIndexEntry(
    val docId: String,
    val key: JsonElement,
    val value: JsonElement,
    val docSeq: ULong
)

/**
 * Map result from a single document
 */
data class MapResult(
    val key: JsonElement,
    val value: JsonElement
)

/**
 * Reduce result
 */
data class ReduceResult(
    val key: JsonElement?,
    val value: JsonElement
)

/**
 * JavaScript engine wrapper (simplified mock implementation)
 */
class JavaScriptEngine {
    val contextId: String = generateUuid()

    /**
     * Execute map function on a document
     */
    fun executeMap(mapFunction: String, doc: Document): CouchResult<List<MapResult>> {
        // This is a simplified implementation
        // In a real implementation, you'd use a JavaScript engine like V8 or SpiderMonkey

        val results = when {
            // Simple key-value mapping
            "emit(doc._id, doc)" in mapFunction -> listOf(MapResult(
                key = JsonPrimitive(doc.id),
                value = doc.data
            ))

            // Map by document type
            "doc.type" in mapFunction -> {
                doc.data["type"]?.let { docType ->
                    listOf(MapResult(
                        key = docType,
                        value = JsonPrimitive(doc.id)
                    ))
                } ?: emptyList()
            }

            // Map all documents
            "emit(null, 1)" in mapFunction -> listOf(MapResult(
                key = JsonNull,
                value = JsonPrimitive(1)
            ))

            // Custom date-based mapping
            "doc.created_at" in mapFunction -> {
                doc.data["created_at"]?.let { createdAt ->
                    listOf(MapResult(
                        key = createdAt,
                        value = buildJsonObject {
                            put("id", JsonPrimitive(doc.id))
                            put("rev", JsonPrimitive(doc.rev))
                        }
                    ))
                } ?: emptyList()
            }

            else -> {
                emptyList()
            }
        }

        return Result.success(results)
    }

    /**
     * Execute reduce function on mapped results
     */
    fun executeReduce(
        reduceFunction: String,
        keys: List<JsonElement>,
        values: List<JsonElement>,
        rereduce: Boolean
    ): CouchResult<ReduceResult> {
        val result = when (reduceFunction.trim()) {
            // Count reduce
            "_count", "function(keys, values, rereduce) { return values.length; }" -> {
                ReduceResult(
                    key = null,
                    value = JsonPrimitive(values.size)
                )
            }

            // Sum reduce: builtin "_sum" or any custom function containing "sum"
            else -> if (reduceFunction == "_sum" || "sum" in reduceFunction) {
                val sum = values.mapNotNull { it.jsonPrimitive.doubleOrNull }.sum()
                ReduceResult(
                    key = null,
                    value = JsonPrimitive(sum)
                )
            } else if (reduceFunction == "_stats") {
                val numbers = values.mapNotNull { it.jsonPrimitive.doubleOrNull }

                if (numbers.isEmpty()) {
                    ReduceResult(
                        key = null,
                        value = buildJsonObject {
                            put("sum", JsonPrimitive(0))
                            put("count", JsonPrimitive(0))
                            put("min", JsonNull)
                            put("max", JsonNull)
                            put("sumsqr", JsonPrimitive(0))
                        }
                    )
                } else {
                    val sum = numbers.sum()
                    val count = numbers.size
                    val min = numbers.minOrNull() ?: 0.0
                    val max = numbers.maxOrNull() ?: 0.0
                    val sumsqr = numbers.map { it * it }.sum()

                    ReduceResult(
                        key = null,
                        value = buildJsonObject {
                            put("sum", JsonPrimitive(sum))
                            put("count", JsonPrimitive(count))
                            put("min", JsonPrimitive(min))
                            put("max", JsonPrimitive(max))
                            put("sumsqr", JsonPrimitive(sumsqr))
                        }
                    )
                }
            } else {
                ReduceResult(
                    key = null,
                    value = JsonNull
                )
            }
        }

        return Result.success(result)
    }

    private fun generateUuid(): String {
        return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace("[xy]".toRegex()) { match ->
            val r = (Math.random() * 16).toInt()
            val v = if (match.value == "x") r else (r and 0x3 or 0x8)
            v.toString(16)
        }
    }
}
