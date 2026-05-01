package borg.trikeshed.htx.client.generated.infrastructure

/**
 * Generated from /app/libs/server/openapi/htx-general.openapi.yaml
 * by ./gradlew generateHtxGeneralClientSources.
 * Repository policy: this checked-in file must be regenerated, not edited by hand.
 */

/** HTTP method enum. */
enum class HttpMethod {
    GET,
    POST,
    PUT,
    DELETE,
    PATCH,
}

/**
 * A fully-bound HTTP request — method, path, query params, and optional body.
 * Consumed by the server adapter which routes it through the reactor context.
 */
data class GeneratedRequest(
    val method: HttpMethod,
    val path: String,
    val queryParams: Map<String, String> = emptyMap(),
    val body: String? = null,
    val operationId: String? = null,
)
