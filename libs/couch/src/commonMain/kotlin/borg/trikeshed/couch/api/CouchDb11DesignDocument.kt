package borg.trikeshed.couch.api

/**
 * CouchDB 1.1 design document with manual JSON serialization.
 */
data class CouchDb11DesignDocument(
    val id: CharSequence,
    val language: CharSequence,
    val views: Map<CharSequence, CouchViewDefinition>,
) {
    /**
     * Build JSON manually (no kotlinx.serialization).
     * Produces e.g. {"_id":"_design/example","language":"javascript","views":{"by_brand":{"map":"...","reduce":"_count"}}}
     */
    fun toJson(): CharSequence {
        val sb = StringBuilder()
        sb.append("{\"_id\":\"").append(escape(id)).append("\"")
        sb.append(",\"language\":\"").append(escape(language)).append("\"")
        if (views.isNotEmpty()) {
            sb.append(",\"views\":{")
            views.entries.forEachIndexed { idx, (name, defn) ->
                if (idx > 0) sb.append(",")
                sb.append("\"").append(escape(name)).append("\":{")
                sb.append("\"map\":\"").append(escape(defn.map)).append("\"")
                if (defn.reduce != null) {
                    sb.append(",\"reduce\":\"").append(escape(defn.reduce)).append("\"")
                }
                sb.append("}")
            }
            sb.append("}")
        }
        sb.append("}")
        return sb.toString()
    }

   fun escape(s: CharSequence): CharSequence = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}
