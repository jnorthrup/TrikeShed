package borg.trikeshed.viewserver

/**
 * CouchDB 1.7.2 view server protocol — JSON-lines over stdin/stdout.
 *
 * Each line is a complete JSON value. Commands arrive on stdin,
 * responses are written to stdout.
 */
sealed class CouchCommand {
    /** `["reset"]` — clear all state, forget all functions. */
    data object Reset : CouchCommand()

    /** `["add_fun", "function(doc) { ... }"]` — register a new view function. */
    data class AddFun(val source: String) : CouchCommand()

    /** `["map_doc", {...doc...}]` — map a single document through all active functions. */
    data class MapDoc(val doc: Map<String, Any?>) : CouchCommand()

    /** `["reduce", [source, ...], ...]` — reduce values. */
    data class Reduce(val sources: List<String>, val values: List<Any?>) : CouchCommand()

    /** `["rereduce", [source, ...], ...]` — re-reduce values. */
    data class Rereduce(val sources: List<String>, val values: List<Any?>) : CouchCommand()

    companion object {
        /**
         * Parse a JSON array into a [CouchCommand].
         * Returns null if the command is unrecognized.
         */
        fun parse(json: List<*>): CouchCommand? {
            if (json.isEmpty()) return null
            val op = json[0] as? String ?: return null
            return when (op) {
                "reset" -> Reset
                "add_fun" -> {
                    val source = json.getOrNull(1) as? String ?: return null
                    AddFun(source)
                }
                "map_doc" -> {
                    @Suppress("UNCHECKED_CAST")
                    val doc = json.getOrNull(1) as? Map<String, Any?> ?: return null
                    MapDoc(doc)
                }
                "reduce" -> {
                    @Suppress("UNCHECKED_CAST")
                    val fns = (json.getOrNull(1) as? List<*>)?.mapNotNull { it as? String } ?: return null
                    val values = json.getOrNull(2) as? List<*> ?: emptyList<Any?>()
                    Reduce(fns, values as List<Any?>)
                }
                "rereduce" -> {
                    @Suppress("UNCHECKED_CAST")
                    val fns = (json.getOrNull(1) as? List<*>)?.mapNotNull { it as? String } ?: return null
                    val values = json.getOrNull(2) as? List<*> ?: emptyList<Any?>()
                    Rereduce(fns, values as List<Any?>)
                }
                else -> null
            }
        }
    }
}

/**
 * Response from the view server to CouchDB.
 */
sealed class CouchResponse {
    /** `true` — acknowledge reset or add_fun. */
    data object True : CouchResponse() {
        override fun toString() = "true"
    }

    /** `[[[k,v],...], ...]` — map results from multiple functions. */
    data class MapResults(val perFunction: List<List<List<Any?>>>) : CouchResponse()

    /** `[true, [result]]` — reduce/rereduce result. */
    data class ReduceResult(val value: Any?) : CouchResponse()

    /** `"error"` or structured error. */
    data class Error(val message: String) : CouchResponse()
}
