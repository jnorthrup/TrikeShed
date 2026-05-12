package org.bereft.robinhood.generated.infrastructure

/**
 * Generated from /Users/jim/work/TrikeShed/libs/rhood/generated/openapi/robinhood.openapi.yaml
 * by ./gradlew generateRobinhoodClientSources.
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
    val path: CharSequence,
    val queryParams: Map<CharSequence, CharSequence> = emptyMap(),
    val body: CharSequence? = null,
    val operationId: CharSequence? = null,
)
