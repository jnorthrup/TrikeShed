package borg.literbike.ccek.store.couchdb

/**
 * API error wrapper
 */
data class ApiError(
    val couchError: CouchError
) {
    companion object {
        fun from(error: CouchError): ApiError = ApiError(error)
    }

    fun statusCode(): UShort = couchError.statusCode()
}

/**
 * Generic API response wrapper
 */
data class ApiResponse<T>(
    val ok: Boolean,
    val data: T
) {
    companion object {
        fun <T> success(data: T): ApiResponse<T> = ApiResponse(ok = true, data = data)
    }
}

/**
 * Main application state
 */
data class AppState(
    val dbManager: DatabaseManager,
    val viewServer: ViewServer,
    val m2mManager: M2mManager,
    val tensorEngine: TensorEngine,
    val ipfsManager: IpfsManager?,
    val kvStore: IpfsKvStore?,
    val rfDefaultDb: String = "main"
)

/**
 * Create the main API router configuration
 *
 * In Kotlin/Native, routing is handled by Ktor or similar HTTP frameworks.
 * This function returns a configuration object describing all routes and handlers.
 */
fun createRouterConfig(state: AppState): RouterConfig {
    return RouterConfig(state)
}

/**
 * Router configuration describing all available routes
 */
class RouterConfig(
    val state: AppState
) {
    val routes = mutableListOf<RouteDefinition>()

    init {
        // Server endpoints
        addRoute("GET", "/", "getServerInfo")
        addRoute("GET", "/_all_dbs", "listDatabases")
        addRoute("GET", "/_stats", "getServerStats")

        // Database endpoints
        addRoute("PUT", "/:db", "createDatabase")
        addRoute("DELETE", "/:db", "deleteDatabase")
        addRoute("GET", "/:db", "getDatabaseInfo")
        addRoute("GET", "/:db/_all_docs", "getAllDocs")
        addRoute("POST", "/:db/_bulk_docs", "bulkDocs")
        addRoute("GET", "/:db/_changes", "getChanges")
        addRoute("POST", "/:db/_compact", "compactDatabase")

        // Document endpoints
        addRoute("GET", "/:db/:docId", "getDocument")
        addRoute("PUT", "/:db/:docId", "putDocument")
        addRoute("DELETE", "/:db/:docId", "deleteDocument")
        addRoute("HEAD", "/:db/:docId", "headDocument")

        // View endpoints
        addRoute("GET", "/:db/_design/:ddoc/_view/:view", "queryView")
        addRoute("POST", "/:db/_design/:ddoc/_view/:view", "queryViewPost")

        // Attachment endpoints
        addRoute("PUT", "/:db/:docId/:attachment", "putAttachment")
        addRoute("GET", "/:db/:docId/:attachment", "getAttachment")
        addRoute("DELETE", "/:db/:docId/:attachment", "deleteAttachment")

        // IPFS endpoints
        addRoute("POST", "/_ipfs/store", "ipfsStore")
        addRoute("GET", "/_ipfs/get/:cid", "ipfsGet")
        addRoute("GET", "/_ipfs/stats", "ipfsStats")
        addRoute("POST", "/_ipfs/gc", "ipfsGc")

        // M2M endpoints
        addRoute("POST", "/_m2m/send", "m2mSendMessage")
        addRoute("POST", "/_m2m/broadcast", "m2mBroadcastMessage")
        addRoute("GET", "/_m2m/peers", "m2mListPeers")
        addRoute("GET", "/_m2m/stats", "m2mGetStats")

        // Tensor endpoints
        addRoute("POST", "/_tensor/execute", "tensorExecuteOperation")
        addRoute("GET", "/_tensor/stats", "tensorGetStats")

        // Key-Value store endpoints
        addRoute("PUT", "/_kv/:key", "kvPut")
        addRoute("GET", "/_kv/:key", "kvGet")
        addRoute("DELETE", "/_kv/:key", "kvDelete")
        addRoute("GET", "/_kv", "kvListKeys")
        addRoute("GET", "/_kv/_stats", "kvGetStats")
    }

    private fun addRoute(method: String, path: String, handler: String) {
        routes.add(RouteDefinition(method, path, handler))
    }
}

/**
 * Route definition
 */
