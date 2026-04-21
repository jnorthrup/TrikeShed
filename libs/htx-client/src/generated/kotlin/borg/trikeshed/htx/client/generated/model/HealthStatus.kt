package borg.trikeshed.htx.client.generated.model

/**
 * Generated from ../server/openapi/htx-general.openapi.yaml by ./gradlew -p libs/htx-client openApiGenerateHtxGeneralClient.
 * Repository policy: this checked-in file must be regenerated, not edited by hand.
 */

data class HealthStatus(
    val body: String,
) {
    val ok: Boolean
        get() = body == "ok"
}
