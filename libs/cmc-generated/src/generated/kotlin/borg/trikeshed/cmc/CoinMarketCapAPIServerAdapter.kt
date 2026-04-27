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
            CmcEndpointOverview -> {
                ServerMessage(status = 501, body = "cmcEndpointOverview not implemented")
            }
            CmcCryptocurrency -> {
                ServerMessage(status = 501, body = "cmcCryptocurrency not implemented")
            }
            CmcExchange -> {
                ServerMessage(status = 501, body = "cmcExchange not implemented")
            }
            CmcGlobalMetrics -> {
                ServerMessage(status = 501, body = "cmcGlobalMetrics not implemented")
            }
            CmcContent -> {
                ServerMessage(status = 501, body = "cmcContent not implemented")
            }
            CmcCommunity -> {
                ServerMessage(status = 501, body = "cmcCommunity not implemented")
            }
            CmcIndex -> {
                ServerMessage(status = 501, body = "cmcIndex not implemented")
            }
            CmcCryptoOthers -> {
                ServerMessage(status = 501, body = "cmcCryptoOthers not implemented")
            }
            CmcToken -> {
                ServerMessage(status = 501, body = "cmcToken not implemented")
            }
            CmcPlatform -> {
                ServerMessage(status = 501, body = "cmcPlatform not implemented")
            }
            CmcHolder -> {
                ServerMessage(status = 501, body = "cmcHolder not implemented")
            }
            CmcOhlcv -> {
                ServerMessage(status = 501, body = "cmcOhlcv not implemented")
            }
            CmcTools -> {
                ServerMessage(status = 501, body = "cmcTools not implemented")
            }
            CmcDeprecated -> {
                ServerMessage(status = 501, body = "cmcDeprecated not implemented")
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
        object CmcCryptocurrency {
            const val operationId: String = "cmcCryptocurrency"
            const val path: String = "/api/documentation/pro-api-reference/cryptocurrency"
            const val method: String = "GET"
        }
        object CmcExchange {
            const val operationId: String = "cmcExchange"
            const val path: String = "/api/documentation/pro-api-reference/exchange"
            const val method: String = "GET"
        }
        object CmcGlobalMetrics {
            const val operationId: String = "cmcGlobalMetrics"
            const val path: String = "/api/documentation/pro-api-reference/global-metrics"
            const val method: String = "GET"
        }
        object CmcContent {
            const val operationId: String = "cmcContent"
            const val path: String = "/api/documentation/pro-api-reference/content"
            const val method: String = "GET"
        }
        object CmcCommunity {
            const val operationId: String = "cmcCommunity"
            const val path: String = "/api/documentation/pro-api-reference/community"
            const val method: String = "GET"
        }
        object CmcIndex {
            const val operationId: String = "cmcIndex"
            const val path: String = "/api/documentation/pro-api-reference/cmc-index"
            const val method: String = "GET"
        }
        object CmcCryptoOthers {
            const val operationId: String = "cmcCryptoOthers"
            const val path: String = "/api/documentation/pro-api-reference/crypto-others"
            const val method: String = "GET"
        }
        object CmcToken {
            const val operationId: String = "cmcToken"
            const val path: String = "/api/documentation/pro-api-reference/token"
            const val method: String = "GET"
        }
        object CmcPlatform {
            const val operationId: String = "cmcPlatform"
            const val path: String = "/api/documentation/pro-api-reference/platform"
            const val method: String = "GET"
        }
        object CmcHolder {
            const val operationId: String = "cmcHolder"
            const val path: String = "/api/documentation/pro-api-reference/holder"
            const val method: String = "GET"
        }
        object CmcOhlcv {
            const val operationId: String = "cmcOhlcv"
            const val path: String = "/api/documentation/pro-api-reference/ohlcv"
            const val method: String = "GET"
        }
        object CmcTools {
            const val operationId: String = "cmcTools"
            const val path: String = "/api/documentation/pro-api-reference/tools"
            const val method: String = "GET"
        }
        object CmcDeprecated {
            const val operationId: String = "cmcDeprecated"
            const val path: String = "/api/documentation/pro-api-reference/deprecated"
            const val method: String = "GET"
        }
    }
}