data class RouteDefinition(
    val method: String,
    val path: String,
    val handler: String
)

/**
 * Server endpoints handlers
 */
object ServerHandlers {

    suspend fun getServerInfo(state: AppState): ServerInfo {
        return state.dbManager.getServerInfo()
    }

    suspend fun listDatabases(state: AppState): CouchResult<List<String>> {
        return state.dbManager.listDatabases()
    }

    suspend fun getServerStats(state: AppState): Map<String, Any> {
        val stats = mutableMapOf<String, Any>()

        val dbList = state.dbManager.listDatabases().getOrNull() ?: emptyList()
        stats["database_count"] = dbList.size

        val viewStats = state.viewServer.getStats()
        stats["view_stats"] = viewStats

        val m2mMetrics = state.m2mManager.getMetrics()
        stats["m2m_stats"] = m2mMetrics

        val tensorStats = state.tensorEngine.getStats()
        stats["tensor_stats"] = tensorStats

        return stats
    }
}

/**
 * Database endpoint handlers
 */
object DatabaseHandlers {

    suspend fun createDatabase(dbName: String, state: AppState): CouchResult<DatabaseInfo> {
        return state.dbManager.createDatabase(dbName)
    }

    suspend fun deleteDatabase(dbName: String, state: AppState): CouchResult<Map<String, Boolean>> {
        return state.dbManager.deleteDatabase(dbName)
    }

    suspend fun getDatabaseInfo(dbName: String, state: AppState): CouchResult<DatabaseInfo> {
        return state.dbManager.getDatabaseInfo(dbName)
    }

    suspend fun compactDatabase(dbName: String, state: AppState): CouchResult<Map<String, Boolean>> {
        return state.dbManager.compactDatabase(dbName)
    }
}

/**
 * Document endpoint handlers
 */
object DocumentHandlers {

    suspend fun getDocument(dbName: String, docId: String, state: AppState): CouchResult<Document> {
        val dbInstance = state.dbManager.getDatabaseClone(dbName).getOrThrow()
        return dbInstance.getDocument(docId)
    }

    suspend fun putDocument(dbName: String, docId: String, doc: Document, state: AppState): CouchResult<Map<String, Any>> {
        val updatedDoc = doc.copy(id = docId)
        val dbInstance = state.dbManager.getDatabaseClone(dbName).getOrThrow()
        val (id, rev) = dbInstance.putDocument(updatedDoc).getOrThrow()

        return Result.success(
            mapOf(
                "ok" to true,
                "id" to id,
                "rev" to rev
            )
        )
    }

    suspend fun deleteDocument(dbName: String, docId: String, rev: String, state: AppState): CouchResult<Map<String, Any>> {
        val dbInstance = state.dbManager.getDatabaseClone(dbName).getOrThrow()
        val (id, newRev) = dbInstance.deleteDocument(docId, rev).getOrThrow()

        return Result.success(
            mapOf(
                "ok" to true,
                "id" to id,
                "rev" to newRev
            )
        )
    }

    suspend fun headDocument(dbName: String, docId: String, state: AppState): CouchResult<Boolean> {
        val dbInstance = state.dbManager.getDatabaseClone(dbName).getOrThrow()
        return Result.success(dbInstance.documentExists(docId))
    }

    suspend fun getAllDocs(dbName: String, query: ViewQuery, state: AppState): CouchResult<ViewResult> {
        val dbInstance = state.dbManager.getDatabaseClone(dbName).getOrThrow()
        return dbInstance.getAllDocuments(query)
    }

    suspend fun bulkDocs(dbName: String, bulkDocs: BulkDocs, state: AppState): CouchResult<List<BulkResult>> {
        val dbInstance = state.dbManager.getDatabaseClone(dbName).getOrThrow()
        val results = mutableListOf<BulkResult>()

        for (doc in bulkDocs.docs) {
            val result = dbInstance.putDocument(doc)
            result.fold(
                onSuccess = { (id, rev) ->
                    results.add(
                        BulkResult(
                            ok = true,
                            id = id,
                            rev = rev,
                            error = null,
                            reason = null
                        )
                    )
                },
                onFailure = { error ->
                    val couchError = error as? CouchError ?: CouchError.internalServerError(error.message ?: "Unknown error")
                    results.add(
                        BulkResult(
                            ok = null,
                            id = doc.id,
                            rev = null,
                            error = couchError.error,
                            reason = couchError.reason
                        )
                    )
                }
            )
        }

        return Result.success(results)
    }

