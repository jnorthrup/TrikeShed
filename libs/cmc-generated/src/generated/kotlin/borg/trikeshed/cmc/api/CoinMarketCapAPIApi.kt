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
}

/** Default implementation — caller provides the low-level call. */
class DefaultCoinMarketCapAPIApi(
    private val call: suspend (GeneratedRequest) -> String,
) : CoinMarketCapAPIApi {
    override suspend fun cmcEndpointOverview(): String = call(CoinMarketCapAPIApiContract.CmcEndpointOverview.request)
}

/** Contract constants for each CoinMarketCapAPI operation. */
object CoinMarketCapAPIApiContract {
          object CmcEndpointOverview {
              const val operationId: String = "cmcEndpointOverview"
              val request: GeneratedRequest = GeneratedRequest(method = HttpMethod.GET, path = "/api/documentation/pro-api-reference/endpoint-overview")
          }
  
}