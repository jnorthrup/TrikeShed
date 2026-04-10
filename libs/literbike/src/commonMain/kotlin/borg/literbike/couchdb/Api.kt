package borg.literbike.couchdb

/**
 * API application state holder
 *
 * Centralizes all shared state for the CouchDB HTTP API layer.
 * In the Rust version this is the Axum `AppState`.
 */
data class AppState(
    val dbManager: DatabaseManager,
    val viewServer: ViewServer? = null,
    val rfTracker: borg.literbike.request_factory.OperationsTracker? = null,
    val rfDefaultDb: String = "rf_entities"
) {
    companion object {
        fun new(
            dbManager: DatabaseManager,
            viewServer: ViewServer? = null,
            rfTracker: borg.literbike.request_factory.OperationsTracker? = null,
            rfDefaultDb: String = "rf_entities"
        ) = AppState(dbManager, viewServer, rfTracker, rfDefaultDb)
    }
}
