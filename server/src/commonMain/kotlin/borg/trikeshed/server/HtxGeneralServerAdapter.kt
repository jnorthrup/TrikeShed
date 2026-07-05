package borg.trikeshed.server

import borg.trikeshed.htx.HtxKey
import borg.trikeshed.htx.HtxResponse
import borg.trikeshed.htx.client.generated.api.DefaultHtxGeneralApi
import borg.trikeshed.htx.client.generated.api.HtxGeneralApi
import borg.trikeshed.htx.client.generated.infrastructure.GeneratedRequest
import kotlin.coroutines.CoroutineContext

class HtxGeneralServerAdapter(
   val context: CoroutineContext,
) {
    suspend fun execute(request: GeneratedRequest): HtxResponse {
        val htx = requireNotNull(context[HtxKey]) { "Expected HtxKey to be present in the server context" }
        return htx.request(
            method = request.method.name,
            path = request.path,
        )
    }

    fun client(): HtxGeneralApi =
        DefaultHtxGeneralApi { request ->
            val response = execute(request)
            check(response.status == 200) {
                "Expected 200 from htx-general server for ${request.method.name} ${request.path}, but got ${response.status} with body ${response.body.asString()}"
            }
            response.body.asString()
        }
}
