package borg.literbike.ccek.agent8888.request_factory

import kotlinx.serialization.json.*

/**
 * Application state interface for RequestFactory.
 * Implementations provide database access and tracking.
 */
interface RfAppState {
    val rfTracker: OperationsTracker
    val rfDefaultDb: String

    fun databaseExists(dbName: String): Boolean
    fun createDatabase(dbName: String): Result<Unit>
    fun getDatabaseClone(dbName: String): Result<RfDatabase>
}

/**
 * Database interface for RequestFactory operations.
 */
interface RfDatabase {
    fun getDocument(id: String): Result<RfDocument>
    fun putDocument(doc: RfDocument): Result<Pair<String, String>>
    fun deleteDocument(id: String, rev: String): Result<Pair<String, String>>
}

/**
 * Document representation for RequestFactory.
 */
data class RfDocument(
    val id: String,
    val rev: String,
    val deleted: Boolean? = null,
    val data: JsonObject
)

/**
 * Axum handler for `POST /_rf` -- dispatches batched RequestFactory operations.
 *
 * Maps operations:
 * - `find` -> `getDocument`
 * - `persist` -> `putDocument`
 * - `delete` -> `deleteDocument`
 */
fun handleRfRequest(
    state: RfAppState,
    req: RfRequest
): RfResponse {
    val tracker = state.rfTracker
    val timer = tracker.recordBatchStart(req.invocations.size)
    val results = mutableListOf<RfResult>()
    val sideEffects = mutableListOf<JsonElement>()

    // Ensure the default database exists
    if (!state.databaseExists(state.rfDefaultDb)) {
        state.createDatabase(state.rfDefaultDb).onFailure { e ->
            return RfResponse(
                results = emptyList(),
                sideEffects = listOf(
                    buildJsonObject {
                        put("type", "error")
                        put("message", "Failed to create database: ${e.message}")
                    }
                )
            )
        }
    }

    for (invocation in req.invocations) {
        when (val result = dispatchInvocation(state, invocation)) {
            is Result.Success -> {
                results.add(result.value)
            }
            is Result.Failure -> {
                val (errorMsg, entityType) = result
                tracker.recordError(
                    invocation.operation,
                    entityType,
                    errorMsg
                )
                results.add(
                    RfResult(
                        id = invocation.id ?: "",
                        version = invocation.version ?: "",
                        payload = null,
                        error = errorMsg
                    )
                )
            }
        }
    }

    // Add side effect: metrics snapshot
    val metrics = tracker.getMetrics()
    sideEffects.add(
        buildJsonObject {
            put("type", "metrics")
            putJsonObject("data") {
                put("totalOperations", metrics.totalOperations)
                put("successCount", metrics.successCount)
                put("errorCount", metrics.errorCount)
                put("findCount", metrics.findCount)
                put("persistCount", metrics.persistCount)
                put("deleteCount", metrics.deleteCount)
                put("totalProcessingTimeUs", metrics.totalProcessingTimeUs)
                put("avgProcessingTimeUs", metrics.avgProcessingTimeUs)
                put("successRate", metrics.successRate)
            }
        }
    )

    timer.finish()

    return RfResponse(
        results = results,
        sideEffects = sideEffects
    )
}

/**
 * Get metrics for RequestFactory operations
 */
fun getRfMetrics(state: RfAppState): OperationsMetrics {
    return state.rfTracker.getMetrics()
}

/**
 * Reset metrics for RequestFactory operations
 */
fun resetRfMetrics(state: RfAppState) {
    state.rfTracker.reset()
}

/**
 * Dispatch a single invocation to the appropriate database operation
 */
private fun dispatchInvocation(
    state: RfAppState,
    invocation: Invocation
): Result<RfResult, Pair<String, String>> {
    return when (invocation.operation) {
        "find" -> handleFind(state, invocation)
        "persist" -> handlePersist(state, invocation)
        "delete" -> handleDelete(state, invocation)
        else -> Result.Failure(
            "Unknown operation: ${invocation.operation}" to invocation.entityType
        )
    }
}

/**
 * Handle a find operation -> maps to database getDocument
 */
