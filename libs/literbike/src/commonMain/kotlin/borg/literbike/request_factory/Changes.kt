package borg.literbike.request_factory

import borg.literbike.couchdb.*
import kotlinx.serialization.json.*

/**
 * Query parameters for the RF changes endpoint.
 */
data class RfChangesQuery(
    val since: ULong? = null
)

/**
 * A single change event returned by the changes endpoint.
 */
data class RfChangeEvent(
    val id: String,
    val version: String,
    val payload: JsonObject,
    val deleted: Boolean
)

/**
 * Response envelope for the RF changes endpoint.
 */
data class RfChangesResponse(
    val results: List<RfChangeEvent>,
    val lastSeq: String
)

/**
 * GET `/_rf/changes` -- returns RF change events from the default database.
 *
 * Accepts a `since` query parameter (ULong sequence number). All live documents
 * are returned; deleted tombstones are included when the document's `_deleted`
 * field is `true`.
 */
fun rfChangesHandler(
    state: AppState,
    params: RfChangesQuery = RfChangesQuery()
): RfChangesResponse {
    val since = params.since ?: 0uL

    // Ensure the default database exists; if it doesn't, return an empty result.
    if (!state.dbManager.databaseExists(state.rfDefaultDb)) {
        return RfChangesResponse(
            results = emptyList(),
            lastSeq = since.toString()
        )
    }

    val dbInstance = state.dbManager.getDatabaseClone(state.rfDefaultDb).getOrNull()
        ?: return RfChangesResponse(
            results = emptyList(),
            lastSeq = since.toString()
        )

    // Use a default ViewQuery to fetch all documents.
    val query = ViewQuery(
        conflicts = null,
        descending = null,
        endkey = null,
        endkeyDocid = null,
        group = null,
        groupLevel = null,
        includeDocs = true,
        inclusiveEnd = null,
        key = null,
        keys = null,
        limit = null,
        reduce = null,
        skip = null,
        stale = null,
        startkey = null,
        startkeyDocid = null,
        updateSeq = null,
        cursor = null
    )

    val viewResult = dbInstance.getAllDocuments(query).getOrNull()
        ?: return RfChangesResponse(
            results = emptyList(),
            lastSeq = since.toString()
        )

    val results = mutableListOf<RfChangeEvent>()
    var lastSeq: ULong = since

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
