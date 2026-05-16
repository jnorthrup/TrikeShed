package borg.trikeshed.couch.api

import borg.trikeshed.couch.internal.urlencode

/**
 * Encodes [ViewQuery] into a URL query string using CouchDB 1.1 parameter names.
 */
object ViewQueryEncoder {
    /**
     * URL-encodes query params. JSON values must be URL-encoded.
     * Uses the multiplatform couch internal encoder.
     */
    fun encode(query: ViewQuery): CharSequence {
        val parts = buildList {
            query.key?.let { add("key=${urlencode(toJson(it))}") }
            query.startKey?.let { add("startkey=${urlencode(toJson(it))}") }
            query.endKey?.let { add("endkey=${urlencode(toJson(it))}") }
            query.keys?.let { add("keys=${urlencode(toJson(it))}") }
            query.limit?.let { add("limit=$it") }
            query.skip?.let { add("skip=$it") }
            query.descending?.let { add("descending=$it") }
            query.group?.let { add("group=$it") }
            query.groupLevel?.let { add("group_level=$it") }
            query.includeDocs?.let { add("include_docs=$it") }
            query.reduce?.let { add("reduce=$it") }
            query.startKeyDocId?.let { add("startkey_docid=${urlencode(it)}") }
            query.endKeyDocId?.let { add("endkey_docid=${urlencode(it)}") }
        }

        return parts.joinToString("&")
    }

   fun urlEncode(s: CharSequence): CharSequence = urlencode(s)

    /**
     * Convert a Kotlin value to its JSON representation for CouchDB query params.
     */
   fun toJson(value: Any?): CharSequence = when (value) {
        null -> "null"
        is CharSequence -> "\"$value\""
        is Number -> value.toString()
        is Boolean -> value.toString()
        is List<*> -> "[" + value.joinToString(",") { toJson(it) } + "]"
        is Map<*, *> -> "{" + value.entries.joinToString(",") { (k, v) ->
            "\"${k}\":${toJson(v)}"
        } + "}"
        else -> "\"$value\""
    }
}
