package borg.trikeshed.jules.client

import borg.trikeshed.openapi.api.JulesAPIApi
import borg.trikeshed.openapi.api.DefaultJulesAPIApi
import borg.trikeshed.openapi.infrastructure.GeneratedRequest
import borg.trikeshed.openapi.infrastructure.HttpMethod
import borg.trikeshed.htx.client.HtxKey
import kotlin.coroutines.CoroutineContext

class JulesClient(val context: CoroutineContext, val apiKey: CharSequence) {
    val api: JulesAPIApi = DefaultJulesAPIApi { request: GeneratedRequest ->
        val htx = requireNotNull(context[HtxKey]) { "Expected HtxKey in coroutine context" }
        // We use HTX client to execute the call against Jules API
        // Ensure proper routing for Jules
        val finalPath = if (!request.path.contains("https://")) "https://jules.googleapis.com${request.path}" else request.path

        // Inject the API key into the final path
        val requestPath = if (finalPath.contains("?")) "${finalPath}&key=$apiKey" else "${finalPath}?key=$apiKey"
        val response = htx.request(
            method = request.method.name,
            path = requestPath,
            body = request.body ?: ""
        )
        check(response.status in 200..299) { "Jules API error: ${response.status} ${response.body}" }
        response.body
    }
}
