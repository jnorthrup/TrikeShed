package borg.trikeshed.cmc.pro.generated.infrastructure

/**
 * Generated from /Users/jim/work/TrikeShed/libs/cmc/pro-api/openapi/coinmarketcap-pro.openapi.yaml
 * by ./gradlew generateCmcProClientSources.
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
