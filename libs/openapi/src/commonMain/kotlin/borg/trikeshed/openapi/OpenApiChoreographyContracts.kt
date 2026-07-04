package borg.trikeshed.openapi

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*

/**
 * OpenAPI Generated API contracts — choreography for generated APIs.
 *
 * Stage 11: OpenAPI Generated APIs
 *
 * @see TODO.md Stage 11
 */

// ==================== GENERATED REQUEST ====================

/**
 * Generated request — request type per operation.
 */
data class GeneratedRequest(
    val operationId: String,
    val method: HttpMethod,
    val path: String,
    val queryParams: Map<String, String> = emptyMap(),
    val pathParams: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val body: Series<Byte>? = null
)

// ==================== HTTP METHOD ====================

/**
 * HTTP method for generated APIs.
 */
enum class HttpMethod {
    GET,
    POST,
    PUT,
    DELETE,
    PATCH
}