    suspend fun getChanges(dbName: String, state: AppState): CouchResult<ChangesResponse> {
        // Simplified implementation - return empty changes
        return Result.success(
            ChangesResponse(
                results = emptyList(),
                lastSeq = "0",
                pending = 0u
            )
        )
    }
}

/**
 * View endpoint handlers
 */
object ViewHandlers {

    suspend fun queryView(dbName: String, ddoc: String, viewName: String, query: ViewQuery, state: AppState): CouchResult<ViewResult> {
        val ddocId = "_design/$ddoc"
        return state.viewServer.queryView(ddocId, viewName, query)
    }
}

/**
 * Attachment endpoint handlers
 */
object AttachmentHandlers {

    suspend fun putAttachment(
        dbName: String,
        docId: String,
        attachmentName: String,
        data: ByteArray,
        contentType: String,
        state: AppState
    ): CouchResult<Map<String, Any>> {
        val ipfsManager = state.ipfsManager
            ?: return Result.failure(CouchError.internalServerError("IPFS manager not available"))

        val ipfsCid = ipfsManager.storeData(data, contentType).getOrThrow()

        val dbInstance = state.dbManager.getDatabaseClone(dbName).getOrThrow()
        val doc = dbInstance.getDocument(docId).getOrThrow()

        val attachmentInfo = AttachmentInfo(
            contentType = contentType,
            length = data.size.toULong(),
            digest = ipfsCid.cid,
            stub = true,
            revpos = 1u,
            data = null
        )

        val existingAttachments = doc.attachments?.toMutableMap() ?: mutableMapOf()
        existingAttachments[attachmentName] = attachmentInfo

        val updatedDoc = doc.copy(attachments = existingAttachments)
        val (id, rev) = dbInstance.putDocument(updatedDoc).getOrThrow()

        return Result.success(
            mapOf(
                "ok" to true,
                "id" to id,
                "rev" to rev
            )
        )
    }

    suspend fun getAttachment(
        dbName: String,
        docId: String,
        attachmentName: String,
        state: AppState
    ): CouchResult<Pair<ByteArray, AttachmentInfo>> {
        val dbInstance = state.dbManager.getDatabaseClone(dbName).getOrThrow()
        val doc = dbInstance.getDocument(docId).getOrThrow()

        val attachmentInfo = doc.attachments?.get(attachmentName)
            ?: return Result.failure(CouchError.notFound("Attachment not found"))

        val ipfsManager = state.ipfsManager
            ?: return Result.failure(CouchError.internalServerError("IPFS manager not available"))

        val (data, _) = ipfsManager.getAttachment(attachmentInfo.digest).getOrThrow()

        return Result.success(data to attachmentInfo)
    }

    suspend fun deleteAttachment(
        dbName: String,
        docId: String,
        attachmentName: String,
        state: AppState
    ): CouchResult<Map<String, Any>> {
        val dbInstance = state.dbManager.getDatabaseClone(dbName).getOrThrow()
        val doc = dbInstance.getDocument(docId).getOrThrow()

        val attachments = doc.attachments?.toMutableMap() ?: mutableMapOf()
        val attachmentInfo = attachments.remove(attachmentName)
            ?: return Result.failure(CouchError.notFound("Attachment not found"))

        // Unpin from IPFS
        state.ipfsManager?.unpinContent(attachmentInfo.digest)

        val updatedDoc = doc.copy(attachments = attachments)
        val (id, rev) = dbInstance.putDocument(updatedDoc).getOrThrow()

        return Result.success(
            mapOf(
                "ok" to true,
                "id" to id,
                "rev" to rev
            )
        )
    }
}

/**
 * IPFS endpoint handlers
 */
object IpfsEndpointHandlers {

    suspend fun ipfsStore(data: ByteArray, contentType: String, state: AppState): CouchResult<IpfsCid> {
        val ipfsManager = state.ipfsManager
            ?: return Result.failure(CouchError.internalServerError("IPFS manager not available"))

        return ipfsManager.storeData(data, contentType)
    }

