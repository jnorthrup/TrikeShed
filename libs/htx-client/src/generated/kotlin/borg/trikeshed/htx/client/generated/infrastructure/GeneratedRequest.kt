package borg.trikeshed.htx.client.generated.infrastructure

/**
 * Generated from ../server/openapi/htx-general.openapi.yaml by ./gradlew -p libs/htx-client openApiGenerateHtxGeneralClient.
 * Repository policy: this checked-in file must be regenerated, not edited by hand.
 */

enum class HttpMethod {
    GET,
}

data class GeneratedRequest(
    val method: HttpMethod,
    val path: String,
)
