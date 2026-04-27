package borg.trikeshed.cmc

/**
 * Generated from /Users/jim/work/TrikeShed/libs/cmc/endpoint-overview/openapi/coinmarketcap.openapi.yaml
 * by ./gradlew generateCmcSources.
 * Repository policy: this checked-in file must be regenerated, not edited by hand.
 */

/** Minimal HTTP response model used by the generated server adapter. */
data class ServerMessage(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: String?,
) {
    val isSuccess: Boolean get() = status in 200..299
}
