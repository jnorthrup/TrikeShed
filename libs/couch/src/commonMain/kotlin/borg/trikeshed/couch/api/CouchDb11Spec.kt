package borg.trikeshed.couch.api

/**
 * CouchDB 1.1 endpoint specification.
 */
data class CouchDb11Spec(
    val paths: Map<CharSequence, CharSequence>,
) {
    companion object {
        /**
         * Returns the 6 CouchDB 1.1 endpoint paths.
         */
        fun default(): CouchDb11Spec = CouchDb11Spec(
            paths = mapOf(
                "/{db}" to "GET",
                "/{db}/_all_docs" to "GET",
                "/{db}/_bulk_docs" to "POST",
                "/{db}/_design/{ddoc}" to "GET",
                "/{db}/_design/{ddoc}/_view/{view}" to "GET",
                "/{db}/{docid}/{attachment}" to "GET",
            )
        )
    }
}
