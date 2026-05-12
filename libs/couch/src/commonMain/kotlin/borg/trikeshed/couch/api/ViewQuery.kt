package borg.trikeshed.couch.api

/**
 * All CouchDB 1.1 view query parameters.
 */
data class ViewQuery(
    val key: Any? = null,
    val startKey: Any? = null,
    val endKey: Any? = null,
    val keys: List<Any?>? = null,
    val limit: Int? = null,
    val skip: Int? = null,
    val descending: Boolean? = null,
    val group: Boolean? = null,
    val groupLevel: Int? = null,
    val includeDocs: Boolean? = null,
    val reduce: Boolean? = null,
    val startKeyDocId: CharSequence? = null,
    val endKeyDocId: CharSequence? = null,
)
