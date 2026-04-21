package borg.trikeshed.couch.api

import java.net.URLEncoder

/**
 * Encodes [ViewQuery] into a URL query string using CouchDB 1.1 parameter names.
 */
object ViewQueryEncoder {
    /**
     * URL-encodes query params. JSON values must be URL-encoded.
     * Uses java.net.URLEncoder for encoding.
     */
    fun encode(query: ViewQuery): String {
        val parts = mutableListOf<String>()

        query.key?.let { parts += "key=${urlencode(toJson(it))}" }
        query.startKey?.let { parts += "startkey=${urlencode(toJson(it))}" }
        query.endKey?.let { parts += "endkey=${urlencode(toJson(it))}" }
        query.keys?.let { parts += "keys=${urlencode(toJson(it))}" }
        query.limit?.let { parts += "limit=$it" }
        query.skip?.let { parts += "skip=$it" }
        query.descending?.let { parts += "descending=$it" }
        query.group?.let { parts += "group=$it" }
        query.groupLevel?.let { parts += "group_level=$it" }
        query.includeDocs?.let { parts += "include_docs=$it" }
        query.reduce?.let { parts += "reduce=$it" }
        query.startKeyDocId?.let { parts += "startkey_docid=${urlencode(it)}" }
        query.endKeyDocId?.let { parts += "endkey_docid=${urlencode(it)}" }

        return parts.joinToString("&")
    }

    private fun urlencode(s: String): String = URLEncoder.encode(s, "UTF-8")

    /**
     * Convert a Kotlin value to its JSON representation for CouchDB query params.
     */
    private fun toJson(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"$value\""
        is Number -> value.toString()
        is Boolean -> value.toString()
        is List<*> -> "[" + value.joinToString(",") { toJson(it) } + "]"
        is Map<*, *> -> "{" + value.entries.joinToString(",") { (k, v) ->
            "\"${k}\":${toJson(v)}"
        } + "}"
        else -> "\"$value\""
    }
}
