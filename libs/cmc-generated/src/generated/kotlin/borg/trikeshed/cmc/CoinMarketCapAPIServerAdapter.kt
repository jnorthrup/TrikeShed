package borg.trikeshed.cmc

/**
 * Generated from /Users/jim/work/TrikeShed/libs/cmc/endpoint-overview/openapi/coinmarketcap.openapi.yaml
 * by ./gradlew generateCmcSources.
 * Repository policy: this checked-in file must be regenerated, not edited by hand.
 */

import kotlin.coroutines.CoroutineContext

/**
 * Generated server adapter for CoinMarketCapAPI.
 * Routes incoming GeneratedRequests to the appropriate reactor context.
 */
class CoinMarketCapAPIServerAdapter(private val context: CoroutineContext) {

    fun execute(request: borg.trikeshed.cmc.infrastructure.GeneratedRequest): ServerMessage {
        return when (request.operationId) {
            Contract.CmcEndpointOverview.operationId -> {
                ServerMessage(status = 501, body = "cmcEndpointOverview not implemented")
            }
            else -> ServerMessage(status = 404, body = "Unknown operation: ${request.operationId}")
        }
    }

    object Contract {
        object CmcEndpointOverview {
            const val operationId: String = "cmcEndpointOverview"
            const val path: String = "/api/documentation/pro-api-reference/endpoint-overview"
            const val method: String = "GET"
        }
    }
}
