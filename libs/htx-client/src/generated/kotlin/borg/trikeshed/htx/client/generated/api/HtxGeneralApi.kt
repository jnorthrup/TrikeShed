package borg.trikeshed.htx.client.generated.api

/**
 * Generated from ../server/openapi/htx-general.openapi.yaml by ./gradlew -p libs/htx-client openApiGenerateHtxGeneralClient.
 * Repository policy: this checked-in file must be regenerated, not edited by hand.
 */

import borg.trikeshed.htx.client.generated.infrastructure.GeneratedRequest
import borg.trikeshed.htx.client.generated.infrastructure.HttpMethod
import borg.trikeshed.htx.client.generated.model.HealthStatus

interface HtxGeneralApi {
    suspend fun getHealth(): HealthStatus
}

class DefaultHtxGeneralApi(
    private val call: suspend (GeneratedRequest) -> String,
) : HtxGeneralApi {
    override suspend fun getHealth(): HealthStatus =
        HealthStatus(call(HtxGeneralApiContract.GetHealth.request))
}

object HtxGeneralApiContract {
    object GetHealth {
        const val operationId: String = "getHealth"
        const val responseBody: String = "ok"
        val request: GeneratedRequest = GeneratedRequest(
            method = HttpMethod.GET,
            path = "/health",
        )
    }
}
