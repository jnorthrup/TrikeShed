package borg.literbike.ccek.store.couchdb

/**
 * CouchDB error types
 */

/**
 * CouchDB error
 */
data class CouchError(
    val error: String,
    val reason: String
) {
    companion object {
        fun new(error: String, reason: String): CouchError = CouchError(error, reason)
        fun badRequest(reason: String): CouchError = CouchError("bad_request", reason)
        fun notFound(reason: String): CouchError = CouchError("not_found", reason)
        fun conflict(reason: String): CouchError = CouchError("conflict", reason)
        fun unauthorized(reason: String): CouchError = CouchError("unauthorized", reason)
        fun forbidden(reason: String): CouchError = CouchError("forbidden", reason)
        fun methodNotAllowed(reason: String): CouchError = CouchError("method_not_allowed", reason)
        fun notAcceptable(reason: String): CouchError = CouchError("not_acceptable", reason)
        fun preconditionFailed(reason: String): CouchError = CouchError("precondition_failed", reason)
        fun requestEntityTooLarge(reason: String): CouchError = CouchError("request_entity_too_large", reason)
        fun unsupportedMediaType(reason: String): CouchError = CouchError("unsupported_media_type", reason)
        fun internalServerError(reason: String): CouchError = CouchError("internal_server_error", reason)
        fun serviceUnavailable(reason: String): CouchError = CouchError("service_unavailable", reason)
    }

    /**
     * HTTP status code mapping for CouchDB errors
     */
    fun statusCode(): UShort = when (error) {
        "bad_request" -> 400u
        "unauthorized" -> 401u
        "forbidden" -> 403u
        "not_found" -> 404u
        "method_not_allowed" -> 405u
        "not_acceptable" -> 406u
        "conflict" -> 409u
        "precondition_failed" -> 412u
        "request_entity_too_large" -> 413u
        "unsupported_media_type" -> 415u
        "internal_server_error" -> 500u
        "service_unavailable" -> 503u
        else -> 500u
    }

    override fun toString(): String = "$error: $reason"
}

/**
 * Result type for CouchDB operations
 */
typealias CouchResult<T> = Result<T>

/**
 * Extension to convert Result to CouchResult
 */
fun <T> Result<T>.toCouchResult(errorMessage: String = "Operation failed"): CouchResult<T> {
    return mapCatching { it }.recoverCatching {
        throw CouchError.internalServerError(it.message ?: errorMessage)
    }
}
