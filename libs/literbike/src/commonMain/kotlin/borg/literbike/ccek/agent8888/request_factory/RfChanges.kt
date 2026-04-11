package borg.literbike.ccek.agent8888.request_factory

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Query parameters for the RF changes endpoint.
 */
data class RfChangesQuery(
    val since: Long? = null
)

/**
 * A single change event returned by the changes endpoint.
 */
@Serializable
data class RfChangeEvent(
    val id: String,
    val version: String,
    val payload: JsonObject,
    val deleted: Boolean
)

/**
 * Response envelope for the RF changes endpoint.
 */
@Serializable
data class RfChangesResponse(
    val results: List<RfChangeEvent>,
    val lastSeq: String
)

/**
 * A row from the database query result.
 */
data class ViewRow(
    val id: String,
    val doc: RfDocument?
)

/**
 * Result of a database view query.
 */
data class ViewResult(
    val rows: List<ViewRow>
)

/**
 * GET `/_rf/changes` -- returns RF change events from the default database.
 *
 * Accepts a `since` query parameter (Long sequence number). All live documents
 * are returned; deleted tombstones are included when the document's `_deleted`
 * field is `true`.
 *
 * This is a portable implementation that works with the RfAppState/RfDatabase interfaces.
 */
fun handleRfChanges(
    state: RfAppState,
    query: RfChangesQuery
): RfChangesResponse {
    val since = query.since ?: 0L

    // Ensure the default database exists; if it doesn't, return an empty result.
    if (!state.databaseExists(state.rfDefaultDb)) {
        return RfChangesResponse(
            results = emptyList(),
            lastSeq = since.toString()
        )
    }

    val db = state.getDatabaseClone(state.rfDefaultDb)
        .getOrElse {
            return RfChangesResponse(
                results = emptyList(),
                lastSeq = since.toString()
            )
        }

    // Fetch all documents
    val viewResult = db.getAllDocuments()
        .getOrElse {
            return RfChangesResponse(
                results = emptyList(),
                lastSeq = since.toString()
            )
        }

    val results = mutableListOf<RfChangeEvent>()
    var lastSeq = since

    for (row in viewResult.rows) {
        val doc = row.doc ?: continue

        val isDeleted = doc.deleted ?: false

        val event = RfChangeEvent(
            id = doc.id,
            version = doc.rev,
            payload = doc.data,
            deleted = isDeleted
        )

        results.add(event)
        lastSeq++
    }

    return RfChangesResponse(
        results = results,
        lastSeq = lastSeq.toString()
    )
}

/**
 * Helper extension to get all documents from a database.
 * This is a convenience wrapper around the database interface.
 */
private fun RfDatabase.getAllDocuments(): Result<ViewResult, String> {
    // This would typically use a view query in a real CouchDB implementation.
    // For the portable interface, we provide a default implementation that
    // would need to be overridden by concrete database implementations.
    return try {
        // Implementation would query all documents with include_docs=true
        // This is a placeholder -- concrete implementations should override
        Result.success(ViewResult(emptyList()))
    } catch (e: Exception) {
        Result.failure(e.message ?: "Unknown error")
    }
}

/**
 * Helper extension for Result.getOrElse
 */
private fun <T> Result<T, String>.getOrElse(fallback: (String) -> Nothing): T {
    return when (this) {
        is Result.Success -> value
        is Result.Failure -> fallback(reason)
    }
}
