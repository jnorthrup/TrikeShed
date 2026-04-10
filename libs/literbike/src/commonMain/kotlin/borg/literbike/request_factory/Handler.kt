package borg.literbike.request_factory

import borg.literbike.couchdb.*
import kotlinx.serialization.json.*

/**
 * RequestFactory handler for `POST /_rf` -- dispatches batched RequestFactory operations to CouchDB.
 *
 * Maps operations:
 * - `find` -> `getDocument`
 * - `persist` -> `putDocument`
 * - `delete` -> `deleteDocument`
 */
class RfHandler(
    private val dbManager: DatabaseManager,
    private val rfDefaultDb: String,
    private val rfTracker: OperationsTracker
) {
    companion object {
        fun new(
            dbManager: DatabaseManager,
            rfDefaultDb: String,
            rfTracker: OperationsTracker
        ) = RfHandler(dbManager, rfDefaultDb, rfTracker)
    }

    /**
     * Dispatch batched RequestFactory operations
     */
    suspend fun handle(req: RfRequest): RfResponse {
        val _timer = rfTracker.recordBatchStart(req.invocations.size)
        val results = mutableListOf<RfResult>()
        val sideEffects = mutableListOf<JsonObject>()

        // Ensure the default database exists
        if (!dbManager.databaseExists(rfDefaultDb)) {
            dbManager.createDatabase(rfDefaultDb).onFailure {
                return RfResponse(
                    results = emptyList(),
                    sideEffects = listOf(buildJsonObject {
                        put("type", JsonPrimitive("error"))
                        put("message", JsonPrimitive("Failed to create database: ${it.message}"))
                    })
                )
            }
        }

        for (invocation in req.invocations) {
            runCatching {
                dispatchInvocation(invocation)
            }.onSuccess { result ->
                results.add(result)
            }.onFailure { error ->
                rfTracker.recordError(
                    invocation.operation,
                    invocation.entityType,
                    error.message ?: "Unknown error"
                )
                results.add(RfResult(
                    id = invocation.id ?: "",
                    version = invocation.version ?: "",
                    payload = null,
                    error = error.message
                ))
            }
        }

        // Add side effect: metrics snapshot
        val metrics = rfTracker.getMetrics()
        sideEffects.add(buildJsonObject {
            put("type", JsonPrimitive("metrics"))
            put("data", Json.encodeToJsonElement(OperationsMetrics.serializer(), metrics))
        })

        return RfResponse(results = results, sideEffects = sideEffects)
    }

    /**
     * Get metrics for RequestFactory operations
     */
    fun getMetrics(): OperationsMetrics = rfTracker.getMetrics()

    /**
     * Reset metrics for RequestFactory operations
     */
    fun resetMetrics() {
        rfTracker.reset()
    }

    /**
     * Dispatch a single invocation to the appropriate CouchDB operation
     */
    private fun dispatchInvocation(invocation: Invocation): RfResult {
        return when (invocation.operation) {
            "find" -> handleFind(invocation)
            "persist" -> handlePersist(invocation)
            "delete" -> handleDelete(invocation)
            else -> throw IllegalArgumentException("Unknown operation: ${invocation.operation}")
        }
    }

    /**
     * Handle a find operation -> maps to CouchDB getDocument
     */
    private fun handleFind(invocation: Invocation): RfResult {
        val id = invocation.id
            ?: throw IllegalArgumentException("Missing id for find operation")

        val dbInstance = dbManager.getDatabaseClone(rfDefaultDb).getOrThrow()

        return dbInstance.getDocument(id).map { doc ->
            rfTracker.recordFindSuccess()
            RfResult(
                id = doc.id,
                version = doc.rev,
                payload = doc.data,
                error = null
            )
        }.getOrThrow()
    }

    /**
     * Handle a persist operation -> maps to CouchDB putDocument
     */
    private fun handlePersist(invocation: Invocation): RfResult {
        val id = invocation.id
            ?: throw IllegalArgumentException("Missing id for persist operation")

        val dbInstance = dbManager.getDatabaseClone(rfDefaultDb).getOrThrow()

        val doc = Document(
            id = id,
            rev = invocation.version ?: "",
            deleted = null,
            attachments = null,
            data = invocation.payload ?: JsonObject(emptyMap())
        )

        return dbInstance.putDocument(doc).map { (newId, newRev) ->
            rfTracker.recordPersistSuccess()

            // Return the persisted document data with updated version
            val responseData = doc.data.buildUpon {
                put("_id", JsonPrimitive(newId))
                put("_rev", JsonPrimitive(newRev))
            }

            RfResult(
                id = newId,
                version = newRev,
                payload = responseData,
                error = null
            )
        }.getOrThrow()
    }

    /**
     * Handle a delete operation -> maps to CouchDB deleteDocument
     */
    private fun handleDelete(invocation: Invocation): RfResult {
        val id = invocation.id
            ?: throw IllegalArgumentException("Missing id for delete operation")

        val rev = invocation.version
            ?: throw IllegalArgumentException("Missing version (rev) for delete operation")

        val dbInstance = dbManager.getDatabaseClone(rfDefaultDb).getOrThrow()

        return dbInstance.deleteDocument(id, rev).map { (deletedId, deletedRev) ->
            rfTracker.recordDeleteSuccess()
            RfResult(
                id = deletedId,
                version = deletedRev,
                payload = null,
                error = null
            )
        }.getOrThrow()
    }
}

/**
 * Helper to build upon an existing JsonObject
 */
private fun JsonObject.buildUpon(block: MutableJsonDsl.() -> Unit): JsonObject {
    val mutable = toMutableMap()
    val dsl = MutableJsonDsl(mutable)
    block(dsl)
    return JsonObject(mutable)
}

private class MutableJsonDsl(private val map: MutableMap<String, JsonElement>) {
    fun put(key: String, value: JsonElement) { map[key] = value }
}
