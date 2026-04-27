package borg.trikeshed.cmc.api

/**
 * Generated from /Users/jim/work/TrikeShed/libs/cmc/endpoint-overview/openapi/coinmarketcap.openapi.yaml
 * by ./gradlew generateCmcSources.
 * Repository policy: this checked-in file must be regenerated, not edited by hand.
 */

import borg.trikeshed.cmc.infrastructure.GeneratedRequest
import borg.trikeshed.cmc.infrastructure.HttpMethod

/** Generated API interface for CoinMarketCapAPI. */
interface CoinMarketCapAPIApi {
    suspend fun cmcEndpointOverview(): String
    suspend fun cmcCryptocurrency(): String
    suspend fun cmcExchange(): String
    suspend fun cmcGlobalMetrics(): String
    suspend fun cmcContent(): String
    suspend fun cmcCommunity(): String
    suspend fun cmcIndex(): String
    suspend fun cmcCryptoOthers(): String
    suspend fun cmcToken(): String
    suspend fun cmcPlatform(): String
    suspend fun cmcHolder(): String
    suspend fun cmcOhlcv(): String
    suspend fun cmcTools(): String
    suspend fun cmcDeprecated(): String
}

/** Default implementation — caller provides the low-level call. */
class DefaultCoinMarketCapAPIApi(
    private val call: suspend (GeneratedRequest) -> String,
) : CoinMarketCapAPIApi {
    override suspend fun cmcEndpointOverview(): String = call(CoinMarketCapAPIApiContract.CmcEndpointOverview.request)    override suspend fun cmcCryptocurrency(): String = call(CoinMarketCapAPIApiContract.CmcCryptocurrency.request)    override suspend fun cmcExchange(): String = call(CoinMarketCapAPIApiContract.CmcExchange.request)    override suspend fun cmcGlobalMetrics(): String = call(CoinMarketCapAPIApiContract.CmcGlobalMetrics.request)    override suspend fun cmcContent(): String = call(CoinMarketCapAPIApiContract.CmcContent.request)    override suspend fun cmcCommunity(): String = call(CoinMarketCapAPIApiContract.CmcCommunity.request)    override suspend fun cmcIndex(): String = call(CoinMarketCapAPIApiContract.CmcIndex.request)    override suspend fun cmcCryptoOthers(): String = call(CoinMarketCapAPIApiContract.CmcCryptoOthers.request)    override suspend fun cmcToken(): String = call(CoinMarketCapAPIApiContract.CmcToken.request)    override suspend fun cmcPlatform(): String = call(CoinMarketCapAPIApiContract.CmcPlatform.request)    override suspend fun cmcHolder(): String = call(CoinMarketCapAPIApiContract.CmcHolder.request)    override suspend fun cmcOhlcv(): String = call(CoinMarketCapAPIApiContract.CmcOhlcv.request)    override suspend fun cmcTools(): String = call(CoinMarketCapAPIApiContract.CmcTools.request)    override suspend fun cmcDeprecated(): String = call(CoinMarketCapAPIApiContract.CmcDeprecated.request)
}

/** Contract constants for each CoinMarketCapAPI operation. */
object CoinMarketCapAPIApiContract {
          object CmcEndpointOverview {
              const val operationId: String = "cmcEndpointOverview"
              val request: GeneratedRequest = GeneratedRequest(method = HttpMethod.GET, path = "/api/documentation/pro-api-reference/endpoint-overview")
          }
  
  
          object CmcCryptocurrency {
              const val operationId: String = "cmcCryptocurrency"
              val request: GeneratedRequest = GeneratedRequest(method = HttpMethod.GET, path = "/api/documentation/pro-api-reference/cryptocurrency")
          }
  
  
          object CmcExchange {
              const val operationId: String = "cmcExchange"
              val request: GeneratedRequest = GeneratedRequest(method = HttpMethod.GET, path = "/api/documentation/pro-api-reference/exchange")
          }
  
  
          object CmcGlobalMetrics {
              const val operationId: String = "cmcGlobalMetrics"
              val request: GeneratedRequest = GeneratedRequest(method = HttpMethod.GET, path = "/api/documentation/pro-api-reference/global-metrics")
          }
  
  
          object CmcContent {
              const val operationId: String = "cmcContent"
              val request: GeneratedRequest = GeneratedRequest(method = HttpMethod.GET, path = "/api/documentation/pro-api-reference/content")
          }
  
  
          object CmcCommunity {
              const val operationId: String = "cmcCommunity"
              val request: GeneratedRequest = GeneratedRequest(method = HttpMethod.GET, path = "/api/documentation/pro-api-reference/community")
          }
  
  
          object CmcIndex {
              const val operationId: String = "cmcIndex"
              val request: GeneratedRequest = GeneratedRequest(method = HttpMethod.GET, path = "/api/documentation/pro-api-reference/cmc-index")
          }
  
  
          object CmcCryptoOthers {
              const val operationId: String = "cmcCryptoOthers"
              val request: GeneratedRequest = GeneratedRequest(method = HttpMethod.GET, path = "/api/documentation/pro-api-reference/crypto-others")
          }
  
  
          object CmcToken {
              const val operationId: String = "cmcToken"
              val request: GeneratedRequest = GeneratedRequest(method = HttpMethod.GET, path = "/api/documentation/pro-api-reference/token")
          }
  
  
          object CmcPlatform {
              const val operationId: String = "cmcPlatform"
              val request: GeneratedRequest = GeneratedRequest(method = HttpMethod.GET, path = "/api/documentation/pro-api-reference/platform")
          }
  
  
          object CmcHolder {
              const val operationId: String = "cmcHolder"
              val request: GeneratedRequest = GeneratedRequest(method = HttpMethod.GET, path = "/api/documentation/pro-api-reference/holder")
          }
  
  
          object CmcOhlcv {
              const val operationId: String = "cmcOhlcv"
              val request: GeneratedRequest = GeneratedRequest(method = HttpMethod.GET, path = "/api/documentation/pro-api-reference/ohlcv")
          }
  
  
          object CmcTools {
              const val operationId: String = "cmcTools"
              val request: GeneratedRequest = GeneratedRequest(method = HttpMethod.GET, path = "/api/documentation/pro-api-reference/tools")
          }
  
  
          object CmcDeprecated {
              const val operationId: String = "cmcDeprecated"
              val request: GeneratedRequest = GeneratedRequest(method = HttpMethod.GET, path = "/api/documentation/pro-api-reference/deprecated")
          }
  
}