    suspend fun ipfsGet(cid: String, state: AppState): CouchResult<ByteArray> {
        val ipfsManager = state.ipfsManager
            ?: return Result.failure(CouchError.internalServerError("IPFS manager not available"))

        val (data, _) = ipfsManager.getAttachment(cid).getOrThrow()
        return Result.success(data)
    }

    suspend fun ipfsStats(state: AppState): CouchResult<Map<String, Any>> {
        val ipfsManager = state.ipfsManager
            ?: return Result.failure(CouchError.internalServerError("IPFS manager not available"))

        return ipfsManager.getStats()
    }

    suspend fun ipfsGc(state: AppState): CouchResult<Map<String, Any>> {
        val ipfsManager = state.ipfsManager
            ?: return Result.failure(CouchError.internalServerError("IPFS manager not available"))

        val removed = ipfsManager.garbageCollect().getOrThrow()
        return Result.success(
            mapOf(
                "ok" to true,
                "removed_objects" to removed.size,
                "removed_cids" to removed
            )
        )
    }
}

/**
 * M2M endpoint handlers
 */
object M2mEndpointHandlers {

    suspend fun m2mSendMessage(message: M2mMessage, state: AppState): CouchResult<Map<String, Boolean>> {
        if (message.recipient != null) {
            state.m2mManager.sendMessage(message.recipient, message.messageType, message.payload)
        } else {
            state.m2mManager.broadcastMessage(message.messageType, message.payload)
        }

        return Result.success(mapOf("ok" to true))
    }

    suspend fun m2mBroadcastMessage(payload: String, state: AppState): CouchResult<Map<String, Boolean>> {
        // Try to extract message type from payload
        val messageType = M2mMessageType.Custom("broadcast")
        state.m2mManager.broadcastMessage(messageType, payload)
        return Result.success(mapOf("ok" to true))
    }

    fun m2mListPeers(state: AppState): List<PeerInfo> {
        return state.m2mManager.listPeers()
    }

    fun m2mGetStats(state: AppState): M2mMetrics {
        return state.m2mManager.getMetrics()
    }
}

/**
 * Tensor endpoint handlers
 */
object TensorEndpointHandlers {

    suspend fun tensorExecuteOperation(operation: TensorOperation, state: AppState): CouchResult<TensorResult> {
        val dbName = operation.inputDocs.firstOrNull()?.let { "main" } ?: state.rfDefaultDb
        val dbInstance = state.dbManager.getDatabaseClone(dbName).getOrThrow()
        return state.tensorEngine.executeOperation(operation, dbInstance)
    }

    fun tensorGetStats(state: AppState): Map<String, String> {
        return state.tensorEngine.getStats()
    }
}

/**
 * KV endpoint handlers
 */
object KvEndpointHandlers {

    suspend fun kvPut(key: String, value: ByteArray, contentType: String, state: AppState): CouchResult<KvEntry> {
        val kvStore = state.kvStore
            ?: return Result.failure(CouchError.internalServerError("KV store not available"))

        return kvStore.put(key, value, contentType, emptyMap())
    }

    suspend fun kvGet(key: String, state: AppState): CouchResult<KvEntry> {
        val kvStore = state.kvStore
            ?: return Result.failure(CouchError.internalServerError("KV store not available"))

        return kvStore.get(key)
    }

    suspend fun kvDelete(key: String, state: AppState): CouchResult<Map<String, Any>> {
        val kvStore = state.kvStore
            ?: return Result.failure(CouchError.internalServerError("KV store not available"))

        val deleted = kvStore.delete(key).getOrThrow()
        return Result.success(
            mapOf(
                "ok" to true,
                "deleted" to deleted
            )
        )
    }

    suspend fun kvListKeys(state: AppState): CouchResult<List<String>> {
        val kvStore = state.kvStore
            ?: return Result.failure(CouchError.internalServerError("KV store not available"))

        return Result.success(kvStore.listKeys())
    }

    suspend fun kvGetStats(state: AppState): CouchResult<Map<String, Any>> {
        val kvStore = state.kvStore
            ?: return Result.failure(CouchError.internalServerError("KV store not available"))

        return kvStore.getStats()
    }
}
