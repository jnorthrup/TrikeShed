package borg.literbike.couchdb

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * CouchDB error response
 */
@Serializable
data class CouchError(
    val error: String,
    val reason: String
) {
    companion object {
        fun new(error: String, reason: String) = CouchError(error, reason)
        fun badRequest(reason: String) = new("bad_request", reason)
        fun notFound(reason: String) = new("not_found", reason)
        fun conflict(reason: String) = new("conflict", reason)
        fun unauthorized(reason: String) = new("unauthorized", reason)
        fun forbidden(reason: String) = new("forbidden", reason)
        fun methodNotAllowed(reason: String) = new("method_not_allowed", reason)
        fun notAcceptable(reason: String) = new("not_acceptable", reason)
        fun preconditionFailed(reason: String) = new("precondition_failed", reason)
        fun requestEntityTooLarge(reason: String) = new("request_entity_too_large", reason)
        fun unsupportedMediaType(reason: String) = new("unsupported_media_type", reason)
        fun internalServerError(reason: String) = new("internal_server_error", reason)
        fun serviceUnavailable(reason: String) = new("service_unavailable", reason)
    }

    fun statusCode(): UInt = when (error) {
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
 * Exception wrapper for CouchError
 */
class CouchException(val couchError: CouchError) : Exception(couchError.toString())

/**
 * Convert Result<T> to Result<T> with CouchError
 */
fun <T> Result<T>.toCouchResult(): Result<T> = this

/**
 * Create a CouchResult from a lambda that may throw
 */
inline fun <T> couchResult(block: () -> T): Result<T> = runCatching {
    block()
}

/**
 * Convert any exception to CouchError
 */
fun <T> Result<T>.mapToCouchError(): Result<T> = recoverCatching {
    throw CouchException(CouchError.internalServerError(it.message ?: "Unknown error"))
}
