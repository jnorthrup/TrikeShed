package borg.trikeshed.htx.client.generated.api

/**
 * Generated from /app/libs/server/openapi/htx-general.openapi.yaml
 * by ./gradlew generateHtxGeneralClientSources.
 * Repository policy: this checked-in file must be regenerated, not edited by hand.
 */

import borg.trikeshed.htx.client.generated.infrastructure.GeneratedRequest
import borg.trikeshed.htx.client.generated.infrastructure.HttpMethod

/** Generated API interface for HtxGeneral. */
interface HtxGeneralApi {
    suspend fun getHealth(): String
}

/** Default implementation — caller provides the low-level call. */
class DefaultHtxGeneralApi(
   val call: suspend (GeneratedRequest) -> String,
) : HtxGeneralApi {
    override suspend fun getHealth(): String = call(HtxGeneralApiContract.GetHealth.request)
}

/** Contract constants for each HtxGeneral operation. */
object HtxGeneralApiContract {
          object GetHealth {
              const val operationId: String = "getHealth"
              val request: GeneratedRequest = GeneratedRequest(method = HttpMethod.GET, path = "/health")
          }

}