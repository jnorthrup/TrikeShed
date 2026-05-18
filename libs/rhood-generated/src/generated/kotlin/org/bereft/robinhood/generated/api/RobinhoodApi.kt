package org.bereft.robinhood.generated.api

/**
 * Generated from /Users/jim/work/TrikeShed/libs/rhood/generated/openapi/robinhood.openapi.yaml
 * by ./gradlew generateRobinhoodClientSources.
 * Repository policy: this checked-in file must be regenerated, not edited by hand.
 */

import org.bereft.robinhood.generated.infrastructure.GeneratedRequest
import org.bereft.robinhood.generated.infrastructure.HttpMethod

/** Generated API interface for Robinhood. */
interface RobinhoodApi {
    suspend fun v2GetAccounts(): String
}

/** Default implementation — caller provides the low-level call. */
class DefaultRobinhoodApi(
   val call: suspend (GeneratedRequest) -> String,
) : RobinhoodApi {
    override suspend fun v2GetAccounts(): String =
        call(RobinhoodApiContract.V2GetAccounts.request)


}

/** Contract constants for each Robinhood operation. */
object RobinhoodApiContract {
          object V2GetAccounts {
              const val operationId: String = "v2GetAccounts"
              val request: GeneratedRequest = GeneratedRequest(method = HttpMethod.GET, path = "/api/v2/crypto/trading/accounts/")
          }
  
}