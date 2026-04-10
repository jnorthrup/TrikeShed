package borg.literbike.ccek.store.couchdb

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * View server for CouchDB map/reduce functionality
 */
class ViewServer(
    private val config: ViewServerConfig
) {
    private val views = mutableMapOf<String, CompiledView>()
    private val viewsMutex = Mutex()
    private val javascriptEngine: JavaScriptEngine? = if (config.enableJavascript) JavaScriptEngine.new().getOrNull() else null

    companion object {
        fun new(config: ViewServerConfig = ViewServerConfig.default()): CouchResult<ViewServer> {
            Result.success(ViewServer(config))
        }
    }

    /**
     * Update view index for a design document
     */
    suspend fun updateViewIndex(
        db: DatabaseInstance,
        designDoc: DesignDocument
    ): CouchResult<Unit> {
        designDoc.views?.forEach { (viewName, viewDef) ->
            val viewKey = "${designDoc.id}:$viewName"

            val compiledView = compileView(db, designDoc, viewName, viewDef).getOrThrow()

            viewsMutex.withLock {
                views[viewKey] = compiledView
            }
        }

        return Result.success(Unit)
    }

    /**
     * Compile a view and build its index
     */
    private suspend fun compileView(
        db: DatabaseInstance,
        designDoc: DesignDocument,
        viewName: String,
        viewDef: ViewDefinition
    ): CouchResult<CompiledView> {
        val index = mutableMapOf<String, MutableList<ViewIndexEntry>>()

        // Get all documents and apply map function
        val allDocs = db.getAllDocuments(
            ViewQuery(
                includeDocs = true,
                conflicts = false
            )
        ).getOrThrow()

        val currentSeq = allDocs.updateSeq?.toLong() ?: 0L

        javascriptEngine?.let { engine ->
            for (row in allDocs.rows) {
                row.doc?.let { doc ->
                    // Skip design documents in regular views
                    if (doc.id.startsWith("_design/")) {
                        continue
                    }

                    // Execute map function
                    engine.executeMap(viewDef.map, doc).fold(
                        onSuccess = { mapResults ->
                            for (mapResult in mapResults) {
                                val entry = ViewIndexEntry(
                                    docId = doc.id,
                                    key = mapResult.key,
                                    value = mapResult.value,
                                    docSeq = currentSeq
                                )

                                index.getOrPut(mapResult.key) { mutableListOf() }.add(entry)
                            }
                        },
                        onFailure = {
                            // Map function error for doc
                        }
                    )
                }
            }
        }

        return Result.success(
            CompiledView(
                designDocId = designDoc.id,
                viewName = viewName,
                mapFunction = viewDef.map,
                reduceFunction = viewDef.reduce,
                compiledAt = Clock.System.INSTANT,
                lastSeq = currentSeq.toULong(),
                index = index
            )
        )
    }

    /**
     * Query a view
     */
    suspend fun queryView(
        designDocId: String,
        viewName: String,
        query: ViewQuery
    ): CouchResult<ViewResult> {
        val viewKey = "$designDocId:$viewName"

        val view = viewsMutex.withLock {
            views[viewKey]
        } ?: return Result.failure(
            CouchError.notFound("View not found: $designDocId/$viewName")
        )

        // Apply query filters and limits
        val results = mutableListOf<ViewRow>()
        val limit = (query.limit ?: 25u).toInt()
        val skip = (query.skip ?: 0u).toInt()
        val includeDocs = query.includeDocs ?: false
        val descending = query.descending ?: false

        // Get keys in order
        val keys = if (descending) {
            view.index.keys.toList().asReversed()
        } else {
            view.index.keys.toList()
        }

        // Apply key range filters
        val filteredKeys = keys.filter { key ->
            // Filter by startkey
            query.startkey?.let { startkey ->
                if (descending) {
                    if (key > startkey) return@filter false
                } else {
                    if (key < startkey) return@filter false
                }
            }

            // Filter by endkey
            query.endkey?.let { endkey ->
                if (descending) {
                    if (key < endkey) return@filter false
                } else {
                    if (key > endkey) return@filter false
                }
            }

            // Filter by specific key
            query.key?.let { keyFilter ->
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
            view.index[key]?.let { entries ->
                for (entry in entries) {
                    if (currentSkip < skip) {
                        currentSkip++
                        continue
                    }

                    if (results.size >= limit) {
                        break
                    }

                    val row = ViewRow(
                        id = entry.docId,
                        key = entry.key,
                        value = entry.value,
                        doc = if (includeDocs) null else null // Would fetch in real impl
                    )

                    results.add(row)
                }

                if (results.size >= limit) {
                    break
                }
            }
        }

        return Result.success(
            ViewResult(
                totalRows = view.index.values.sumOf { it.size }.toULong(),
                offset = skip.toUInt(),
                rows = results,
                updateSeq = view.lastSeq,
                nextCursor = null
            )
        )
    }

    /**
     * Execute reduce query
     */
    private suspend fun executeReduceQuery(
        view: CompiledView,
        keys: List<String>,
        query: ViewQuery
    ): CouchResult<ViewResult> {
        val reduceFunction = view.reduceFunction
            ?: return Result.failure(CouchError.badRequest("View does not have a reduce function"))

        val engine = javascriptEngine
            ?: return Result.failure(CouchError.internalServerError("JavaScript engine not available"))

        val group = query.group ?: false
        val groupLevel = query.groupLevel

        if (group || groupLevel != null) {
            return executeGroupReduce(engine, view, keys, reduceFunction, groupLevel)
        } else {
            return executeGlobalReduce(engine, view, keys, reduceFunction)
        }
    }

    /**
     * Execute global reduce (no grouping)
     */
    private suspend fun executeGlobalReduce(
        engine: JavaScriptEngine,
        view: CompiledView,
        keys: List<String>,
        reduceFunction: String
    ): CouchResult<ViewResult> {
        val allKeys = mutableListOf<String>()
        val allValues = mutableListOf<String>()

        for (key in keys) {
            view.index[key]?.let { entries ->
                for (entry in entries) {
                    allKeys.add(entry.key)
                    allValues.add(entry.value)
                }
            }
        }

        val result = engine.executeReduce(reduceFunction, allKeys, allValues, false).getOrThrow()

        val row = ViewRow(
            id = null,
            key = "null",
            value = result.value,
            doc = null
        )

        return Result.success(
            ViewResult(
                totalRows = 1u,
                offset = 0u,
                rows = listOf(row),
                updateSeq = view.lastSeq,
                nextCursor = null
            )
        )
    }

    /**
     * Execute group reduce
     */
    private suspend fun executeGroupReduce(
        engine: JavaScriptEngine,
        view: CompiledView,
        keys: List<String>,
        reduceFunction: String,
        groupLevel: UInt?
    ): CouchResult<ViewResult> {
        val groups = mutableMapOf<String, MutableList<Pair<String, String>>>()

        for (key in keys) {
            view.index[key]?.let { entries ->
                for (entry in entries) {
                    val groupKey = if (groupLevel != null) {
                        getGroupKey(entry.key, groupLevel)
                    } else {
                        entry.key
                    }

                    groups.getOrPut(groupKey) { mutableListOf() }.add(entry.key to entry.value)
                }
            }
        }

        val results = mutableListOf<ViewRow>()
        for ((groupKey, groupData) in groups) {
            val groupKeys = groupData.map { it.first }
            val groupValues = groupData.map { it.second }

            val result = engine.executeReduce(reduceFunction, groupKeys, groupValues, false).getOrThrow()

            val row = ViewRow(
                id = null,
                key = groupKey,
                value = result.value,
                doc = null
            )

            results.add(row)
        }

        return Result.success(
            ViewResult(
                totalRows = results.size.toULong(),
                offset = 0u,
                rows = results,
                updateSeq = view.lastSeq,
                nextCursor = null
            )
        )
    }

    /**
     * Get group key for a given level
     */
    private fun getGroupKey(key: String, level: UInt): String {
        // Simplified - in real impl would parse JSON array and truncate
        return key
    }

    /**
     * Compare two JSON values for ordering
     */
    private fun compareKeys(a: String, b: String): Int {
        // Simplified string comparison - real impl would parse JSON
        return a.compareTo(b)
    }

    /**
     * Get view server statistics
     */
    suspend fun getStats(): Map<String, Any> {
        return viewsMutex.withLock {
            val totalIndexEntries = views.values.sumOf { view ->
                view.index.values.sumOf { it.size }
            }

            mapOf(
                "total_views" to views.size,
                "javascript_enabled" to config.enableJavascript,
                "total_index_entries" to totalIndexEntries
            )
        }
    }

    /**
     * Clear all view caches
     */
    suspend fun clearCaches() {
        viewsMutex.withLock {
            views.clear()
        }
    }
}

/**
 * View server configuration
 */
data class ViewServerConfig(
    val enableJavascript: Boolean = true,
    val maxMapResults: Int = 10000,
    val maxReduceDepth: UInt = 10u,
    val timeoutMs: ULong = 30000u,
    val cacheViews: Boolean = true,
    val allowBuiltinReduces: Boolean = true
) {
    companion object {
        fun default(): ViewServerConfig = ViewServerConfig()
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
    val compiledAt: Instant,
    val lastSeq: ULong,
    val index: Map<String, List<ViewIndexEntry>>
)

/**
 * View index entry
 */
data class ViewIndexEntry(
    val docId: String,
    val key: String,
    val value: String,
    val docSeq: Long
)

/**
 * Map result from a single document
 */
data class MapResult(
    val key: String,
    val value: String
)

/**
 * Reduce result
 */
data class ReduceResult(
    val key: String?,
    val value: String
)

/**
 * JavaScript engine wrapper (simplified mock implementation)
 */
class JavaScriptEngine private constructor(
    private val contextId: String
) {
    companion object {
        fun new(): CouchResult<JavaScriptEngine> {
            Result.success(JavaScriptEngine(generateUuid()))
        }
    }

    /**
     * Execute map function on a document
     */
    fun executeMap(mapFunction: String, doc: Document): CouchResult<List<MapResult>> {
        // Simplified implementation - real impl would use a JS engine
        val results = when {
            "emit(doc._id, doc)" in mapFunction -> {
                listOf(
                    MapResult(
                        key = doc.id,
                        value = doc.data
                    )
                )
            }

            "doc.type" in mapFunction -> {
                // Extract type from JSON - simplified
                val docType = extractJsonString(doc.data, "type")
                if (docType != null) {
                    listOf(
                        MapResult(
                            key = docType,
                            value = doc.id
                        )
                    )
                } else {
                    emptyList()
                }
            }

            "emit(null, 1)" in mapFunction -> {
                listOf(
                    MapResult(
                        key = "null",
                        value = "1"
                    )
                )
            }

            "doc.created_at" in mapFunction -> {
                val createdAt = extractJsonString(doc.data, "created_at")
                if (createdAt != null) {
                    listOf(
                        MapResult(
                            key = createdAt,
                            value = """{"id":"${doc.id}","rev":"${doc.rev}"}"""
                        )
                    )
                } else {
                    emptyList()
                }
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
        keys: List<String>,
        values: List<String>,
        rereduce: Boolean
    ): CouchResult<ReduceResult> {
        val result = when (reduceFunction.trim()) {
            "_count", "function(keys, values, rereduce) { return values.length; }" -> {
                ReduceResult(
                    key = null,
                    value = values.size.toString()
                )
            }

            "_sum" -> {
                val sum = values.sumOf { it.toDoubleOrNull() ?: 0.0 }
                ReduceResult(
                    key = null,
                    value = sum.toString()
                )
            }

            "_stats" -> {
                val numbers = values.mapNotNull { it.toDoubleOrNull() }

                if (numbers.isEmpty()) {
                    ReduceResult(
                        key = null,
                        value = """{"sum":0,"count":0,"min":null,"max":null,"sumsqr":0}"""
                    )
                } else {
                    val sum = numbers.sum()
                    val count = numbers.size
                    val min = numbers.minOrNull()!!
                    val max = numbers.maxOrNull()!!
                    val sumsqr = numbers.sumOf { it * it }

                    ReduceResult(
                        key = null,
                        value = """{"sum":$sum,"count":$count,"min":$min,"max":$max,"sumsqr":$sumsqr}"""
                    )
                }
            }

            else -> {
                ReduceResult(
                    key = null,
                    value = "null"
                )
            }
        }

        return Result.success(result)
    }

    /**
     * Extract string value from simple JSON
     */
    private fun extractJsonString(json: String, key: String): String? {
        val pattern = """"$key"\s*:\s*"([^"]*)"""".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
    }
}
