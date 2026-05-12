package borg.trikeshed.couch.api

/**
 * A single view definition inside a CouchDB 1.1 design document.
 */
data class CouchViewDefinition(
    val map: CharSequence,
    val reduce: CharSequence? = null,
)