private fun handleFind(
    state: RfAppState,
    invocation: Invocation
): Result<RfResult, Pair<String, String>> {
    val id = invocation.id
        ?: return Result.Failure("Missing id for find operation" to invocation.entityType)

    val db = state.getDatabaseClone(state.rfDefaultDb)
        .mapFailure { it to invocation.entityType }
        .getOrElse { return Result.Failure(it) }

    return when (val docResult = db.getDocument(id)) {
        is Result.Success -> {
            val doc = docResult.value
            state.rfTracker.recordFindSuccess()
            Result.Success(
                RfResult(
                    id = doc.id,
                    version = doc.rev,
                    payload = doc.data,
                    error = null
                )
            )
        }
        is Result.Failure -> {
            Result.Failure(docResult.reason to invocation.entityType)
        }
    }
}

/**
 * Handle a persist operation -> maps to database putDocument
 */
private fun handlePersist(
    state: RfAppState,
    invocation: Invocation
): Result<RfResult, Pair<String, String>> {
    val id = invocation.id
        ?: return Result.Failure("Missing id for persist operation" to invocation.entityType)

    val db = state.getDatabaseClone(state.rfDefaultDb)
        .mapFailure { it to invocation.entityType }
        .getOrElse { return Result.Failure(it) }

    // Build the document
    val docData = (invocation.payload ?: JsonObject(emptyMap())).toMutableMap()
    docData["_entity_type"] = JsonPrimitive(invocation.entityType)

    val doc = RfDocument(
        id = id,
        rev = invocation.version ?: "",
        deleted = null,
        data = JsonObject(docData)
    )

    return when (val putResult = db.putDocument(doc)) {
        is Result.Success -> {
            val (newId, newRev) = putResult.value
            state.rfTracker.recordPersistSuccess()

            // Return the persisted document data with updated version
            val responseData = docData.toMutableMap()
            responseData["_id"] = JsonPrimitive(newId)
            responseData["_rev"] = JsonPrimitive(newRev)

            Result.Success(
                RfResult(
                    id = newId,
                    version = newRev,
                    payload = JsonObject(responseData),
                    error = null
                )
            )
        }
        is Result.Failure -> {
            Result.Failure(putResult.reason to invocation.entityType)
        }
    }
}

/**
 * Handle a delete operation -> maps to database deleteDocument
 */
private fun handleDelete(
    state: RfAppState,
    invocation: Invocation
): Result<RfResult, Pair<String, String>> {
    val id = invocation.id
        ?: return Result.Failure("Missing id for delete operation" to invocation.entityType)

    val rev = invocation.version
        ?: return Result.Failure("Missing version (rev) for delete operation" to invocation.entityType)

    val db = state.getDatabaseClone(state.rfDefaultDb)
        .mapFailure { it to invocation.entityType }
        .getOrElse { return Result.Failure(it) }

    return when (val deleteResult = db.deleteDocument(id, rev)) {
        is Result.Success -> {
            val (deletedId, deletedRev) = deleteResult.value
            state.rfTracker.recordDeleteSuccess()
            Result.Success(
                RfResult(
                    id = deletedId,
                    version = deletedRev,
                    payload = null,
                    error = null
                )
            )
        }
        is Result.Failure -> {
            Result.Failure(deleteResult.reason to invocation.entityType)
        }
    }
}

/**
 * Sealed result type for operations
 */
sealed class Result<out T, out E> {
    data class Success<out T>(val value: T) : Result<T, Nothing>()
    data class Failure<out E>(val reason: E) : Result<Nothing, E>()

    companion object {
        fun <T, E> success(value: T): Result<T, E> = Success(value)
        fun <T, E> failure(reason: E): Result<T, E> = Failure(reason)
    }
}

/**
 * Helper extension to map failure types
 */
private fun <T, E, E2> Result<T, E>.mapFailure(transform: (E) -> E2): Result<T, E2> {
    return when (this) {
        is Result.Success -> this
        is Result.Failure -> Result.Failure(transform(reason))
    }
}

private fun <T, E> Result<T, E>.getOrElse(fallback: (E) -> Nothing): T {
    return when (this) {
        is Result.Success -> value
        is Result.Failure -> fallback(reason)
    }
}
